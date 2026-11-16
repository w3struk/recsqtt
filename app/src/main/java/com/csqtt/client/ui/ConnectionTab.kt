package com.csqtt.client.ui

import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionSource
import com.csqtt.client.ConnectionStartBlocker
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.newTunnelSessionId
import com.csqtt.client.resolveConnectionSource
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.components.CsqttScreen
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes
import com.csqtt.client.ui.utils.parseCsqttLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun ConnectionTab(
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    onInvalidConfiguration: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelStarting by TunnelManager.starting.collectAsStateWithLifecycle()
    val tunnelStopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val autoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val cooldownActive by TunnelManager.cooldownActive.collectAsStateWithLifecycle()

    val csqttLink = tunnelAuthSettings.csqttLink
    val vkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val workersPerHash by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 18)
    val sni by settingsStore.sni.collectAsStateWithLifecycle(initialValue = "")
    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT)
    val srtpMode by settingsStore.srtpMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedSrtpMode ?: CsqttConstants.SrtpMode.DEFAULT)
    val customTurnIps by settingsStore.customTurnIps.collectAsStateWithLifecycle(initialValue = "")
    val vkAuthMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.VkAuth.MODE_CALLS)
    val captchaMode by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
    val captchaSolveMethod by settingsStore.captchaSolveMethod.collectAsStateWithLifecycle(initialValue = "auto")
    val activeFingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val activeClientIds by settingsStore.activeClientIds.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)

    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }
    var isClosingVkCalls by remember { mutableStateOf(false) }
    var showCloseVkCallsConfirm by remember { mutableStateOf(false) }
    val showCloseVkCalls = savedHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API
    val isTunnelActive = tunnelRunning || tunnelStarting || tunnelStopping || autoPausedForWifi

    fun closeVkCalls() {
        scope.launch {
            isClosingVkCalls = true
            runCatching {
                val intent = Intent(context, TunnelService::class.java).apply {
                    action = CsqttConstants.General.ACTION_CLOSE_VK_CALLS
                }
                context.startService(intent)
                context.showRaisedToast("Закрываем звонки VK…", Toast.LENGTH_SHORT)
            }.onFailure { error ->
                TunnelManager.updateLog(
                    "vk_calls_close_error",
                    "Не удалось отправить запрос на закрытие звонков VK: ${error.message ?: error.javaClass.simpleName}",
                    90,
                    true,
                )
            }
            delay(2000)
            isClosingVkCalls = false
        }
    }

    val parsedLink = remember(csqttLink) { parseCsqttLink(csqttLink) }
    val linkHashes = parsedLink?.hashes.orEmpty()
    val linkTokenActive = parsedLink?.vkAccessToken?.isNotEmpty() == true
    val rawManualHashes = remember(vkHashes) {
        vkHashes.split(Regex("[,\\s\\n]+"))
            .map(String::trim)
            .filter { it.length >= 16 }
            .distinct()
    }
    val activeManualHashes = remember(vkHashes, vkHashCheckResultsJson) {
        VkHashValidationCodec.active(
            rawManualHashes,
            VkHashValidationCodec.decode(vkHashCheckResultsJson),
        )
    }
    // Хеши, помеченные `Invalid`, не возвращаются в оборот: иначе мёртвый звонок
    // снова требует капчу. Если невалидны все — список пуст, и запуск блокирует
    // `ConnectionStartPolicy`.
    val manualHashes = activeManualHashes
    val accountAutoJsMode = vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    val autoHashMode = accountAutoJsMode || (
        savedHashMode != null && savedHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    )
    val vkTokenActive = savedVkAccessToken?.isNotBlank() == true
    val hashesReady = hashSettingsLoaded && ConnectionStartPolicy.hasReadyVkCredentials(
        linkHashCount = linkHashes.size,
        linkTokenPresent = linkTokenActive,
        autoHashMode = autoHashMode,
        storedTokenPresent = vkTokenActive,
        manualHashCount = manualHashes.size,
    )
    val startBlocker = ConnectionStartPolicy.blocker(
        linkValid = parsedLink != null,
        hashesReady = hashesReady,
    )
    val isValid = startBlocker == null

    fun startIntent(source: ConnectionSource, generationId: Long, salt: String): Intent =
        Intent(context, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", source.peer)
            putExtra("vk_hashes", source.hashes)
            putExtra("vk_hashes_from_link", source.hashesFromLink)
            putExtra("vk_access_token", source.vkAccessToken)
            putExtra("vk_token_from_link", source.tokenFromLink)
            putExtra("workers_per_hash", workersPerHash)
            putExtra("port", 0)
            putExtra("sni", sni)
            putExtra("connection_password", source.password)
            putExtra("vk_auth_mode", vkAuthMode)
            putExtra("captcha_mode", captchaMode)
            putExtra("captcha_solve_method", captchaSolveMethod)
            putExtra("fingerprint", activeFingerprint)
            putExtra("client_ids", activeClientIds)
            putExtra("obfs_mode", CsqttConstants.Tunnel.DEFAULT_OBFS_MODE)
            putExtra("turn_transport", turnTransport)
            putExtra("srtp_mode", srtpMode)
            putExtra("custom_turn_ips", customTurnIps)
            putExtra("generation_id", generationId)
            putExtra("session_salt", salt)
        }

    fun startTunnelService() {
        scope.launch {
            val source = resolveConnectionSource(settingsStore) ?: run {
                context.showRaisedToast("Заполните настройки подключения", Toast.LENGTH_SHORT)
                return@launch
            }
            TunnelManager.isLoggingEnabled = settingsStore.participantShowLogs.first()
            val nextGen = System.currentTimeMillis() / 1000L
            val salt = newTunnelSessionId()
            val intent = startIntent(source, nextGen, salt)
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    TunnelManager.updateLog(
                        "foreground_request_error",
                        "Android заблокировал запуск VPN: ${error.message ?: error.javaClass.simpleName}",
                        99,
                        true,
                    )
                    Toast.makeText(
                        context,
                        "Android заблокировал запуск VPN. Проверьте ограничения батареи приложения.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) {
                startTunnelService()
            } else {
                context.showRaisedToast("VPN-разрешение не выдано", Toast.LENGTH_SHORT)
            }
        }
    }

    fun toggleTunnel() {
        if (TunnelManager.stopping.value) return
        if (com.csqtt.client.tunnelPowerIsOn(TunnelManager.running.value, TunnelManager.starting.value, TunnelManager.autoPausedForWifi.value)) {
            pendingStartAfterVpnPermission = false
            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
            return
        }
        if (!isValid) {
            val blocker = requireNotNull(startBlocker)
            TunnelManager.updateLog(
                "connection_configuration_${blocker.name}",
                "[ПОДКЛЮЧЕНИЕ] Запуск не начат: ${blocker.message}",
                99,
                true,
            )
            onInvalidConfiguration()
            return
        }
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    CsqttScreen {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp)
                .offset(y = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TunnelConnectionPanel(
                tunnelRunning = tunnelRunning,
                tunnelStarting = tunnelStarting,
                tunnelStopping = tunnelStopping,
                autoPausedForWifi = autoPausedForWifi,
                cooldownActive = cooldownActive,
                configurationValid = isValid,
                linkMissing = csqttLink.isBlank(),
                onToggleTunnel = { toggleTunnel() },
                showCloseVkCalls = showCloseVkCalls,
                isClosingVkCalls = isClosingVkCalls,
                onRequestCloseVkCalls = { showCloseVkCallsConfirm = true },
            )
        }
    }

    if (showCloseVkCallsConfirm) {
        CloseVkCallsDialog(
            isTunnelActive = isTunnelActive,
            onDismiss = { showCloseVkCallsConfirm = false },
            onConfirm = {
                showCloseVkCallsConfirm = false
                closeVkCalls()
            },
        )
    }
}

@Composable
private fun TunnelConnectionPanel(
    tunnelRunning: Boolean,
    tunnelStarting: Boolean,
    tunnelStopping: Boolean,
    autoPausedForWifi: Boolean,
    cooldownActive: Boolean,
    configurationValid: Boolean,
    linkMissing: Boolean,
    onToggleTunnel: () -> Unit,
    showCloseVkCalls: Boolean = false,
    isClosingVkCalls: Boolean = false,
    onRequestCloseVkCalls: () -> Unit = {},
) {
    val isStarting = tunnelStarting && !tunnelRunning && !tunnelStopping
    val canToggle = !tunnelStopping && (tunnelRunning || isStarting || autoPausedForWifi || !cooldownActive || !configurationValid)
    val activeVisual = tunnelRunning || isStarting || autoPausedForWifi || tunnelStopping
    val isTunnelActive = tunnelRunning || tunnelStarting || tunnelStopping || autoPausedForWifi
    val logoScale = if (activeVisual) 1.06f else 0.96f
    val statusColor = when {
        autoPausedForWifi -> MaterialTheme.colorScheme.tertiary
        tunnelStopping -> MaterialTheme.colorScheme.tertiary
        tunnelRunning -> MaterialTheme.colorScheme.primary
        isStarting -> MaterialTheme.colorScheme.primary
        cooldownActive -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        tunnelStopping -> "Остановка"
        autoPausedForWifi -> "Ожидание. Автопауза при Wi-Fi"
        tunnelRunning -> "Подключено"
        isStarting -> "Подключение"
        cooldownActive -> "Ожидание"
        else -> "Отключено"
    }

    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_c_logo),
                contentDescription = when {
                    tunnelStopping -> "Ожидание отключения VPN"
                    tunnelRunning || autoPausedForWifi -> "Остановить VPN"
                    else -> "Включить VPN"
                },
                modifier = Modifier
                    .size(172.dp)
                    .clip(CsqttShapes.Pill)
                    .clickable(
                        enabled = canToggle,
                        onClick = onToggleTunnel,
                    )
                    .scale(logoScale),
                colorFilter = if (activeVisual) {
                    null
                } else {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                },
                alpha = when {
                    activeVisual -> 1f
                    canToggle -> 0.66f
                    else -> 0.34f
                },
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center,
            )

            if (linkMissing) {
                Text(
                    text = "Вставьте ссылку csqtt:// из панели",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            TunnelUptimeText(tunnelRunning = tunnelRunning || autoPausedForWifi || tunnelStopping)
        }

        if (showCloseVkCalls) {
            CloseVkCallsAction(
                enabled = !isTunnelActive && !isClosingVkCalls,
                isClosing = isClosingVkCalls,
                isTunnelActive = isTunnelActive,
                onClick = onRequestCloseVkCalls,
            )
        }
    }
}

@Composable
private fun TunnelUptimeText(tunnelRunning: Boolean) {
    val uptimeSeconds by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    Text(
        text = formatTunnelUptime(uptimeSeconds),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (tunnelRunning) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
        },
    )
}

private fun formatTunnelUptime(seconds: Long?): String {
    val totalSeconds = seconds?.coerceAtLeast(0L) ?: 0L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, sec)
}

@Composable
private fun CloseVkCallsAction(
    enabled: Boolean,
    isClosing: Boolean,
    isTunnelActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(CsqttSizes.CompactControlHeight),
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
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CallEnd,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isClosing) "Закрываем звонки VK…" else "Закрыть звонки VK",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        if (isTunnelActive) {
            Text(
                text = "Для закрытия звонков сначала отключите VPN",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
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

@Preview(showBackground = true)
@Composable
private fun CloseVkCallsActionPreview() {
    CloseVkCallsAction(
        enabled = true,
        isClosing = false,
        isTunnelActive = false,
        onClick = {},
    )
}
