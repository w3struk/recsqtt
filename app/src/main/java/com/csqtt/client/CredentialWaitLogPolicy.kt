package com.csqtt.client

import java.util.Locale

/**
 * Разбор строк Rust-клиента о выдаче TURN-креденшелов и их свёртка в одну
 * строку журнала.
 *
 * Зачем: клиент пишет `[КРЕД #N] Ошибка: … Повторяем через 30s…` на каждую
 * неудачную попытку, а `TunnelLogPolicy.isInternalRecovery` эти строки из
 * журнала убирает. В отчёте оставался только итог «Активных: N», по которому
 * нельзя понять, почему воркеры набираются минутами. Здесь те же самые события
 * сводятся к состоянию «сколько групп ждут креды» без потери причины отказа.
 */
internal sealed interface CredentialSignal {
    /** `[КРЕД #N] Запрос` / `[КРЕД #N] Невалиден` — ждём ответа, журнал не трогаем. */
    data class Requested(val credentialId: Int) : CredentialSignal

    /** `[КРЕД #N] OK, TURN: …` — группа получила credential. */
    data class Acquired(val credentialId: Int) : CredentialSignal

    /** `[КРЕД #N] Ошибка: … Повторяем через 30s…` */
    data class Retry(
        val credentialId: Int,
        val reason: String,
        val delay: String,
    ) : CredentialSignal
}

internal object CredentialWaitLogPolicy {
    /** Стабильный ключ журнала: обновления сливаются в одну строку. */
    const val STATUS_KEY: String = "cred_wait_status"

    private const val MAX_REASON = 160
    private val REQUEST = Regex("""^\[КРЕД #(\d+)] Запрос""")
    private val ACQUIRED = Regex("""^\[КРЕД #(\d+)] OK, TURN""")
    private val RETRY = Regex("""^\[КРЕД #(\d+)] Ошибка: (.*)\. Повторяем через (.+?)\.*$""")
    private val INVALIDATED = Regex("""^\[КРЕД #(\d+)] Невалиден""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9+/_\-]{32,}""")
    private val RUST_DURATION = Regex("""^(\d+(?:\.\d+)?)(ms|s|m)$""")

    fun parse(line: String): CredentialSignal? {
        REQUEST.find(line)?.let {
            return CredentialSignal.Requested(it.groupValues[1].toIntOrNull() ?: return null)
        }
        INVALIDATED.find(line)?.let {
            return CredentialSignal.Requested(it.groupValues[1].toIntOrNull() ?: return null)
        }
        ACQUIRED.find(line)?.let {
            return CredentialSignal.Acquired(it.groupValues[1].toIntOrNull() ?: return null)
        }
        RETRY.find(line)?.let { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return null
            return CredentialSignal.Retry(
                credentialId = id,
                reason = sanitizeReason(match.groupValues[2]),
                delay = formatDelay(match.groupValues[3]),
            )
        }
        return null
    }

    /**
     * Причина отказа идёт в отчёт: убираем переводы строк, длинные токены
     * (URL/подписи) и ограничиваем длину, чтобы одна запись не вытесняла
     * остальной журнал.
     */
    fun sanitizeReason(raw: String): String {
        val compact = raw
            .replace(Regex("\\s+"), " ")
            .replace(LONG_TOKEN, "…")
            .trim()
            .trimEnd('.', '!', ' ')
        return if (compact.length <= MAX_REASON) compact else compact.take(MAX_REASON - 1) + "…"
    }

    /** `30s` / `1.5s` / `250ms` из Rust → `30 с` / `1.5 с` / `0.25 с`. */
    fun formatDelay(raw: String): String {
        val value = raw.trim().trimEnd('.')
        val match = RUST_DURATION.matchEntire(value) ?: return value
        val number = match.groupValues[1].toDoubleOrNull() ?: return value
        val seconds = when (match.groupValues[2]) {
            "ms" -> number / 1000.0
            "m" -> number * 60.0
            else -> number
        }
        val text = if (seconds >= 1.0 && seconds % 1.0 == 0.0) {
            seconds.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", seconds).trimEnd('0').trimEnd('.')
        }
        return "$text с"
    }

    fun waitingMessage(waiting: Int, total: Int, last: CredentialSignal.Retry?): String {
        val verb = if (waiting == 1) "ждёт" else "ждут"
        val base = "[КРЕД] $waiting из $total групп $verb TURN-креды"
        if (last == null) return base
        return "$base · последний отказ: ${last.reason} · пауза ${last.delay}"
    }

    fun allAcquiredMessage(total: Int): String =
        "[КРЕД] Все группы получили TURN-креды ($total) ✓"

    /** Групп в цели: воркеры клиента делятся по `WORKERS_PER_GROUP` на одну когорту. */
    fun groupsForTarget(workers: Int): Int =
        (workers.coerceAtLeast(1) + CsqttConstants.Tunnel.WORKERS_PER_GROUP - 1) /
            CsqttConstants.Tunnel.WORKERS_PER_GROUP
}

/**
 * Состояние ожидания креденшелов одного процесса. `apply` возвращает только
 * изменившуюся строку журнала, поэтому повторы одного и того же отказа не
 * плодят записи.
 */
internal class CredentialWaitTracker {
    private val waiting = LinkedHashMap<Int, CredentialSignal.Retry>()
    private var lastRetry: CredentialSignal.Retry? = null
    private var lastMessage: String? = null

    fun apply(signal: CredentialSignal, totalGroups: Int): String? {
        val total = totalGroups.coerceAtLeast(1)
        return when (signal) {
            is CredentialSignal.Requested -> null
            is CredentialSignal.Acquired -> {
                waiting.remove(signal.credentialId)
                when {
                    waiting.isEmpty() && lastRetry != null ->
                        publish(CredentialWaitLogPolicy.allAcquiredMessage(total))
                    waiting.isNotEmpty() ->
                        publish(CredentialWaitLogPolicy.waitingMessage(waiting.size, total, lastRetry))
                    else -> null
                }
            }
            is CredentialSignal.Retry -> {
                waiting[signal.credentialId] = signal
                lastRetry = signal
                publish(CredentialWaitLogPolicy.waitingMessage(waiting.size, total, signal))
            }
        }
    }

    private fun publish(message: String): String? =
        if (message == lastMessage) null else message.also { lastMessage = it }
}
