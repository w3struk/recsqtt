package com.csqtt.client.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ProfileDraft
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkCallCloseUiPolicy
import com.csqtt.client.VkCredentialStartPolicy
import com.csqtt.client.VkCredentialUiPolicy
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidator
import com.csqtt.client.VkLinkImportPolicy
import com.csqtt.client.R
import com.csqtt.client.normalizeVkModeSelection
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.shouldConfirmTcpTransport
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes
import com.csqtt.client.ui.dialogs.HashesDialog
import com.csqtt.client.ui.dialogs.QrScannerDialog
import com.csqtt.client.ui.dialogs.TurnSubnetsDialog
import com.csqtt.client.ui.tunnel.WorkersInfoDialog
import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.stripVkUrlStatic
import com.csqtt.client.vkAuthModeForHashMode
import com.csqtt.client.vkHashModeForAuthMode
import kotlinx.coroutines.launch

/**
 * Все поля подключения профиля. Работают только с локальным черновиком:
 * ни одно изменение не пишется в хранилище до «Сохранить». QR и вставка
 * доступны при пустом поле ссылки, у заполненного остаётся X очистки.
 */
@Composable
internal fun ProfileConnectionFields(
    settingsStore: SettingsStore,
    draft: ProfileDraft,
    linkError: Boolean,
    draftHasStoredToken: Boolean,
    storedUserId: String,
    hashCheckResultsJson: String,
    clientIds: String,
    onDraftChange: (ProfileDraft) -> Unit,
    onLinkEdited: (String) -> Unit,
    onLinkImported: (VkLinkImportPolicy.Decision) -> Unit,
    onHashCheckResultsChange: (String) -> Unit,
    onForgetToken: (() -> Unit)?,
    onCloseVkCalls: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelStarting by TunnelManager.starting.collectAsStateWithLifecycle()
    val tunnelStopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val vkTokenActionEnabled = !tunnelRunning && !tunnelStarting && !tunnelStopping
    val isTunnelActive = !vkTokenActionEnabled
    val serverWorkers by TunnelManager.serverAssignedWorkers.collectAsState()
    val effectiveServerWorkers = serverWorkers.takeIf { tunnelRunning && it in 3..100 }

    val autoJsRiskAcknowledged by settingsStore.autoJsRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    val tcpTransportRiskAcknowledged by settingsStore.tcpTransportRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)

    val showCredentialUi = VkCredentialUiPolicy.statusVisible(BuildConfig.DEBUG)
    val showForgetTokenAction = VkCredentialUiPolicy.forgetTokenVisible(BuildConfig.DEBUG)
    val showCloseVkCallsAction = VkCallCloseUiPolicy.closeCallsVisible(BuildConfig.DEBUG)

    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showWorkModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkersInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnTransportGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnTransportDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showTurnSubnetsDialog by rememberSaveable { mutableStateOf(false) }
    var showForgetTokenDialog by rememberSaveable { mutableStateOf(false) }
    var showCloseVkCallsDialog by rememberSaveable { mutableStateOf(false) }
    var vkModeBlockedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAutoJsRiskDialog by remember { mutableStateOf(false) }
    var pendingAutoJsSelection by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showTcpTransportRiskDialog by remember { mutableStateOf(false) }
    var pendingTcpTransportSelection by remember { mutableStateOf<(() -> Unit)?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Требуется разрешение на камеру для сканирования QR-кода", Toast.LENGTH_SHORT).show()
        }
    }

    val parsedLink = remember(draft.link) { parseCsqttLink(draft.link) }
    val linkHashes = parsedLink?.hashes.orEmpty()
    val hashFields = remember(draft.vkHashes) { parseHashFields(draft.vkHashes) }
    val validationResults = remember(hashCheckResultsJson) {
        VkHashValidationCodec.decode(hashCheckResultsJson)
    }
    val hashErrors = remember(hashFields) { hashInputErrors(hashFields) }
    val effectiveAuthMode = if (linkHashes.isNotEmpty() && draft.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) {
        CsqttConstants.VkAuth.MODE_CALLS
    } else {
        draft.vkAuthMode
    }
    val accountAutoJsMode = effectiveAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    val autoHashModeEffective = accountAutoJsMode ||
        draft.vkHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL

    val autoCaptchaEnabled = draft.captchaMode == "auto"
    val useWVCaptcha = draft.captchaMode != "rjs"
    val wbvManualMode = draft.captchaWbvSolveMethod != "auto"
    val isManualMode = if (draft.captchaMode == "wv") wbvManualMode else draft.captchaSolveMethod != "auto"
    val hasStoredToken = draft.hasVkToken || draftHasStoredToken
    val currentWorkers = roundToGroup(
        draft.workersPerHash.toFloat().coerceIn(5f, CsqttConstants.Tunnel.MAX_WORKERS.toFloat()),
        CsqttConstants.Tunnel.MAX_WORKERS.toFloat(),
    )

    fun blockModeSelection(hashMode: String, authMode: String): Boolean {
        val reason = VkCredentialStartPolicy.blockedReason(
            hashMode = hashMode,
            authMode = authMode,
            hasToken = hasStoredToken,
        ) ?: return false
        vkModeBlockedMessage = reason
        return true
    }

    fun applyWorkMode(mode: String) {
        val selection = normalizeVkModeSelection(
            requestedAuthMode = mode,
            requestedHashMode = vkHashModeForAuthMode(mode, draft.vkHashMode, hasStoredToken),
            hasVkToken = hasStoredToken,
        )
        onDraftChange(draft.copy(vkAuthMode = selection.authMode, vkHashMode = selection.hashMode))
    }

    fun requestWorkMode(mode: String) {
        if (blockModeSelection(vkHashModeForAuthMode(mode, draft.vkHashMode, hasStoredToken), mode)) return
        if (shouldConfirmAutoJsMode(draft.vkAuthMode, mode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyWorkMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyWorkMode(mode)
        }
    }

    fun applyHashMode(mode: String) {
        val nextAuthMode = vkAuthModeForHashMode(mode, draft.vkAuthMode)
        val selection = normalizeVkModeSelection(
            requestedAuthMode = nextAuthMode,
            requestedHashMode = mode,
            hasVkToken = hasStoredToken,
        )
        onDraftChange(draft.copy(vkAuthMode = selection.authMode, vkHashMode = selection.hashMode))
    }

    fun requestHashMode(mode: String) {
        val nextAuthMode = vkAuthModeForHashMode(mode, draft.vkAuthMode)
        if (blockModeSelection(mode, nextAuthMode)) return
        if (shouldConfirmAutoJsMode(draft.vkAuthMode, nextAuthMode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyHashMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyHashMode(mode)
        }
    }

    fun applyTurnTransport(transport: String) {
        onDraftChange(draft.copy(turnTransport = transport))
    }

    fun requestTurnTransport(transport: String) {
        if (shouldConfirmTcpTransport(draft.turnTransport, transport, tcpTransportRiskAcknowledged)) {
            pendingTcpTransportSelection = { applyTurnTransport(transport) }
            showTcpTransportRiskDialog = true
        } else {
            applyTurnTransport(transport)
        }
    }

    fun importScanned(raw: String) {
        val decision = VkLinkImportPolicy.decide(raw)
        if (decision == null) {
            Toast.makeText(context, "QR-код не содержит ссылку reCSQTT", Toast.LENGTH_SHORT).show()
            return
        }
        showQrScanner = false
        onLinkImported(decision)
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val decision = VkLinkImportPolicy.decide(text)
        if (decision == null) {
            Toast.makeText(context, "В буфере нет ссылки reCSQTT", Toast.LENGTH_SHORT).show()
            return
        }
        onLinkImported(decision)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppSectionCard(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Параметры соединения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = draft.link,
                onValueChange = { value -> onLinkEdited(value.filterNot(Char::isWhitespace)) },
                label = if (draft.link.isBlank()) {
                    {
                        Text(
                            "Ссылка csqtt:// (обязательно)",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
                placeholder = {
                    Text(
                        "csqtt://connect?v=2&host=ip&peer=порт&password=пароль",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                isError = linkError,
                modifier = Modifier.fillMaxWidth(),
                shape = CsqttShapes.Control,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                ),
                trailingIcon = {
                    if (draft.link.isBlank()) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.CAMERA,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            showQrScanner = true
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_qr_scan),
                                        contentDescription = "Сканировать QR-код",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = { pasteFromClipboard() },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentPaste,
                                        contentDescription = "Вставить из буфера обмена",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    } else {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            IconButton(
                                onClick = { onLinkEdited("") },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Очистить ссылку",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )

            if (linkError) {
                Text(
                    text = "Неверная ссылка reCSQTT v2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                    draft = draft,
                    autoHashMode = autoHashModeEffective,
                    hasInputHashErrors = hashErrors.isNotEmpty(),
                    hashErrorTexts = hashErrors.filter { !it.contains("короткий") },
                    onOpenHashes = { showHashesDialog = true },
                    onTitleInfo = { showHashModeGeneralDialog = true },
                    onInfo = { mode -> showHashModeDetailDialog = mode },
                    onSelected = ::requestHashMode,
                )
            }

            val showForgetToken = showForgetTokenAction && onForgetToken != null
            val showCloseVkCalls = showCloseVkCallsAction && onCloseVkCalls != null
            if (showCredentialUi && (hasStoredToken || linkHashes.isEmpty())) {
                VkTokenStatusRow(
                    hasStoredToken = hasStoredToken,
                    storedUserId = storedUserId,
                    showForgetToken = showForgetToken,
                    showCloseVkCalls = showCloseVkCalls,
                    enabled = vkTokenActionEnabled,
                    onForgetToken = { showForgetTokenDialog = true },
                    onCloseVkCalls = { showCloseVkCallsDialog = true },
                )
            }

            CompactDropdownSetting(
                title = "Режим кредов",
                selectedKey = effectiveAuthMode,
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

            AnimatedVisibility(
                visible = draft.vkAuthMode == CsqttConstants.VkAuth.MODE_CAPTCHA,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = autoCaptchaEnabled,
                            onCheckedChange = { enabled ->
                                onDraftChange(
                                    if (enabled) {
                                        draft.copy(
                                            captchaMode = "auto",
                                            captchaSolveMethod = "auto",
                                        )
                                    } else {
                                        val mode = if (useWVCaptcha) "wv" else "rjs"
                                        draft.copy(
                                            captchaMode = mode,
                                            captchaSolveMethod = if (mode == "wv" && isManualMode) "manual" else "auto",
                                        )
                                    }
                                )
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = !autoCaptchaEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Метод обхода капчи",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProtocolChip("WBV", useWVCaptcha) {
                                        onDraftChange(
                                            draft.copy(
                                                captchaMode = "wv",
                                                captchaSolveMethod = if (wbvManualMode) "manual" else "auto",
                                            )
                                        )
                                    }
                                    ProtocolChip("RJS", !useWVCaptcha, isError = false) {
                                        onDraftChange(
                                            draft.copy(
                                                captchaMode = "rjs",
                                                captchaSolveMethod = "auto",
                                            )
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Режим обхода",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (useWVCaptcha) {
                                        ProtocolChip("РУЧ", isManualMode, isError = false) {
                                            onDraftChange(
                                                draft.copy(
                                                    captchaWbvSolveMethod = "manual",
                                                    captchaSolveMethod = "manual",
                                                )
                                            )
                                        }
                                        ProtocolChip("АВТ", !isManualMode, isError = false) {
                                            onDraftChange(
                                                draft.copy(
                                                    captchaWbvSolveMethod = "auto",
                                                    captchaSolveMethod = "auto",
                                                )
                                            )
                                        }
                                    } else {
                                        ProtocolChip("АВТ", selected = true, enabled = false, isError = false) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }

            CompactDropdownSetting(
                title = "Транспорт",
                selectedKey = draft.turnTransport,
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

            CompactDropdownSetting(
                title = "Отпечаток",
                selectedKey = draft.fingerprint,
                options = listOf(
                    "chrome" to "Chrome",
                    "safari" to "Safari",
                    "firefox" to "Firefox",
                    "edge" to "Edge",
                    "opera" to "Opera",
                ),
                enabled = true,
                onSelected = { fingerprint -> onDraftChange(draft.copy(fingerprint = fingerprint)) },
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            OutlinedButton(
                onClick = { showTurnSubnetsDialog = true },
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.AltRoute,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Выбор TURN-подсетей",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            TunnelWorkersControl(
                value = currentWorkers,
                maximum = CsqttConstants.Tunnel.MAX_WORKERS.toFloat(),
                enabled = true,
                onValueChange = { value ->
                    onDraftChange(draft.copy(workersPerHash = value.toInt()))
                },
                onInfo = { showWorkersInfoDialog = true },
                fixedWorkers = effectiveServerWorkers,
            )
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onQrCodeScanned = { raw -> importScanned(raw) },
        )
    }

    if (showHashesDialog) {
        HashesDialog(
            hash1 = hashFields.getOrElse(0) { "" },
            hash2 = hashFields.getOrElse(1) { "" },
            hash3 = hashFields.getOrElse(2) { "" },
            hash4 = hashFields.getOrElse(3) { "" },
            hash5 = hashFields.getOrElse(4) { "" },
            hash6 = hashFields.getOrElse(5) { "" },
            validationResults = validationResults,
            onValidationResultsChange = { results ->
                onHashCheckResultsChange(VkHashValidationCodec.encode(results))
            },
            onCheck = { hashes ->
                VkHashValidator.check(context, hashes, draft.fingerprint, clientIds)
            },
            onAutoSave = { h1, h2, h3, h4, h5, h6 ->
                onDraftChange(draft.copy(vkHashes = normalizeHashes(h1, h2, h3, h4, h5, h6)))
            },
            onSave = { h1, h2, h3, h4, h5, h6 ->
                onDraftChange(draft.copy(vkHashes = normalizeHashes(h1, h2, h3, h4, h5, h6)))
                showHashesDialog = false
            },
            onDismiss = {
                onDraftChange(draft.copy(vkHashes = normalizeHashes(*hashFields.toTypedArray())))
                showHashesDialog = false
            },
        )
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
    if (showTurnSubnetsDialog) {
        TurnSubnetsDialog(
            selectedSubnetId = draft.selectedTurnSubnet,
            turnSubnetIpLimit = draft.turnSubnetIpLimit,
            onSelectSubnet = { id -> onDraftChange(draft.copy(selectedTurnSubnet = id)) },
            onSelectIpLimit = { limit -> onDraftChange(draft.copy(turnSubnetIpLimit = limit)) },
            onDismiss = { showTurnSubnetsDialog = false },
        )
    }
    if (showForgetTokenDialog && onForgetToken != null) {
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
                        onForgetToken.invoke()
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
    if (showCloseVkCallsDialog && onCloseVkCalls != null) {
        CloseVkCallsDialog(
            isTunnelActive = isTunnelActive,
            onDismiss = { showCloseVkCallsDialog = false },
            onConfirm = {
                showCloseVkCallsDialog = false
                onCloseVkCalls.invoke()
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

}

private fun parseHashFields(raw: String): List<String> {
    val parts = raw.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
    return List(6) { index -> parts.getOrElse(index) { "" } }
}

private fun normalizeHashes(vararg hashes: String): String = hashes
    .map { stripVkUrlStatic(it) }
    .filter { it.isNotBlank() && it.length >= 16 }
    .distinct()
    .joinToString(",")

private fun hashInputErrors(hashes: List<String>): List<String> = buildList {
    hashes.forEachIndexed { i, h ->
        if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
    }
    val filled = hashes.filter { it.isNotBlank() && it.length >= 16 }
    if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
}

@Composable
private fun VkTokenStatusRow(
    hasStoredToken: Boolean,
    storedUserId: String,
    showForgetToken: Boolean,
    showCloseVkCalls: Boolean,
    enabled: Boolean,
    onForgetToken: () -> Unit,
    onCloseVkCalls: () -> Unit,
) {
    val anyActionVisible = showForgetToken || showCloseVkCalls
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    if (anyActionVisible && !enabled) {
                        Text(
                            text = "Сначала отключите VPN",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
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
        if (hasStoredToken && anyActionVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCloseVkCalls) {
                    OutlinedButton(
                        onClick = onCloseVkCalls,
                        enabled = enabled,
                        modifier = Modifier.height(36.dp),
                        shape = CsqttShapes.Pill,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (enabled) {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CallEnd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Закрыть звонки VK", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                if (showForgetToken) {
                    OutlinedButton(
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
                }
            }
        }
    }
}

@Composable
private fun CloseVkCallsDialog(
    isTunnelActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = CsqttShapes.Dialog,
            color = appSectionCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CallEnd,
                        contentDescription = null,
                        tint = if (isTunnelActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "Закрыть звонки VK?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                Text(
                    text = if (isTunnelActive) {
                        "Туннель запущен. Сначала необходимо отключить VPN: завершение звонков во время активного соединения невозможно.\n\nЗвонки будут завершены в VK, а сохранённые ссылки удалены. При следующем запуске потребуется создать новые звонки и решить капчу."
                    } else {
                        "Звонки будут завершены в VK, а сохранённые ссылки удалены из памяти приложения. При следующем запуске потребуется создать новые звонки — для этого нужно решить капчу."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = !isTunnelActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CsqttSizes.ControlHeight),
                        shape = CsqttShapes.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                    ) {
                        Text(
                            text = "Закрыть звонки",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CsqttSizes.ControlHeight),
                        shape = CsqttShapes.Pill,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "Отмена",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VkHashModeControls(
    draft: ProfileDraft,
    autoHashMode: Boolean,
    hasInputHashErrors: Boolean,
    hashErrorTexts: List<String>,
    onOpenHashes: () -> Unit,
    onTitleInfo: () -> Unit,
    onInfo: (String) -> Unit,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
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
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    CompactDropdownSetting(
        title = "Режим хешей",
        selectedKey = draft.vkHashMode,
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
