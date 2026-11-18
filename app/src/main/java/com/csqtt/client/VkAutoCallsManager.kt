package com.csqtt.client

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

object VkAutoCallsManager {
    data class StartResult(
        val hashes: String,
        val requestedCalls: Int,
        val createdCalls: Int,
    ) {
        val needsWorkerRedistribution: Boolean
            get() = createdCalls < requestedCalls
    }

    private data class ActiveCall(val callId: String, val hashKey: String)
    private data class PendingLog(val key: String, val message: String, val priority: Int, val isError: Boolean)

    private const val TAG = "VkAutoCalls"

    private val activeCalls = CopyOnWriteArrayList<ActiveCall>()
    private val pendingLogs = ArrayList<PendingLog>()
    private val pendingLogsLock = Any()

    fun hasActiveCalls(): Boolean = activeCalls.isNotEmpty()

    fun callCountForWorkers(workers: Int): Int {
        return (1..CsqttConstants.Tunnel.MAX_VK_HASHES).firstOrNull {
            workers <= WorkerCountPolicy.maximumForHashes(it)
        } ?: CsqttConstants.Tunnel.MAX_VK_HASHES
    }

    internal fun requestDelayForCallCount(callCount: Int): Long =
        if (callCount <= 4) {
            CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS
        } else {
            CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS
        }

    /**
     * Доводит набор звонков до `callCountForWorkers(workers)`, переиспользуя ссылки из
     * реестра профиля и заминчивая только недостающие.
     *
     * `StartResult.hashes` — сначала переиспользованные, затем свежие. `createdCalls`
     * считает все отданные хеши, поэтому `needsWorkerRedistribution` продолжает работать.
     */
    suspend fun ensureCalls(
        context: Context,
        token: String,
        workers: Int,
        profile: Int,
        userId: String = "",
        clearStoredTokenOnInvalid: Boolean = true,
    ): StartResult? {
        if (token.isBlank()) return null

        synchronized(pendingLogsLock) { pendingLogs.clear() }
        activeCalls.clear()

        val requested = callCountForWorkers(workers)
        val tokenFp = VkCallReusePolicy.tokenFingerprint(token)
        val store = VkCallStore(context)
        val nowMs = System.currentTimeMillis()
        val entries = store.load(profile)
        val eligible = VkCallReusePolicy.eligible(entries, tokenFp, userId, nowMs)
        val selection = VkCallReusePolicy.select(eligible, requested)
        val kept = selection.keep
        kept.forEach { entry ->
            activeCalls.add(ActiveCall(callId = entry.callId, hashKey = sha256Hex(entry.hash)))
        }

        // Чужие звонки (другой токен) закрыть нельзя — токен не тот. Свои истёкшие,
        // исчерпавшие бюджет падений и лишние сверх запроса закрываем best-effort.
        val keptIds = kept.mapTo(HashSet()) { it.callId }
        val eligibleIds = eligible.mapTo(HashSet()) { it.callId }
        val pruned = entries.filter { entry ->
            entry.mode == CsqttConstants.VkAutoHash.MODE_AUTO_API &&
                entry.callId !in keptIds &&
                (entry.callId in eligibleIds || VkCallOwnerPolicy.matches(entry, tokenFp, userId))
        }
        if (pruned.isNotEmpty()) {
            withTimeoutOrNull(CsqttConstants.VkAutoHash.FINISH_CALLS_TIMEOUT_MS) {
                finishCalls(pruned.map { ActiveCall(it.callId, sha256Hex(it.hash)) }, token, logResults = false)
            }
            log(
                "vk_calls_pruned",
                "[OK] Устаревшие звонки VK закрыты: ${pruned.size}",
                40,
                false,
            )
        }

        val minted = ArrayList<VkCallEntry>(selection.missing)
        if (selection.missing > 0) {
            val requestPacer = VkRequestPacer(
                requestDelayForCallCount(selection.missing),
                SystemClock::elapsedRealtime,
                { waitMs -> delay(waitMs) },
            )
            var completedSlots = 0
            while (completedSlots < selection.missing) {
                val result = runVkCallAttempts {
                    requestPacer.awaitRequestSlot()
                    withContext(Dispatchers.IO) { VkApi.startCall(token) }
                }
                completedSlots++
                when (result) {
                    is VkApiResult.Ok<*> -> {
                        val call = result.value as VkApi.StartedCall
                        minted.add(
                            VkCallEntry(
                                callId = call.callId,
                                hash = call.hash,
                                joinLink = call.joinLink,
                                createdAtMs = System.currentTimeMillis(),
                                tokenFp = tokenFp,
                                accountUserId = userId,
                                mode = CsqttConstants.VkAutoHash.MODE_AUTO_API,
                            ),
                        )
                        activeCalls.add(ActiveCall(callId = call.callId, hashKey = sha256Hex(call.hash)))
                        if (BuildConfig.DEBUG) {
                            // callId нужен полевому тесту, чтобы завершить один звонок снаружи.
                            Log.d(TAG, "call_created id=${call.callId} hash=${call.hash.take(8)}…")
                        }
                        log(
                            "vk_call_created_progress",
                            "[OK] Звонок VK создан ✓",
                            16,
                            false,
                        )
                    }

                    is VkApiResult.ApiError -> {
                        log(
                            "vk_auto_call_error",
                            "[ERR] Звонок VK не создан · код=${result.code} ${result.message}",
                            90,
                            true,
                        )
                        if (VkApi.isTokenInvalid(result)) {
                            if (clearStoredTokenOnInvalid) {
                                runCatching { SettingsStore(context).clearVkAccessToken() }
                            }
                            log(
                                "vk_token_invalidated",
                                if (clearStoredTokenOnInvalid) {
                                    "[ERR] Токен VK недействителен · войдите снова"
                                } else {
                                    "[ERR] Токен VK из ссылки недействителен"
                                },
                                95,
                                true,
                            )
                            // Токен один на все ссылки профиля: kept заминчены им же и тоже
                            // осиротеют, поэтому закрываем весь набор, а не только свежие.
                            val orphans = kept + minted
                            if (orphans.isNotEmpty()) {
                                withTimeoutOrNull(CsqttConstants.VkAutoHash.FINISH_CALLS_TIMEOUT_MS) {
                                    finishCalls(
                                        orphans.map { ActiveCall(it.callId, sha256Hex(it.hash)) },
                                        token,
                                        logResults = false,
                                    )
                                }
                            }
                            activeCalls.clear()
                            clearStore(store, profile)
                            return null
                        }
                    }

                    is VkApiResult.Failed -> {
                        log(
                            "vk_auto_call_error",
                            "[ERR] Звонок VK не создан · ${result.reason}",
                            90,
                            true,
                        )
                    }
                }
            }
        }

        if (minted.isNotEmpty() && minted.size < selection.missing) {
            log(
                "vk_auto_call_partial",
                "[OK] Звонки VK ${minted.size}/${selection.missing} · потоки распределены",
                45,
                false,
            )
        }

        // Не-auto_api записи в реестре не наши: их не закрываем и оставляем на месте.
        // Счётчики падений берём из актуального реестра: параллельное восстановление
        // могло увеличить их, пока мы минчили, и стухший снимок не должен это затереть.
        var claimed = 0
        persistStore(store, profile) { current ->
            val currentById = current.associateBy { it.callId }
            val mergedKept = kept.map { entry ->
                val stored = currentById[entry.callId]
                if (stored == null) {
                    entry
                } else {
                    entry.copy(
                        failCount = maxOf(entry.failCount, stored.failCount),
                        lastFailMs = listOfNotNull(entry.lastFailMs, stored.lastFailMs).maxOrNull(),
                    )
                }
            }.map { entry ->
                val claimedEntry = VkCallOwnerPolicy.claim(entry, userId)
                if (claimedEntry.accountUserId != entry.accountUserId) claimed += 1
                claimedEntry
            }
            val foreign = current.filter { it.mode != CsqttConstants.VkAutoHash.MODE_AUTO_API }
            (mergedKept + minted + foreign)
                .distinctBy { it.callId }
                .take(CsqttConstants.Tunnel.MAX_VK_HASHES)
        }

        if (kept.isNotEmpty()) {
            log(
                "vk_calls_reused",
                "[OK] Звонки VK переиспользованы: ${kept.size}",
                40,
                false,
            )
        }
        if (minted.isNotEmpty()) {
            log(
                "vk_calls_minted",
                "[OK] Звонки VK созданы: ${minted.size}",
                40,
                false,
            )
        }

        val hashes = kept.map { it.hash } + minted.map { it.hash }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "ensure requested=$requested reused=${kept.size} minted=${minted.size} pruned=${pruned.size} claimed=$claimed")
        }
        return if (hashes.isEmpty()) {
            null
        } else {
            StartResult(
                hashes = hashes.joinToString(","),
                requestedCalls = requested,
                createdCalls = hashes.size,
            )
        }
    }

    fun replayPendingLogs() {
        val copy = synchronized(pendingLogsLock) {
            val snapshot = ArrayList(pendingLogs)
            pendingLogs.clear()
            snapshot
        }
        copy.forEach { entry ->
            TunnelManager.updateLog(entry.key, entry.message, entry.priority, entry.isError)
        }
    }

    /**
     * Единственный пользовательский путь завершения: закрывает все звонки профиля из
     * реестра и очищает его. Обычная остановка туннеля сюда не попадает — ссылки
     * должны переживать перезапуск.
     */
    suspend fun closeAll(context: Context, token: String, profile: Int): Boolean {
        if (token.isBlank()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "close_all skipped reason=no_token")
            log(
                "vk_calls_closed",
                "[ERR] Звонки VK не закрыты: нет VK-токена",
                90,
                true,
            )
            return false
        }
        val store = VkCallStore(context)
        val entries = store.load(profile)
        activeCalls.clear()
        if (entries.isNotEmpty()) {
            withTimeoutOrNull(CsqttConstants.VkAutoHash.FINISH_CALLS_TIMEOUT_MS) {
                finishCalls(
                    entries.map { ActiveCall(it.callId, sha256Hex(it.hash)) },
                    token,
                    logResults = true,
                )
            }
        }
        clearStore(store, profile)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "close_all entries=${entries.size}")
        }
        return true
    }

    private suspend fun persistStore(
        store: VkCallStore,
        profile: Int,
        transform: (List<VkCallEntry>) -> List<VkCallEntry>,
    ) {
        try {
            store.update(profile, transform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось сохранить реестр звонков профиля $profile", e)
        }
    }

    private suspend fun clearStore(store: VkCallStore, profile: Int) {
        try {
            store.clear(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось очистить реестр звонков профиля $profile", e)
        }
    }

    private suspend fun finishCalls(calls: List<ActiveCall>, token: String, logResults: Boolean) {
        val results = coroutineScope {
            val deferredResults = calls.mapIndexed { index, call ->
                async(Dispatchers.IO) {
                    index to VkApi.forceFinishCall(token, call.callId)
                }
            }
            deferredResults.awaitAll()
        }

        if (logResults) {
            for ((_, result) in results) {
                when (result) {
                    is VkApiResult.Ok<*> -> TunnelManager.updateLog(
                        "vk_auto_call_finished",
                        "[OK] Звонок VK завершён",
                        40,
                        false,
                    )

                    is VkApiResult.ApiError -> TunnelManager.updateLog(
                        "vk_auto_call_finish_error",
                        "[ERR] Звонок VK не завершён · код=${result.code}",
                        60,
                        false,
                    )

                    is VkApiResult.Failed -> TunnelManager.updateLog(
                        "vk_auto_call_finish_error",
                        "[ERR] Звонок VK не завершён · ${result.reason}",
                        60,
                        false,
                    )
                }
            }
        }
    }

    private fun log(key: String, message: String, priority: Int, isError: Boolean) {
        if (!TunnelManager.isLoggingEnabled) return
        TunnelManager.updateLog(key, message, priority, isError)
        synchronized(pendingLogsLock) {
            pendingLogs.add(PendingLog(key, message, priority, isError))
        }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
