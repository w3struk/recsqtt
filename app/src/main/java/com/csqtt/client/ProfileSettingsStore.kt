package com.csqtt.client

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private fun <T> Flow<T>.onIo(): Flow<T> = flowOn(Dispatchers.IO)

/**
 * Профильные настройки: ключи, flow'ы, кэши, креды и мутации одного профиля.
 *
 * Класс получает **тот же** `DataStore<Preferences>`, что и [SettingsStore]:
 * второй DataStore на файл `settings` создавать нельзя. `Context` нужен только
 * для шифрованного хранилища секретов и реестра звонков.
 *
 * Реестр профилей — `profile_ids` (упорядоченный список ID строкой) и
 * монотонный `next_profile_id`. Реестр может быть пустым: пустая строка
 * `profile_ids` — это мигрированное пустое состояние, а отсутствие ключа —
 * ещё не мигрированное, и тогда ID обнаруживаются по непустым ссылкам.
 * Схема реестра — `profile_registry_schema`; её отсутствие читается как v1,
 * целевая версия — 2.
 *
 * Все сеттеры принимают явный `profile: Int` и игнорируют ID, которого нет в
 * реестре: запись после удаления не должна воскрешать ключи.
 */
class ProfileSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    context: Context,
) {
    companion object {
        private const val TAG = "ProfileSettingsStore"

        private const val REGISTRY_SCHEMA_V2 = 2

        private val ACTIVE_PROFILE = intPreferencesKey("active_profile")
        private val PROFILE_IDS = stringPreferencesKey("profile_ids")
        private val NEXT_PROFILE_ID = intPreferencesKey("next_profile_id")
        private val PROFILE_REGISTRY_SCHEMA = intPreferencesKey("profile_registry_schema")

        private val PROFILE_NAME = stringPreferencesKey("profile_name")
        private val CSQTT_LINK = stringPreferencesKey("csqtt_link")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val SNI = stringPreferencesKey("sni")
        private val CUSTOM_TURN_IPS = stringPreferencesKey("custom_turn_ips")
        private val SELECTED_TURN_SUBNET = stringPreferencesKey("selected_turn_subnet")
        private val TURN_SUBNET_IP_LIMIT = stringPreferencesKey("turn_subnet_ip_limit")

        private val VK_AUTH_MODE = stringPreferencesKey("vk_auth_mode")
        private val OBFS_MODE = stringPreferencesKey("obfs_mode")
        private val TURN_TRANSPORT = stringPreferencesKey("turn_transport")
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode")
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method")
        private val CAPTCHA_WBV_SOLVE_METHOD = stringPreferencesKey("captcha_wbv_solve_method")

        private val VK_HASH_MODE = stringPreferencesKey("vk_hash_mode")
        private val VK_ACCESS_TOKEN = stringPreferencesKey("vk_access_token")
        private val VK_ACCESS_TOKEN_ENCRYPTED = stringPreferencesKey("vk_access_token_encrypted")
        private val VK_ACCESS_TOKEN_USER_ID = stringPreferencesKey("vk_access_token_user_id")

        private val SELECTED_FINGERPRINT = stringPreferencesKey("selected_fingerprint")
        private val ACTIVE_CLIENT_IDS = stringPreferencesKey("active_client_ids")
        private val VK_HASH_CHECK_RESULTS = stringPreferencesKey("vk_hash_check_results")

        /** Все профильные ключи, которые `deleteProfile` вычищает для удалённого ID. */
        private val PROFILE_STRING_KEYS = listOf(
            PROFILE_NAME,
            CSQTT_LINK,
            VK_HASHES,
            VK_HASH_MODE,
            VK_AUTH_MODE,
            CAPTCHA_MODE,
            CAPTCHA_SOLVE_METHOD,
            CAPTCHA_WBV_SOLVE_METHOD,
            VK_HASH_CHECK_RESULTS,
            PROTOCOL,
            SNI,
            OBFS_MODE,
            TURN_TRANSPORT,
            SELECTED_TURN_SUBNET,
            CUSTOM_TURN_IPS,
            TURN_SUBNET_IP_LIMIT,
            SELECTED_FINGERPRINT,
            ACTIVE_CLIENT_IDS,
            VK_ACCESS_TOKEN,
            VK_ACCESS_TOKEN_ENCRYPTED,
            VK_ACCESS_TOKEN_USER_ID,
        )
        private val PROFILE_INT_KEYS = listOf(WORKERS_PER_HASH, LISTEN_PORT)

        @Volatile
        internal var cachedVkHashMode: String? = null

        @Volatile
        internal var cachedVkAccessToken: String? = null

        @Volatile
        internal var cachedObfsMode: String? = null

        @Volatile
        internal var cachedTurnTransport: String? = null

        @Volatile
        internal var cachedWorkersPerHash: Int? = null

        @Volatile
        internal var cachedCustomTurnIps: String? = null

        private val uiCacheStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        private val migrationsStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun <T> getProfileKey(baseKey: Preferences.Key<T>, profile: Int): Preferences.Key<T> {
            if (profile == 0) return baseKey
            val newName = "${baseKey.name}_$profile"
            @Suppress("UNCHECKED_CAST")
            return when (baseKey) {
                VK_HASHES, PROTOCOL, SNI, VK_AUTH_MODE, OBFS_MODE, TURN_TRANSPORT, CAPTCHA_MODE, CAPTCHA_SOLVE_METHOD, CAPTCHA_WBV_SOLVE_METHOD, CSQTT_LINK, SELECTED_FINGERPRINT, ACTIVE_CLIENT_IDS, VK_HASH_MODE, VK_ACCESS_TOKEN, VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN_USER_ID, VK_HASH_CHECK_RESULTS, CUSTOM_TURN_IPS, SELECTED_TURN_SUBNET -> stringPreferencesKey(newName) as Preferences.Key<T>
                WORKERS_PER_HASH, LISTEN_PORT -> intPreferencesKey(newName) as Preferences.Key<T>
                else -> stringPreferencesKey(newName) as Preferences.Key<T>
            }
        }
    }

    private val secureStore = SecureStringStore(context)
    private val vkCalls = VkCallStore(context)

    // --- Реестр профилей: чистые helpers над одним снимком Preferences ---

    /**
     * Список профилей из `profile_ids`. Пустая строка — мигрированный пустой
     * реестр; отсутствие ключа — обнаружение по непустым ссылкам. Чистая
     * функция: не открывает транзакций и не читает поток.
     */
    internal fun profileIdsOf(prefs: Preferences): List<Int> {
        ProfileRegistryPolicy.parseIds(prefs[PROFILE_IDS])?.let { return it }
        return ProfileRegistryPolicy.discover(
            active = null,
            linked = linkedIdsOf(prefs),
        )
    }

    /** Активный профиль, разрешённый относительно реестра и наличия ссылок. */
    internal fun activeProfileOf(prefs: Preferences): Int = ProfileRegistryPolicy.resolveActive(
        ids = profileIdsOf(prefs),
        requested = prefs[ACTIVE_PROFILE] ?: ProfileRegistryPolicy.NO_PROFILE,
        linked = linkedIdsOf(prefs),
    )

    /** Следующий свободный ID: исправляет отставший счётчик и не переиспользует удалённые. */
    internal fun nextProfileIdOf(prefs: Preferences): Int = ProfileRegistryPolicy.nextId(
        ids = profileIdsOf(prefs),
        storedNext = prefs[NEXT_PROFILE_ID],
    )

    private fun linkedIdsOf(prefs: Preferences): Set<Int> = ProfileRegistryPolicy.linkedProfileIds(
        stringPrefs = stringSnapshot(prefs),
        linkKeyName = CSQTT_LINK.name,
    )

    private fun stringSnapshot(prefs: Preferences): Map<String, String> = buildMap {
        for ((key, value) in prefs.asMap()) {
            if (value is String) put(key.name, value)
        }
    }

    /**
     * ID профилей, у которых в хранилище остались любые профильные ключи:
     * базовое имя — профиль 0, `<имя>_<N>` — профиль N. Нужен миграции, чтобы
     * вычистить осиротевшие ключи и при отсутствии `profile_ids`: иначе новый
     * профиль с переиспользованным ID унаследует старый токен и настройки.
     */
    private fun profileIdsInStorage(prefs: Preferences): Set<Int> {
        val baseNames = PROFILE_STRING_KEYS.map { it.name }.toSet() +
            PROFILE_INT_KEYS.map { it.name }
        val ids = LinkedHashSet<Int>()
        for (name in prefs.asMap().keys.map { it.name }) {
            if (name in baseNames) {
                ids += 0
                continue
            }
            for (base in baseNames) {
                val prefix = "${base}_"
                if (!name.startsWith(prefix)) continue
                val id = name.substring(prefix.length).toIntOrNull() ?: continue
                if (id >= 0) ids += id
                break
            }
        }
        return ids
    }

    // --- Flow'ы: профиль всегда берётся из того же снимка prefs ---

    val activeProfile: Flow<Int> = dataStore.data
        .map { activeProfileOf(it) }
        .distinctUntilChanged()

    val profileIds: Flow<List<Int>> = dataStore.data
        .map { profileIdsOf(it) }
        .distinctUntilChanged()

    val profiles: Flow<List<ProfileSummary>> = dataStore.data.map { prefs ->
        profileIdsOf(prefs).map { id -> summaryOf(prefs, id) }
    }

    internal val tunnelAuthSnapshot: Flow<TunnelAuthSnapshot> = dataStore.data.map { prefs ->
        val profile = activeProfileOf(prefs)
        TunnelAuthSnapshot(
            profile = profile,
            csqttLink = prefs[getProfileKey(CSQTT_LINK, profile)] ?: "",
        )
    }.onIo()

    val csqttLink: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(CSQTT_LINK, activeProfileOf(prefs))] ?: ""
    }
    val vkHashes: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(VK_HASHES, activeProfileOf(prefs))] ?: ""
    }
    val workersPerHash: Flow<Int> = dataStore.data.map { prefs ->
        normalizedWorkers(prefs[getProfileKey(WORKERS_PER_HASH, activeProfileOf(prefs))])
    }
    val protocol: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(PROTOCOL, activeProfileOf(prefs))] ?: "udp"
    }
    val listenPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[getProfileKey(LISTEN_PORT, activeProfileOf(prefs))] ?: 0
    }
    val sni: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(SNI, activeProfileOf(prefs))] ?: ""
    }
    val vkAuthMode: Flow<String> = dataStore.data.map { prefs ->
        normalizedAuthMode(prefs[getProfileKey(VK_AUTH_MODE, activeProfileOf(prefs))])
    }
    val obfsMode: Flow<String> = dataStore.data.map { prefs ->
        normalizedObfsMode(prefs[getProfileKey(OBFS_MODE, activeProfileOf(prefs))])
    }
    val turnTransport: Flow<String> = dataStore.data.map { prefs ->
        normalizedTurnTransport(prefs[getProfileKey(TURN_TRANSPORT, activeProfileOf(prefs))])
    }
    val customTurnIps: Flow<String> = dataStore.data.map { prefs ->
        customTurnIpsOf(prefs, activeProfileOf(prefs))
    }
    val selectedTurnSubnet: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(SELECTED_TURN_SUBNET, activeProfileOf(prefs))] ?: ""
    }
    val turnSubnetIpLimit: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, activeProfileOf(prefs))] ?: "ALL"
    }
    val captchaMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(CAPTCHA_MODE, activeProfileOf(prefs))] ?: "auto"
    }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, activeProfileOf(prefs))] ?: "auto"
    }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, activeProfileOf(prefs))] ?: "auto"
    }
    val vkHashMode: Flow<String> = dataStore.data.map { prefs ->
        normalizedHashMode(prefs[getProfileKey(VK_HASH_MODE, activeProfileOf(prefs))])
    }
    val vkAccessToken: Flow<String> = dataStore.data.map { prefs ->
        readSecret(
            prefs,
            VK_ACCESS_TOKEN_ENCRYPTED,
            VK_ACCESS_TOKEN,
            activeProfileOf(prefs),
        )
    }.onIo()
    val vkAccessTokenUserId: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(VK_ACCESS_TOKEN_USER_ID, activeProfileOf(prefs))] ?: ""
    }
    val selectedFingerprint: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(SELECTED_FINGERPRINT, activeProfileOf(prefs))]
            ?: CsqttConstants.Tunnel.DEFAULT_FINGERPRINT
    }
    val activeClientIds: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(ACTIVE_CLIENT_IDS, activeProfileOf(prefs))]
            ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS
    }
    val vkHashCheckResults: Flow<String> = dataStore.data.map { prefs ->
        prefs[getProfileKey(VK_HASH_CHECK_RESULTS, activeProfileOf(prefs))] ?: "{}"
    }

    // --- Снимковые читатели ---

    suspend fun activeProfileId(): Int = activeProfileOf(dataStore.data.first())

    suspend fun profileName(profile: Int): String =
        profileNameOf(dataStore.data.first(), profile)

    /**
     * Черновик существующего профиля; `null`, если ID нет в реестре.
     * Токен в черновик не попадает — только признак `hasVkToken`.
     */
    suspend fun draft(profile: Int): ProfileDraft? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        if (profile !in profileIdsOf(prefs)) return@withContext null
        draftOf(prefs, profile)
    }

    /** Черновик нового профиля с дефолтами приложения и ещё не занятым именем. */
    suspend fun newDraft(): ProfileDraft {
        val prefs = dataStore.data.first()
        val id = nextProfileIdOf(prefs)
        return ProfileDraft(id = id, name = ProfileEditorPolicy.defaultName(id))
    }

    /**
     * Параметры запуска/переподключения для **явного** профиля сессии. Никогда
     * не разрешает активный профиль сам. `null`, если профиля больше нет.
     */
    internal suspend fun prepareSnapshot(profile: Int): ProfilePrepareSnapshot? {
        val prefs = dataStore.data.first()
        if (profile !in profileIdsOf(prefs)) return null
        return ProfilePrepareSnapshot(
            profile = profile,
            sni = prefs[getProfileKey(SNI, profile)] ?: "",
            obfsMode = normalizedObfsMode(prefs[getProfileKey(OBFS_MODE, profile)]),
            turnTransport = normalizedTurnTransport(prefs[getProfileKey(TURN_TRANSPORT, profile)]),
            customTurnIps = customTurnIpsOf(prefs, profile),
            captchaMode = prefs[getProfileKey(CAPTCHA_MODE, profile)] ?: "auto",
            captchaSolveMethod = prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] ?: "auto",
            captchaWbvSolveMethod = prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] ?: "auto",
            fingerprint = prefs[getProfileKey(SELECTED_FINGERPRINT, profile)]
                ?: CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
            clientIds = prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)]
                ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
            workersPerHash = normalizedWorkers(prefs[getProfileKey(WORKERS_PER_HASH, profile)]),
            vkHashMode = normalizedHashMode(prefs[getProfileKey(VK_HASH_MODE, profile)]),
            vkAuthMode = normalizedAuthMode(prefs[getProfileKey(VK_AUTH_MODE, profile)]),
        )
    }

    internal suspend fun vkAccessToken(profile: Int): String = withContext(Dispatchers.IO) {
        readSecret(
            dataStore.data.first(),
            VK_ACCESS_TOKEN_ENCRYPTED,
            VK_ACCESS_TOKEN,
            profile,
        )
    }

    internal suspend fun vkAccessTokenUserId(profile: Int): String =
        dataStore.data.first()[getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile)] ?: ""

    /**
     * Ссылка и хеши явного профиля сессии. `null`, если профиля больше нет в
     * реестре: читать его настройки тогда бессмысленно.
     */
    internal suspend fun connectionSnapshot(profile: Int): ProfileConnectionSnapshot? {
        val prefs = dataStore.data.first()
        if (profile !in profileIdsOf(prefs)) return null
        return ProfileConnectionSnapshot(
            link = prefs[getProfileKey(CSQTT_LINK, profile)] ?: "",
            vkHashes = prefs[getProfileKey(VK_HASHES, profile)] ?: "",
            vkHashCheckResults = prefs[getProfileKey(VK_HASH_CHECK_RESULTS, profile)] ?: "{}",
        )
    }

    // --- Мутации ---

    /**
     * Открывает `dataStore.edit` и вызывает [block] только если профиль ещё в
     * реестре. Возвращает `true`, если запись действительно состоялась.
     */
    private suspend inline fun editForProfile(
        profile: Int,
        crossinline block: (MutablePreferences) -> Unit,
    ): Boolean {
        var applied = false
        dataStore.edit { prefs ->
            if (profile !in profileIdsOf(prefs)) return@edit
            block(prefs)
            applied = true
        }
        return applied
    }

    suspend fun saveProfileName(profile: Int, name: String) {
        editForProfile(profile) { prefs ->
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == ProfileEditorPolicy.defaultName(profile)) {
                prefs.remove(getProfileKey(PROFILE_NAME, profile))
            } else {
                prefs[getProfileKey(PROFILE_NAME, profile)] = trimmed
            }
        }
    }

    suspend fun saveFingerprint(profile: Int, fingerprint: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] = fingerprint
        }
    }

    suspend fun saveActiveClientIds(profile: Int, clientIds: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)] = clientIds
        }
    }

    suspend fun saveVkHashCheckResults(profile: Int, resultsJson: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(VK_HASH_CHECK_RESULTS, profile)] = resultsJson
        }
    }

    suspend fun invalidateVkHash(profile: Int, hash: String) {
        val normalized = hash.trim()
        if (normalized.isEmpty()) return
        editForProfile(profile) { prefs ->
            val key = getProfileKey(VK_HASH_CHECK_RESULTS, profile)
            val results = VkHashValidationCodec.decode(prefs[key] ?: "{}") +
                (normalized to VkHashValidationStatus.Invalid)
            prefs[key] = VkHashValidationCodec.encode(results)
        }
    }

    suspend fun saveCsqttParticipantLink(profile: Int, link: String) {
        val trimmed = link.trim()
        // Убрать ссылку можно только удалением профиля; пустая запись отвергается.
        if (trimmed.isEmpty()) return
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(CSQTT_LINK, profile)] = trimmed
        }
    }

    /**
     * Импорт ссылки в явный профиль: `token`/`user_id` уходят в хранилище
     * (токен — шифрованно), а ссылка переписывается без них. Ссылка без `token`
     * очищает сохранённые креды — это осознанный импорт, а не правка поля.
     * Возвращает `false` для невалидной ссылки, ничего не записывая.
     */
    suspend fun importCsqttLink(profile: Int, raw: String): Boolean {
        val decision = VkLinkImportPolicy.decide(raw) ?: return false
        return editForProfile(profile) { prefs ->
            applyLinkDecision(prefs, decision, profile)
        }
    }

    suspend fun saveWorkersPerHash(profile: Int, workersPerHash: Int) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
        }
    }

    suspend fun saveParticipantTunnelSettings(profile: Int, vkHashes: String, workersPerHash: Int) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(VK_HASHES, profile)] = vkHashes
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
        }
    }

    suspend fun saveVkAuthMode(profile: Int, mode: String) {
        editForProfile(profile) { prefs ->
            val normalized = normalizeVkModeSelection(
                requestedAuthMode = mode,
                requestedHashMode = null,
                hasVkToken = false,
            ).authMode
            prefs[getProfileKey(VK_AUTH_MODE, profile)] = normalized
            prefs[getProfileKey(VK_HASH_MODE, profile)] = vkHashModeForAuthMode(
                requestedAuthMode = normalized,
                currentHashMode = prefs[getProfileKey(VK_HASH_MODE, profile)],
                hasVkToken = readSecret(
                    prefs,
                    VK_ACCESS_TOKEN_ENCRYPTED,
                    VK_ACCESS_TOKEN,
                    profile,
                ).isNotBlank(),
            )
        }
    }

    suspend fun saveVkHashMode(profile: Int, mode: String) {
        editForProfile(profile) { prefs ->
            val currentAuthMode = prefs[getProfileKey(VK_AUTH_MODE, profile)]
            val nextAuthMode = vkAuthModeForHashMode(mode, currentAuthMode)
            val selection = normalizeVkModeSelection(
                requestedAuthMode = nextAuthMode,
                requestedHashMode = mode,
                hasVkToken = readSecret(
                    prefs,
                    VK_ACCESS_TOKEN_ENCRYPTED,
                    VK_ACCESS_TOKEN,
                    profile,
                ).isNotBlank(),
            )
            prefs[getProfileKey(VK_AUTH_MODE, profile)] = selection.authMode
            prefs[getProfileKey(VK_HASH_MODE, profile)] = selection.hashMode
        }
    }

    suspend fun saveObfsMode(profile: Int, mode: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(OBFS_MODE, profile)] = mode
        }
    }

    suspend fun saveTurnTransport(profile: Int, transport: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(TURN_TRANSPORT, profile)] = when (transport) {
                CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
                else -> CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
            }
        }
    }

    suspend fun saveSelectedTurnSubnet(profile: Int, subnetId: String, ipLimit: String? = null) {
        editForProfile(profile) { prefs ->
            val limit = ipLimit ?: prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] ?: "ALL"
            prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] = subnetId
            prefs[getProfileKey(CUSTOM_TURN_IPS, profile)] = computeCustomTurnIps(subnetId, limit)
            if (ipLimit != null) {
                prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] = ipLimit
            }
        }
    }

    suspend fun saveTurnSubnetIpLimit(profile: Int, limit: String) {
        editForProfile(profile) { prefs ->
            val subnetId = prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: ""
            prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] = limit
            prefs[getProfileKey(CUSTOM_TURN_IPS, profile)] = computeCustomTurnIps(subnetId, limit)
        }
    }

    suspend fun saveCaptchaMode(profile: Int, mode: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(CAPTCHA_MODE, profile)] = mode
        }
    }

    suspend fun saveCaptchaSolveMethod(profile: Int, method: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(profile: Int, method: String) {
        editForProfile(profile) { prefs ->
            prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] = method
            if (prefs[getProfileKey(CAPTCHA_MODE, profile)] == "wv") {
                prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
            }
        }
    }

    suspend fun clearVkAccessToken(profile: Int) {
        editForProfile(profile) { prefs ->
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_ENCRYPTED, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile))
        }
    }

    /**
     * Пишет активный профиль только если он есть в реестре. Правило «не
     * активировать профиль без ссылки» держит лист профилей, не store.
     */
    suspend fun saveActiveProfile(profile: Int) {
        dataStore.edit { prefs ->
            if (profile in profileIdsOf(prefs)) prefs[ACTIVE_PROFILE] = profile
        }
    }

    /**
     * Внешний импорт без открытого редактора (design §4.5).
     *
     * Пустой реестр получает новый профиль: ID берётся из `nextProfileIdOf`
     * (0 на чистой установке, монотонный счётчик после удаления всех), ID
     * дописывается в `profile_ids`, ссылка/креды/режимы пишутся вместе с
     * `active_profile` в одной транзакции.
     *
     * Если реестр не пуст, импорт возможен только в свободный профиль 0;
     * заполненный 0 или реестр без 0 возвращают `false` — вызывающий открывает
     * редактор нового профиля. Возвращает `false` и для невалидной ссылки.
     */
    suspend fun importExternalLinkIntoStartProfile(raw: String): Boolean {
        val decision = VkLinkImportPolicy.decide(raw) ?: return false
        var written = false
        var allocatedId: Int? = null
        dataStore.edit { prefs ->
            val ids = profileIdsOf(prefs)
            if (ids.isEmpty()) {
                val id = nextProfileIdOf(prefs)
                // Переиспользованный ID не должен унаследовать старые ключи и токен.
                // Чистим до записи ссылки, иначе удалим только что импортированное.
                removeProfileKeys(prefs, id)
                prefs[PROFILE_IDS] = ProfileRegistryPolicy.formatIds(ids + id)
                prefs[NEXT_PROFILE_ID] = id + 1
                applyLinkDecision(prefs, decision, id)
                applyImportedLinkModes(prefs, id, decision)
                prefs[ACTIVE_PROFILE] = id
                allocatedId = id
                written = true
                return@edit
            }
            val startId = ProfileRegistryPolicy.DEFAULT_PROFILE_ID
            if (startId !in ids) return@edit
            val currentLink = prefs[getProfileKey(CSQTT_LINK, startId)] ?: ""
            if (currentLink.isNotBlank()) return@edit
            applyLinkDecision(prefs, decision, startId)
            applyImportedLinkModes(prefs, startId, decision)
            prefs[ACTIVE_PROFILE] = startId
            written = true
        }
        allocatedId?.let { id ->
            runCatching { vkCalls.clear(id) }
                .onFailure {
                    Log.w(TAG, "Не удалось очистить реестр звонков профиля $id", it)
                }
        }
        return written
    }

    /**
     * Создаёт профиль из черновика и делает его активным в одной транзакции.
     * Принимает только валидную ссылку; пишет **очищенную** ссылку без `token=`.
     * Возвращает новый ID или `null`, если ссылка невалидна.
     *
     * Приоритет кредов: токен из ссылки, иначе [imported] — вызывающий передаёт
     * его только пока видимая ссылка совпадает с префиллом импорта.
     */
    internal suspend fun addProfile(
        draft: ProfileDraft,
        imported: ImportedLinkCredentials? = null,
    ): Int? {
        val decision = VkLinkImportPolicy.decide(draft.link) ?: return null
        var newId: Int? = null
        dataStore.edit { prefs ->
            val ids = profileIdsOf(prefs)
            val id = nextProfileIdOf(prefs)
            // ID может быть переиспользован после удаления: старые ключи, включая
            // сохранённый токен, обязаны исчезнуть до записи нового профиля.
            removeProfileKeys(prefs, id)
            prefs[PROFILE_IDS] = ProfileRegistryPolicy.formatIds(ids + id)
            prefs[NEXT_PROFILE_ID] = id + 1
            prefs[ACTIVE_PROFILE] = id
            val hasToken = decision.token.isNotEmpty() ||
                !imported?.token.isNullOrEmpty()
            writeProfileFields(prefs, id, draft, decision, hasToken)
            writeImportedCredentials(
                prefs,
                id,
                decision,
                imported,
                clearCredentialsWhenAbsent = true,
            )
            newId = id
        }
        newId?.let { id ->
            runCatching { vkCalls.clear(id) }
                .onFailure {
                    Log.w(TAG, "Не удалось очистить реестр звонков профиля $id", it)
                }
        }
        return newId
    }

    /**
     * Сохраняет черновик существующего профиля. Пустая или неразбираемая
     * ссылка отвергается; `active_profile` не трогается; креды без токена в
     * ссылке не очищаются (токен — не поле формы).
     */
    internal suspend fun saveProfileDraft(
        profile: Int,
        draft: ProfileDraft,
        imported: ImportedLinkCredentials? = null,
    ): Boolean {
        val decision = VkLinkImportPolicy.decide(draft.link) ?: return false
        return editForProfile(profile) { prefs ->
            val hasToken = decision.token.isNotEmpty() ||
                !imported?.token.isNullOrEmpty() ||
                readSecret(
                    prefs,
                    VK_ACCESS_TOKEN_ENCRYPTED,
                    VK_ACCESS_TOKEN,
                    profile,
                ).isNotBlank()
            writeProfileFields(prefs, profile, draft, decision, hasToken)
            writeImportedCredentials(prefs, profile, decision, imported)
        }
    }

    /**
     * Удаляет профиль, включая последний. В одной транзакции убирает ID из
     * реестра (пустой остаток записывается пустой строкой), а если удалён
     * активный — выбирает замену среди оставшихся (сначала со ссылкой). Для
     * пустого остатка `active_profile` становится [ProfileRegistryPolicy.NO_PROFILE].
     * `next_profile_id` не уменьшается, ID не переиспользуется. Ключи и реестр
     * звонков чистятся best-effort после.
     */
    suspend fun deleteProfile(profile: Int): Boolean {
        var deleted = false
        dataStore.edit { prefs ->
            val ids = profileIdsOf(prefs)
            if (!ProfileRegistryPolicy.canDelete(ids, profile)) return@edit
            val remaining = ids.filterNot { it == profile }
            val active = activeProfileOf(prefs)
            val storedActive = prefs[ACTIVE_PROFILE] ?: ProfileRegistryPolicy.DEFAULT_PROFILE_ID
            val linked = linkedIdsOf(prefs) intersect remaining.toSet()
            val fallback = ProfileRegistryPolicy.activeAfterDelete(
                ids = ids,
                deleted = profile,
                active = active,
                linked = linked,
            )
            prefs[PROFILE_IDS] = ProfileRegistryPolicy.formatIds(remaining)
            if (active == profile || storedActive !in remaining) {
                prefs[ACTIVE_PROFILE] = fallback
            }
            val storedNext = prefs[NEXT_PROFILE_ID]
            if (storedNext == null || storedNext <= profile) {
                prefs[NEXT_PROFILE_ID] = profile + 1
            }
            deleted = true
        }
        if (deleted) {
            clearProfileKeys(profile)
            runCatching { vkCalls.clear(profile) }
                .onFailure { Log.w(TAG, "Не удалось очистить реестр звонков профиля $profile", it) }
        }
        return deleted
    }

    /**
     * Одноразовая миграция реестра в схему v2. Запускается, пока
     * `profile_registry_schema` меньше 2, и делает одну запись: `profile_ids`
     * (пустая строка для пустого реестра, ключ не удаляется), `active_profile`,
     * `next_profile_id` и `schema = 2`. Новый список — ровно профили со
     * ссылкой.
     *
     * Осиротевшие профильные ключи ищутся в самом хранилище, а не только в
     * `profile_ids`: при отсутствии списка старые ключи иначе пережили бы
     * миграцию и достались новому профилю с переиспользованным ID. Ключи
     * выпавших ID удаляются в той же транзакции, что и переключение реестра.
     * Реестр звонков чистится после, с перечитыванием списка: ID, успевший
     * пересоздаться, не должен потерять свои звонки.
     *
     * Идемпотентна: guard проверяется внутри транзакции, поэтому два экземпляра
     * store не разъедутся.
     */
    suspend fun migrateRegistryIfNeeded() {
        var dropped: List<Int> = emptyList()
        try {
            dataStore.edit { prefs ->
                val schema = prefs[PROFILE_REGISTRY_SCHEMA] ?: 1
                if (schema >= REGISTRY_SCHEMA_V2) return@edit
                val storedIds = (
                    ProfileRegistryPolicy.parseIds(prefs[PROFILE_IDS]).orEmpty() +
                        profileIdsInStorage(prefs)
                    ).distinct()
                val migration = ProfileRegistryPolicy.migrateRegistry(
                    storedIds = storedIds,
                    linked = linkedIdsOf(prefs),
                    requestedActive = prefs[ACTIVE_PROFILE] ?: ProfileRegistryPolicy.NO_PROFILE,
                    storedNext = prefs[NEXT_PROFILE_ID],
                )
                prefs[PROFILE_IDS] = ProfileRegistryPolicy.formatIds(migration.ids)
                prefs[ACTIVE_PROFILE] = migration.active
                prefs[NEXT_PROFILE_ID] = migration.next
                prefs[PROFILE_REGISTRY_SCHEMA] = REGISTRY_SCHEMA_V2
                for (profile in migration.dropped) removeProfileKeys(prefs, profile)
                dropped = migration.dropped
            }
        } catch (error: IOException) {
            Log.w(TAG, "Не удалось мигрировать реестр профилей", error)
            return
        }
        if (dropped.isEmpty()) return
        val current = try {
            profileIdsOf(dataStore.data.first())
        } catch (error: IOException) {
            Log.w(TAG, "Не удалось перечитать реестр профилей после миграции", error)
            return
        }
        for (profile in dropped) {
            if (profile in current) continue
            runCatching { vkCalls.clear(profile) }
                .onFailure {
                    Log.w(TAG, "Не удалось очистить реестр звонков профиля $profile", it)
                }
        }
    }

    /**
     * Одноразовая миграция ссылки, сохранённой до разделения кредов, по всем
     * профилям реестра. Идемпотентна: после перезаписи токена в ссылке нет, и
     * политика возвращает no-op. Ошибка ввода-вывода только логируется.
     */
    suspend fun migrateLinkCredentialsIfNeeded() {
        try {
            dataStore.edit { prefs ->
                for (profile in profileIdsOf(prefs)) {
                    val stored = prefs[getProfileKey(CSQTT_LINK, profile)] ?: continue
                    val decision = VkLinkImportPolicy.migration(stored) ?: continue
                    applyLinkDecision(prefs, decision, profile)
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Не удалось мигрировать креды из ссылки", error)
        }
    }

    // --- Запись полей черновика и кредов ---

    private fun writeProfileFields(
        prefs: MutablePreferences,
        profile: Int,
        draft: ProfileDraft,
        decision: VkLinkImportPolicy.Decision,
        hasVkToken: Boolean,
    ) {
        val trimmedName = draft.name.trim()
        if (trimmedName.isEmpty() || trimmedName == ProfileEditorPolicy.defaultName(profile)) {
            prefs.remove(getProfileKey(PROFILE_NAME, profile))
        } else {
            prefs[getProfileKey(PROFILE_NAME, profile)] = trimmedName
        }
        prefs[getProfileKey(CSQTT_LINK, profile)] = decision.sanitizedLink
        prefs[getProfileKey(VK_HASHES, profile)] = draft.vkHashes
        val selection = normalizeVkModeSelection(
            requestedAuthMode = draft.vkAuthMode,
            requestedHashMode = draft.vkHashMode,
            hasVkToken = hasVkToken,
        )
        prefs[getProfileKey(VK_AUTH_MODE, profile)] = selection.authMode
        prefs[getProfileKey(VK_HASH_MODE, profile)] = selection.hashMode
        prefs[getProfileKey(CAPTCHA_MODE, profile)] = draft.captchaMode
        prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = draft.captchaSolveMethod
        prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] = draft.captchaWbvSolveMethod
        prefs[getProfileKey(TURN_TRANSPORT, profile)] = when (draft.turnTransport) {
            CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
            else -> CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
        }
        prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] = draft.fingerprint
        prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] = draft.selectedTurnSubnet
        prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] = draft.turnSubnetIpLimit
        prefs[getProfileKey(CUSTOM_TURN_IPS, profile)] =
            computeCustomTurnIps(draft.selectedTurnSubnet, draft.turnSubnetIpLimit)
        prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
            WorkerCountPolicy.normalize(draft.workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
    }

    /**
     * Креды из ссылки важнее импортированных.
     *
     * [clearCredentialsWhenAbsent] различает создание профиля и правку
     * существующего: новый профиль (`addProfile`) не должен унаследовать чужой
     * токен, поэтому при отсутствии токена в обоих источниках креды стираются.
     * `saveProfileDraft` оставляет значение по умолчанию: правка ссылки без
     * `token=` не стирает сохранённый токен — это явное правило продукта.
     */
    private fun writeImportedCredentials(
        prefs: MutablePreferences,
        profile: Int,
        decision: VkLinkImportPolicy.Decision,
        imported: ImportedLinkCredentials?,
        clearCredentialsWhenAbsent: Boolean = false,
    ) {
        when {
            decision.token.isNotEmpty() -> putCredentials(prefs, profile, decision.token, decision.userId)
            !imported?.token.isNullOrEmpty() ->
                putCredentials(prefs, profile, imported.token, imported.userId)
            clearCredentialsWhenAbsent -> {
                prefs.remove(getProfileKey(VK_ACCESS_TOKEN_ENCRYPTED, profile))
                prefs.remove(getProfileKey(VK_ACCESS_TOKEN, profile))
                prefs.remove(getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile))
            }
        }
    }

    private fun putCredentials(
        prefs: MutablePreferences,
        profile: Int,
        token: String,
        userId: String,
    ) {
        prefs.putSecret(VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, token, profile)
        prefs[getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile)] = userId
    }

    /**
     * Внешний импорт идёт мимо редактора, поэтому режимы VK выбираются тем же
     * правилом: ссылка с токеном и без хешей включает авто-режим API. Иначе
     * профиль 0 сохранился бы в ручном режиме без единого хеша и не подключился.
     */
    private fun applyImportedLinkModes(
        prefs: MutablePreferences,
        profile: Int,
        decision: VkLinkImportPolicy.Decision,
    ) {
        val modes = ProfileEditorPolicy.importedLinkModes(
            draft = ProfileDraft(
                id = profile,
                link = decision.sanitizedLink,
                vkAuthMode = normalizedAuthMode(prefs[getProfileKey(VK_AUTH_MODE, profile)]),
                vkHashMode = normalizedHashMode(prefs[getProfileKey(VK_HASH_MODE, profile)]),
            ),
            importedToken = decision.token.isNotEmpty(),
        )
        prefs[getProfileKey(VK_AUTH_MODE, profile)] = modes.vkAuthMode
        prefs[getProfileKey(VK_HASH_MODE, profile)] = modes.vkHashMode
    }

    private fun applyLinkDecision(
        prefs: MutablePreferences,
        decision: VkLinkImportPolicy.Decision,
        profile: Int,
    ) {
        prefs[getProfileKey(CSQTT_LINK, profile)] = decision.sanitizedLink
        if (decision.token.isNotEmpty()) {
            putCredentials(prefs, profile, decision.token, decision.userId)
        } else {
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_ENCRYPTED, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile))
        }
    }

    /** Убирает все профильные ключи ID из уже открытой транзакции. */
    private fun removeProfileKeys(prefs: MutablePreferences, profile: Int) {
        for (key in PROFILE_STRING_KEYS) prefs.remove(getProfileKey(key, profile))
        for (key in PROFILE_INT_KEYS) prefs.remove(getProfileKey(key, profile))
    }

    private suspend fun clearProfileKeys(profile: Int) {
        runCatching {
            dataStore.edit { prefs -> removeProfileKeys(prefs, profile) }
        }.onFailure { Log.w(TAG, "Не удалось очистить ключи удалённого профиля $profile", it) }
    }

    // --- Чтение из снимка ---

    private fun profileNameOf(prefs: Preferences, profile: Int): String {
        val name = prefs[getProfileKey(PROFILE_NAME, profile)]
        return if (name.isNullOrBlank()) ProfileEditorPolicy.defaultName(profile) else name
    }

    private fun summaryOf(prefs: Preferences, profile: Int): ProfileSummary {
        val hasLink = !prefs[getProfileKey(CSQTT_LINK, profile)].isNullOrBlank()
        val transport = normalizedTurnTransport(prefs[getProfileKey(TURN_TRANSPORT, profile)])
        val fingerprint = prefs[getProfileKey(SELECTED_FINGERPRINT, profile)]
            ?: CsqttConstants.Tunnel.DEFAULT_FINGERPRINT
        val workers = normalizedWorkers(prefs[getProfileKey(WORKERS_PER_HASH, profile)])
        return ProfileSummary(
            id = profile,
            name = profileNameOf(prefs, profile),
            hasLink = hasLink,
            summary = ProfileRegistryPolicy.summaryText(hasLink, transport, fingerprint, workers),
            transport = transport,
            fingerprint = fingerprint,
            workersPerHash = workers,
        )
    }

    private fun draftOf(prefs: Preferences, profile: Int): ProfileDraft = ProfileDraft(
        id = profile,
        name = profileNameOf(prefs, profile),
        link = prefs[getProfileKey(CSQTT_LINK, profile)] ?: "",
        vkHashes = prefs[getProfileKey(VK_HASHES, profile)] ?: "",
        vkAuthMode = normalizedAuthMode(prefs[getProfileKey(VK_AUTH_MODE, profile)]),
        vkHashMode = normalizedHashMode(prefs[getProfileKey(VK_HASH_MODE, profile)]),
        captchaMode = prefs[getProfileKey(CAPTCHA_MODE, profile)] ?: "auto",
        captchaSolveMethod = prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] ?: "auto",
        captchaWbvSolveMethod = prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] ?: "auto",
        turnTransport = normalizedTurnTransport(prefs[getProfileKey(TURN_TRANSPORT, profile)]),
        fingerprint = prefs[getProfileKey(SELECTED_FINGERPRINT, profile)]
            ?: CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
        selectedTurnSubnet = prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: "",
        turnSubnetIpLimit = prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] ?: "ALL",
        workersPerHash = normalizedWorkers(prefs[getProfileKey(WORKERS_PER_HASH, profile)]),
        hasVkToken = readSecret(
            prefs,
            VK_ACCESS_TOKEN_ENCRYPTED,
            VK_ACCESS_TOKEN,
            profile,
        ).isNotBlank(),
    )

    private fun customTurnIpsOf(prefs: Preferences, profile: Int): String {
        val subnetId = prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: ""
        if (subnetId.isBlank()) return ""
        val stored = prefs[getProfileKey(CUSTOM_TURN_IPS, profile)]
        if (!stored.isNullOrBlank()) return stored
        return TurnSubnetRegistry.getJoinedIpsForSubnet(subnetId)
    }

    private fun computeCustomTurnIps(subnetId: String, limit: String): String {
        val allIps = if (subnetId.isNotBlank()) TurnSubnetRegistry.getIpsForSubnet(subnetId) else emptyList()
        val limitCount = limit.toIntOrNull()
        return if (allIps.isEmpty()) {
            ""
        } else if (limitCount != null && limitCount > 0) {
            allIps.take(limitCount).joinToString(",")
        } else {
            allIps.joinToString(",")
        }
    }

    private fun normalizedAuthMode(raw: String?): String = when (raw) {
        CsqttConstants.VkAuth.MODE_CAPTCHA -> CsqttConstants.VkAuth.MODE_CAPTCHA
        CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_AUTO_JS
        else -> CsqttConstants.VkAuth.MODE_CALLS
    }

    private fun normalizedHashMode(raw: String?): String = when (raw) {
        "auto", CsqttConstants.VkAutoHash.MODE_AUTO_API -> CsqttConstants.VkAutoHash.MODE_AUTO_API
        CsqttConstants.VkAutoHash.MODE_AUTO_JS -> CsqttConstants.VkAutoHash.MODE_AUTO_JS
        else -> CsqttConstants.VkAutoHash.MODE_MANUAL
    }

    private fun normalizedObfsMode(raw: String?): String =
        if (raw == "unified") raw else CsqttConstants.Tunnel.DEFAULT_OBFS_MODE

    private fun normalizedTurnTransport(raw: String?): String = when (raw) {
        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
        else -> CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
    }

    private fun normalizedWorkers(raw: Int?): Int =
        WorkerCountPolicy.normalize(raw ?: 10, maximum = WorkerCountPolicy.defaultMaximum())

    // --- Секреты ---

    private fun readSecret(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        profile: Int,
    ): String = readSecretState(prefs, encryptedKey, legacyKey, profile).value

    private fun readSecretState(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        profile: Int,
    ): StoredSecret {
        val profEncryptedKey = getProfileKey(encryptedKey, profile)
        val profLegacyKey = getProfileKey(legacyKey, profile)
        return resolveStoredSecret(
            prefs[profEncryptedKey],
            prefs[profLegacyKey],
            secureStore::decrypt,
        )
    }

    private fun MutablePreferences.putSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        value: String,
        profile: Int,
    ) {
        val profEncryptedKey = getProfileKey(encryptedKey, profile)
        val profLegacyKey = getProfileKey(legacyKey, profile)
        if (value.isBlank()) {
            remove(profEncryptedKey)
            remove(profLegacyKey)
        } else {
            this[profEncryptedKey] = secureStore.encrypt(value)
            remove(profLegacyKey)
        }
    }

    // Последним в классе, после всех свойств: Kotlin присваивает их сверху вниз, а сборщики
    // ниже стартуют на других потоках сразу. Выше flow'ов, которые они читают, сборщик успевал
    // прочитать null и вызвать collect на null — так приложение падало при запуске.
    init {
        if (migrationsStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                migrateRegistryIfNeeded()
            }
        }
        if (uiCacheStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                launch { vkHashMode.collect { cachedVkHashMode = it } }
                launch { vkAccessToken.collect { cachedVkAccessToken = it } }
                launch { obfsMode.collect { cachedObfsMode = it } }
                launch { turnTransport.collect { cachedTurnTransport = it } }
                launch { workersPerHash.collect { cachedWorkersPerHash = it } }
                launch { customTurnIps.collect { cachedCustomTurnIps = it } }
            }
        }
    }
}
