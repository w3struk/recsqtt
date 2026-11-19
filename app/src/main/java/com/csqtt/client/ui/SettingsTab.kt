package com.csqtt.client.ui

import com.csqtt.client.showRaisedToast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.CsqttConstants
import com.csqtt.client.VkAutoCallsManager
import com.csqtt.client.VkCredentialStartPolicy
import com.csqtt.client.WorkerCountPolicy
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidator
import com.csqtt.client.VkLinkImportPolicy
import com.csqtt.client.VkCredentialUiPolicy
import com.csqtt.client.BuildConfig
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.shouldConfirmTcpTransport
import com.csqtt.client.vkAuthModeForHashMode
import com.csqtt.client.vkHashModeForAuthMode
import com.csqtt.client.ui.components.CsqttScreen
import com.csqtt.client.ui.components.CsqttSettingRow
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes
import com.csqtt.client.ui.dialogs.HashesDialog
import com.csqtt.client.ui.tunnel.WorkersInfoDialog
import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.stripVkUrlStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.widget.Toast

private const val WORKERS_PER_GROUP = CsqttConstants.Tunnel.WORKERS_PER_GROUP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTab(
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    validationRequest: Int = 0,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    SettingsTabContent(
        context,
        scope,
        settingsStore,
        tunnelAuthSettings,
        validationRequest,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTabContent(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    validationRequest: Int,
) {
    val workersPerHash = remember(settingsStore) {
        settingsStore.workersPerHash
        .map<Int, Int?> { it }
    }
    val savedWorkersPerHash by workersPerHash
        .collectAsStateWithLifecycle(initialValue = SettingsStore.cachedWorkersPerHash)

    val activeProfile = tunnelAuthSettings.profile
    val csqttLink = tunnelAuthSettings.csqttLink

    val activeFingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val activeClientIds by settingsStore.activeClientIds.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val vkHashCheckResults = remember(vkHashCheckResultsJson) {
        VkHashValidationCodec.decode(vkHashCheckResultsJson)
    }
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelStarting by TunnelManager.starting.collectAsStateWithLifecycle()
    val tunnelStopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    // Пока туннель жив или переходит между состояниями, креды не трогаем: закрытие
    // звонков оборвало бы сессию, а старт мог бы успеть создать новые.
    val vkTokenActionEnabled = !tunnelRunning && !tunnelStarting && !tunnelStopping
    val showCredentialUi = VkCredentialUiPolicy.statusVisible(BuildConfig.DEBUG)
    val showForgetTokenAction = VkCredentialUiPolicy.forgetTokenVisible(BuildConfig.DEBUG)
    var showHashModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showWorkModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showForgetTokenDialog by rememberSaveable { mutableStateOf(false) }
    var vkModeBlockedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var vkAuthMode by rememberSaveable { mutableStateOf(CsqttConstants.VkAuth.MODE_CALLS) }
    val autoJsRiskAcknowledged by settingsStore.autoJsRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    var showAutoJsRiskDialog by remember { mutableStateOf(false) }
    var showWorkersInfoDialog by rememberSaveable { mutableStateOf(false) }
    var pendingAutoJsSelection by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showRequiredFieldErrors by rememberSaveable(activeProfile) { mutableStateOf(false) }

    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedTurnTransport ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    )
    val tcpTransportRiskAcknowledged by settingsStore.tcpTransportRiskAcknowledged.collectAsStateWithLifecycle(
        initialValue = false,
    )
    var showTurnTransportGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnTransportDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showTcpTransportRiskDialog by remember { mutableStateOf(false) }
    var pendingTcpTransportSelection by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun applyTurnTransport(transport: String) {
        scope.launch { settingsStore.saveTurnTransport(transport) }
    }

    fun requestTurnTransport(transport: String) {
        if (shouldConfirmTcpTransport(turnTransport, transport, tcpTransportRiskAcknowledged)) {
            pendingTcpTransportSelection = { applyTurnTransport(transport) }
            showTcpTransportRiskDialog = true
        } else {
            applyTurnTransport(transport)
        }
    }

    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    var vkHash1 by rememberSaveable { mutableStateOf("") }
    var vkHash2 by rememberSaveable { mutableStateOf("") }
    var vkHash3 by rememberSaveable { mutableStateOf("") }
    var vkHash4 by rememberSaveable { mutableStateOf("") }
    var vkHash5 by rememberSaveable { mutableStateOf("") }
    var vkHash6 by rememberSaveable { mutableStateOf("") }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var autoCaptchaEnabled by rememberSaveable { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }
    var isManualMode by rememberSaveable { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable { mutableStateOf(true) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var linkSaveJob by remember { mutableStateOf<Job?>(null) }
    var linkText by remember(activeProfile, csqttLink) { mutableStateOf(csqttLink) }
    var initialized by rememberSaveable(activeProfile) { mutableStateOf(false) }

    val allHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        listOf(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)
    }
    val uniqueHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6, vkHashCheckResults) {
        VkHashValidationCodec.active(allHashes, vkHashCheckResults)
    }
    val parsedCsqttLink = remember(linkText) { parseCsqttLink(linkText) }
    val linkHashes = parsedCsqttLink?.hashes.orEmpty()
    val serverWorkers by TunnelManager.serverAssignedWorkers.collectAsState()
    val effectiveServerWorkers = serverWorkers.takeIf { tunnelRunning && it in 3..100 }
    val effectiveVkAuthMode = if (
        linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    ) {
        CsqttConstants.VkAuth.MODE_CALLS
    } else {
        vkAuthMode
    }
    val accountAutoJsMode = effectiveVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    // Режим хешей и наличие токена — только из хранилища. Ссылка после импорта кредов
    // не несёт, поэтому её текст не может ни включить авто-режим, ни показать статус.
    val autoHashMode = accountAutoJsMode || (
        savedHashMode != null && savedHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    )
    val vkTokenActive = savedVkAccessToken?.isNotBlank() == true
    val storedVkUserId by settingsStore.vkAccessTokenUserId.collectAsStateWithLifecycle(initialValue = "")
    val filledHashCount = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) { uniqueHashes.size }
    val combinedHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        allHashes.filter { it.isNotBlank() && it.length >= 16 }.distinct().joinToString(",")
    }
    val selectableMaxWorkers = CsqttConstants.Tunnel.MAX_WORKERS.toFloat()
    val currentWorkers = roundToGroup(
        (savedWorkersPerHash ?: WORKERS_PER_GROUP).toFloat()
            .coerceIn(5f, selectableMaxWorkers),
        selectableMaxWorkers,
    )

    val hashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        buildList {
            allHashes.forEachIndexed { i, h ->
                if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
            }
            val filled = allHashes.filter { it.isNotBlank() && it.length >= 16 }
            if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
        }
    }
    val hasInputHashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) { hashErrors.isNotEmpty() }

    LaunchedEffect(validationRequest) {
        if (validationRequest > 0) showRequiredFieldErrors = true
    }

    fun parseHashes(raw: String) {
        val parts = raw.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
        vkHash1 = parts.getOrElse(0) { "" }
        vkHash2 = parts.getOrElse(1) { "" }
        vkHash3 = parts.getOrElse(2) { "" }
        vkHash4 = parts.getOrElse(3) { "" }
        vkHash5 = parts.getOrElse(4) { "" }
        vkHash6 = parts.getOrElse(5) { "" }
    }

    fun normalizeHashes(vararg hashes: String): String {
        return hashes
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    LaunchedEffect(activeProfile) {
        saveJob?.cancel()
        linkSaveJob?.cancel()
        val hashes = settingsStore.vkHashes.first()
        val loadedVkAuthMode = settingsStore.vkAuthMode.first()
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvCaptchaMethod = settingsStore.captchaWbvSolveMethod.first()

        parseHashes(hashes)
        vkAuthMode = loadedVkAuthMode
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvCaptchaMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"

        initialized = true
    }

    DisposableEffect(Unit) {
        onDispose {
            saveJob?.cancel()
            linkSaveJob?.cancel()
        }
    }

    fun saveTunnelSettingsNow(hashes: String = combinedHashes, onSaved: (() -> Unit)? = null) {
        saveJob?.cancel()
        scope.launch {
            settingsStore.saveParticipantTunnelSettings(hashes, currentWorkers.toInt())
            onSaved?.invoke()
        }
    }

    fun applyWorkMode(mode: String) {
        vkAuthMode = mode
        scope.launch {
            settingsStore.saveVkAuthMode(mode)
        }
    }

    fun applyHashMode(mode: String) {
        val nextWorkMode = vkAuthModeForHashMode(mode, vkAuthMode)
        vkAuthMode = nextWorkMode
        scope.launch {
            settingsStore.saveVkHashMode(mode)
        }
    }

    fun blockModeSelection(hashMode: String, authMode: String): Boolean {
        val reason = VkCredentialStartPolicy.blockedReason(
            hashMode = hashMode,
            authMode = authMode,
            hasToken = vkTokenActive,
        ) ?: return false
        // Тот же текст, что покажет сервис, если запуск всё-таки дойдёт до него.
        vkModeBlockedMessage = reason
        return true
    }

    fun forgetVkToken() {
        // Пока туннель поднят, закрытие звонков оборвало бы живую сессию.
        if (TunnelManager.running.value || TunnelManager.starting.value || TunnelManager.stopping.value) return
        scope.launch {
            val token = runCatching { settingsStore.vkAccessToken.first() }.getOrDefault("")
            val closed = if (token.isNotBlank()) {
                // Best effort: если VK недоступен, креды всё равно удаляем — иначе
                // «Забыть токен» не сработает в офлайне.
                runCatching { VkAutoCallsManager.closeAll(context, token, activeProfile) }
                    .getOrDefault(false)
            } else {
                false
            }
            settingsStore.clearVkAccessToken()
            TunnelManager.updateLog(
                "vk_token_forgotten",
                if (closed) {
                    "[OK] VK-токен забыт: звонки закрыты, креды удалены"
                } else {
                    "[OK] VK-токен забыт: креды удалены, звонки закрыть не удалось"
                },
                40,
                false,
            )
            context.showRaisedToast(
                if (closed) "Токен забыт, звонки VK закрыты" else "Токен забыт",
                Toast.LENGTH_SHORT,
            )
        }
    }

    fun requestWorkMode(mode: String) {
        if (blockModeSelection(vkHashModeForAuthMode(mode, savedHashMode, vkTokenActive), mode)) return
        if (shouldConfirmAutoJsMode(vkAuthMode, mode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyWorkMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyWorkMode(mode)
        }
    }

    fun requestHashMode(mode: String) {
        val nextWorkMode = vkAuthModeForHashMode(mode, vkAuthMode)
        if (blockModeSelection(mode, nextWorkMode)) return
        if (shouldConfirmAutoJsMode(vkAuthMode, nextWorkMode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyHashMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyHashMode(mode)
        }
    }

    LaunchedEffect(initialized, linkHashes, vkAuthMode) {
        if (!initialized) return@LaunchedEffect
        if (linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) {
            applyWorkMode(CsqttConstants.VkAuth.MODE_CALLS)
        }
    }

    LaunchedEffect(initialized, currentWorkers, savedWorkersPerHash) {
        val savedWorkers = savedWorkersPerHash
        if (initialized && savedWorkers != null && currentWorkers.toInt() != savedWorkers) {
            scope.launch { settingsStore.saveWorkersPerHash(currentWorkers.toInt()) }
        }
    }

    val scrollState = rememberScrollState()

    val hashesReadyForTunnel = when {
        linkHashes.isNotEmpty() -> true
        autoHashMode -> vkTokenActive
        else -> filledHashCount > 0
    }
    val linkRequiredError = showRequiredFieldErrors && parsedCsqttLink == null
    val hashesRequiredError = showRequiredFieldErrors && !hashesReadyForTunnel
    if (showHashesDialog) {
        HashesDialog(
            hash1 = vkHash1,
            hash2 = vkHash2,
            hash3 = vkHash3,
            hash4 = vkHash4,
            hash5 = vkHash5,
            hash6 = vkHash6,
            validationResults = vkHashCheckResults,
            onValidationResultsChange = { results ->
                scope.launch {
                    settingsStore.saveVkHashCheckResults(VkHashValidationCodec.encode(results))
                }
            },
            onCheck = { hashes ->
                VkHashValidator.check(context, hashes, activeFingerprint, activeClientIds)
            },
            onAutoSave = { h1, h2, h3, h4, h5, h6 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val cleaned5 = stripVkUrlStatic(h5)
                val cleaned6 = stripVkUrlStatic(h6)
                vkHash1 = cleaned1
                vkHash2 = cleaned2
                vkHash3 = cleaned3
                vkHash4 = cleaned4
                vkHash5 = cleaned5
                vkHash6 = cleaned6
                saveTunnelSettingsNow(normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4, cleaned5, cleaned6))
            },
            onSave = { h1, h2, h3, h4, h5, h6 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val cleaned5 = stripVkUrlStatic(h5)
                val cleaned6 = stripVkUrlStatic(h6)
                vkHash1 = cleaned1
                vkHash2 = cleaned2
                vkHash3 = cleaned3
                vkHash4 = cleaned4
                vkHash5 = cleaned5
                vkHash6 = cleaned6
                saveTunnelSettingsNow(normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4, cleaned5, cleaned6)) {
                    showHashesDialog = false
                }
            },
            onDismiss = {
                saveTunnelSettingsNow(normalizeHashes(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)) {
                    showHashesDialog = false
                }
            }
        )
    }

    if (showForgetTokenDialog && showForgetTokenAction) {
        AlertDialog(
            onDismissRequest = { showForgetTokenDialog = false },
            title = { Text("Забыть токен?") },
            text = {
                Text(
                    "Сохранённый VK-токен и user_id будут удалены, а звонки VK — закрыты, " +
                        "если токен ещё работает. Вернуть доступ можно только новой ссылкой " +
                        "из панели.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetTokenDialog = false
                        forgetVkToken()
                    },
                    enabled = vkTokenActionEnabled,
                ) {
                    Text("Забыть", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetTokenDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    vkModeBlockedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { vkModeBlockedMessage = null },
            title = { Text("Режим недоступен") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vkModeBlockedMessage = null }) {
                    Text("Понятно")
                }
            },
        )
    }

    CsqttScreen {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { value ->
                        linkText = value.filterNot(Char::isWhitespace)
                        linkSaveJob?.cancel()
                        linkSaveJob = scope.launch {
                            delay(300)
                            when {
                                // Пустая ссылка — явное удаление; импорт пустую отвергает.
                                linkText.isBlank() -> settingsStore.saveCsqttParticipantLink("")
                                // Импорт кредов — только для настоящей ссылки из панели. Правка
                                // уже санитизированной ссылки (в ней токена нет) не должна
                                // стирать сохранённый доступ: пишем только текст ссылки.
                                VkLinkImportPolicy.decide(linkText)?.token?.isNotEmpty() == true ->
                                    settingsStore.importCsqttLink(linkText)
                                else -> settingsStore.saveCsqttParticipantLink(linkText)
                            }
                        }
                    },
                    label = { Text("Ссылка csqtt://", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("csqtt://connect?v=2&host=ip&peer=порт&password=пароль", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    isError = linkRequiredError || (linkText.isNotBlank() && parsedCsqttLink == null),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = CsqttShapes.Control,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                )

                if (linkRequiredError || (linkText.isNotBlank() && parsedCsqttLink == null)) {
                    Text(
                        text = if (linkText.isBlank()) {
                            "Вставьте ссылку csqtt:// из панели"
                        } else {
                            "Неверная ссылка reCSQTT v2"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                if (linkHashes.isNotEmpty()) {
                    Text(
                        text = "VK хеши из ссылки: ${linkHashes.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    VkHashModeControls(
                        hashSettingsLoaded = hashSettingsLoaded,
                        autoHashMode = autoHashMode,
                        savedHashMode = savedHashMode,
                        hasInputHashErrors = hasInputHashErrors || hashesRequiredError,
                        hashErrorTexts = hashErrors.filter { !it.contains("короткий") },
                        onOpenHashes = { showHashesDialog = true },
                        onTitleInfo = { showHashModeGeneralDialog = true },
                        onInfo = { mode -> showHashModeDetailDialog = mode },
                        onSelected = ::requestHashMode,
                    )
                }

                // Статус кредов и «Забыть токен» — инструменты отладки: в release
                // пользователь видит только результат (режимы/блокер запуска).
                if (showCredentialUi && (vkTokenActive || linkHashes.isEmpty())) {
                    VkTokenStatusRow(
                        hasStoredToken = vkTokenActive,
                        storedUserId = storedVkUserId,
                        showAction = showForgetTokenAction,
                        enabled = vkTokenActionEnabled,
                        onForgetToken = { showForgetTokenDialog = true },
                    )
                }


                CompactDropdownSetting(
                    title = "Режим кредов",
                    selectedKey = effectiveVkAuthMode,
                    options = listOf(
                        CsqttConstants.VkAuth.MODE_CAPTCHA to "Капча",
                        CsqttConstants.VkAuth.MODE_CALLS to "Авто",
                        CsqttConstants.VkAuth.MODE_AUTO_JS to "Авто ВК",
                    ).filterNot { (mode, _) ->
                        linkHashes.isNotEmpty() && mode == CsqttConstants.VkAuth.MODE_AUTO_JS
                    },
                    enabled = true,
                    indicatorProvider = { mode ->
                        when (mode) {
                            CsqttConstants.VkAuth.MODE_CAPTCHA -> ModeIndicator(progress = 0.30f, color = Color(0xFFE53935))
                            CsqttConstants.VkAuth.MODE_CALLS -> ModeIndicator(progress = 0.78f, color = Color(0xFF43A047))
                            CsqttConstants.VkAuth.MODE_AUTO_JS -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                            else -> null
                        }
                    },
                    onTitleInfo = { showWorkModeGeneralDialog = true },
                    onInfo = { mode -> showWorkModeDetailDialog = mode },
                    onSelected = ::requestWorkMode,
                )

                CompactDropdownSetting(
                    title = "Транспорт",
                    selectedKey = turnTransport,
                    options = listOf(
                        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS to "TCP",
                        CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT to "UDP",
                    ),
                    enabled = true,
                    indicatorProvider = { transport ->
                        when (transport) {
                            CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT -> ModeIndicator(
                                progress = 1.0f,
                                color = Color(0xFF1475FD),
                            )
                            CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> ModeIndicator(
                                progress = 0.70f,
                                color = Color(0xFF43A047),
                            )
                            else -> null
                        }
                    },
                    onTitleInfo = { showTurnTransportGeneralDialog = true },
                    onInfo = { transport -> showTurnTransportDetailDialog = transport },
                    onSelected = ::requestTurnTransport,
                )

                TunnelWorkersControl(
                    value = currentWorkers,
                    maximum = selectableMaxWorkers,
                    enabled = !tunnelRunning,
                    onValueChange = { value ->
                        val newWorkers = value.toInt()
                        scope.launch {
                            settingsStore.saveWorkersPerHash(newWorkers)
                        }
                    },
                    onInfo = { showWorkersInfoDialog = true },
                    fixedWorkers = effectiveServerWorkers,
                )

                AnimatedVisibility(
                    visible = vkAuthMode == CsqttConstants.VkAuth.MODE_CAPTCHA,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoCaptchaEnabled,
                                enabled = !tunnelRunning,
                                onCheckedChange = { enabled ->
                                    autoCaptchaEnabled = enabled
                                    scope.launch {
                                        if (enabled) {
                                            settingsStore.saveCaptchaMode("auto")
                                            settingsStore.saveCaptchaSolveMethod("auto")
                                        } else {
                                            val mode = if (useWVCaptcha) "wv" else "rjs"
                                            settingsStore.saveCaptchaMode(mode)
                                            settingsStore.saveCaptchaSolveMethod(if (mode == "wv" && isManualMode) "manual" else "auto")
                                        }
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !autoCaptchaEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Метод обхода капчи",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ProtocolChip("WBV", useWVCaptcha, enabled = !tunnelRunning) {
                                            useWVCaptcha = true
                                            isManualMode = wbvManualMode
                                            scope.launch {
                                                settingsStore.saveCaptchaMode("wv")
                                                settingsStore.saveCaptchaSolveMethod(if (wbvManualMode) "manual" else "auto")
                                            }
                                        }
                                        ProtocolChip("RJS", !useWVCaptcha, enabled = !tunnelRunning, isError = false) {
                                            useWVCaptcha = false
                                            isManualMode = false
                                            scope.launch {
                                                settingsStore.saveCaptchaMode("rjs")
                                                settingsStore.saveCaptchaSolveMethod("auto")
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Режим обхода",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (useWVCaptcha) {
                                            ProtocolChip(
                                                "РУЧ",
                                                isManualMode,
                                                enabled = !tunnelRunning,
                                                isError = false
                                            ) {
                                                isManualMode = true
                                                wbvManualMode = true
                                                scope.launch { settingsStore.saveWbvCaptchaSolveMethod("manual") }
                                            }
                                            ProtocolChip(
                                                "АВТ",
                                                !isManualMode,
                                                enabled = !tunnelRunning,
                                                isError = false
                                            ) {
                                                isManualMode = false
                                                wbvManualMode = false
                                                scope.launch { settingsStore.saveWbvCaptchaSolveMethod("auto") }
                                            }
                                        } else {
                                            ProtocolChip(
                                                "АВТ",
                                                selected = true,
                                                enabled = false,
                                                isError = false
                                            ) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }

            if (showHashModeGeneralDialog) {
                HashModeInfoDialog(mode = null, onDismiss = { showHashModeGeneralDialog = false })
            }
            showHashModeDetailDialog?.let { mode ->
                HashModeInfoDialog(mode = mode, onDismiss = { showHashModeDetailDialog = null })
            }

            if (showWorkModeGeneralDialog) {
                WorkModeInfoDialog(mode = null, onDismiss = { showWorkModeGeneralDialog = false })
            }
            showWorkModeDetailDialog?.let { mode ->
                WorkModeInfoDialog(mode = mode, onDismiss = { showWorkModeDetailDialog = null })
            }
            if (showAutoJsRiskDialog) {
                AutoVkRiskDialog(
                    requireAcknowledgement = true,
                    onAcknowledge = {
                        showAutoJsRiskDialog = false
                        pendingAutoJsSelection?.invoke()
                        pendingAutoJsSelection = null
                    },
                    onDoNotRemind = {
                        scope.launch { settingsStore.saveAutoJsRiskAcknowledged(true) }
                        showAutoJsRiskDialog = false
                        pendingAutoJsSelection?.invoke()
                        pendingAutoJsSelection = null
                    },
                )
            }
            if (showWorkersInfoDialog) {
                WorkersInfoDialog(onDismiss = { showWorkersInfoDialog = false })
            }
            if (showTurnTransportGeneralDialog) {
                TurnTransportInfoDialog(mode = null, onDismiss = { showTurnTransportGeneralDialog = false })
            }
            showTurnTransportDetailDialog?.let { transport ->
                TurnTransportInfoDialog(mode = transport, onDismiss = { showTurnTransportDetailDialog = null })
            }
            if (showTcpTransportRiskDialog) {
                TcpTransportRiskDialog(
                    onAcknowledge = {
                        showTcpTransportRiskDialog = false
                        pendingTcpTransportSelection?.invoke()
                        pendingTcpTransportSelection = null
                    },
                    onDoNotRemind = {
                        scope.launch { settingsStore.saveTcpTransportRiskAcknowledged(true) }
                        showTcpTransportRiskDialog = false
                        pendingTcpTransportSelection?.invoke()
                        pendingTcpTransportSelection = null
                    },
                )
            }
        }
    }
}
}

@Composable
private fun VkTokenStatusRow(
    hasStoredToken: Boolean,
    storedUserId: String,
    showAction: Boolean,
    enabled: Boolean,
    onForgetToken: () -> Unit,
) {
    // Статус читается только из хранилища: ссылка после импорта кредов их не несёт.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasStoredToken) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Токен из ссылки", fontWeight = FontWeight.SemiBold)
                val maskedUserId = VkCredentialStartPolicy.maskedUserId(storedUserId)
                Text(
                    text = if (maskedUserId.isNotEmpty()) {
                        "ID $maskedUserId"
                    } else {
                        "ID не указан — владелец звонков определится по отпечатку токена"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showAction && !enabled) {
                    Text(
                        text = "Сначала отключите VPN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            if (showAction) OutlinedButton(
                onClick = onForgetToken,
                enabled = enabled,
                modifier = Modifier.height(36.dp),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("Забыть токен", fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        } else {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Токен приходит из ссылки панели", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Без него «Авто API» и «Авто ВК» недоступны: импортируйте ссылку csqtt:// заново.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VkHashModeControls(
    hashSettingsLoaded: Boolean,
    autoHashMode: Boolean,
    savedHashMode: String?,
    hasInputHashErrors: Boolean,
    hashErrorTexts: List<String>,
    onOpenHashes: () -> Unit,
    onTitleInfo: () -> Unit,
    onInfo: (String) -> Unit,
    onSelected: (String) -> Unit,
) {
    if (!hashSettingsLoaded) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!autoHashMode) {
            OutlinedButton(
                onClick = onOpenHashes,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = BorderStroke(
                    1.dp,
                    if (hasInputHashErrors) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Filled.Phone, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ссылки VK звонков",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (!autoHashMode && hashErrorTexts.isNotEmpty()) {
            Text(
                text = hashErrorTexts.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    CompactDropdownSetting(
        title = "Режим хешей",
        selectedKey = savedHashMode ?: CsqttConstants.VkAutoHash.MODE_MANUAL,
        options = listOf(
            CsqttConstants.VkAutoHash.MODE_MANUAL to "Ручной",
            CsqttConstants.VkAutoHash.MODE_AUTO_API to "Авто API",
            CsqttConstants.VkAutoHash.MODE_AUTO_JS to "Авто ВК",
        ),
        enabled = true,
        indicatorProvider = { mode ->
            when (mode) {
                CsqttConstants.VkAutoHash.MODE_MANUAL -> ModeIndicator(progress = 0.78f, color = Color(0xFF43A047))
                CsqttConstants.VkAutoHash.MODE_AUTO_API -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                CsqttConstants.VkAutoHash.MODE_AUTO_JS -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                else -> null
            }
        },
        onTitleInfo = onTitleInfo,
        onInfo = onInfo,
        onSelected = onSelected,
        leadingContent = null,
    )
}
