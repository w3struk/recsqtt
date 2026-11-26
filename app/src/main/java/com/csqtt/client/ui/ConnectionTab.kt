package com.csqtt.client.ui

import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionSource
import com.csqtt.client.ConnectionStartBlocker
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.CsqttConstants
import com.csqtt.client.PhysicalNetworkPauseReason
import com.csqtt.client.R
import com.csqtt.client.ProfileSettingsStore
import com.csqtt.client.ProfileSummary
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelConnectionStatus
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.newTunnelSessionId
import com.csqtt.client.resolveConnectionSource
import com.csqtt.client.showRaisedToast
import com.csqtt.client.showsSessionTimer
import com.csqtt.client.tunnelConnectionStatus
import com.csqtt.client.tunnelConnectionStatusText
import com.csqtt.client.ui.components.CsqttScreen
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttTheme
import com.csqtt.client.ui.utils.parseCsqttLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun ConnectionTab(
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    activeProfile: Int,
    profiles: List<ProfileSummary>,
    onSelectProfile: (Int) -> Unit,
    onEditProfile: (Int) -> Unit,
    onDeleteProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onInvalidConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
    effectiveActiveWorkers: Int? = null,
    liveSessionProfileId: () -> Int? = { null },
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelStarting by TunnelManager.starting.collectAsStateWithLifecycle()
    val tunnelStopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val autoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val networkPauseReason by TunnelManager.networkPauseReason.collectAsStateWithLifecycle()
    val tunnelConnected by TunnelManager.connected.collectAsStateWithLifecycle()
    val tunnelStalled by TunnelManager.connectionStalled.collectAsStateWithLifecycle()

    val hasActiveProfile = activeProfile >= 0 && profiles.any { it.id == activeProfile }
    val emptyProfiles = profiles.isEmpty() || !hasActiveProfile

    val csqttLink = tunnelAuthSettings.csqttLink
    val vkHashes by settingsStore.profile.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val vkHashCheckResultsJson by settingsStore.profile.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val workersPerHash by settingsStore.profile.workersPerHash.collectAsStateWithLifecycle(initialValue = 18)
    val sni by settingsStore.profile.sni.collectAsStateWithLifecycle(initialValue = "")
    val turnTransport by settingsStore.profile.turnTransport.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT)
    val customTurnIps by settingsStore.profile.customTurnIps.collectAsStateWithLifecycle(initialValue = "")
    val vkAuthMode by settingsStore.profile.vkAuthMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.VkAuth.MODE_CALLS)
    val captchaMode by settingsStore.profile.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
    val captchaSolveMethod by settingsStore.profile.captchaSolveMethod.collectAsStateWithLifecycle(initialValue = "auto")
    val activeFingerprint by settingsStore.profile.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val activeClientIds by settingsStore.profile.activeClientIds.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    val savedHashMode by settingsStore.profile.vkHashMode.collectAsStateWithLifecycle(initialValue = ProfileSettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.profile.vkAccessToken.collectAsStateWithLifecycle(initialValue = ProfileSettingsStore.cachedVkAccessToken)

    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }

    val parsedLink = remember(csqttLink) { parseCsqttLink(csqttLink) }
    val linkHashes = parsedLink?.hashes.orEmpty()
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
        autoHashMode = autoHashMode,
        storedTokenPresent = vkTokenActive,
        manualHashCount = manualHashes.size,
    )
    val startBlocker = ConnectionStartPolicy.blocker(
        linkValid = parsedLink != null,
        hashesReady = hashesReady,
    )
    val isValid = !emptyProfiles && startBlocker == null

    fun startIntent(source: ConnectionSource, generationId: Long, salt: String): Intent =
        Intent(context, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", source.peer)
            putExtra("vk_hashes", source.hashes)
            putExtra("vk_hashes_from_link", source.hashesFromLink)
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
        if (emptyProfiles) return
        if (TunnelManager.stopping.value) return
        if (com.csqtt.client.tunnelPowerIsOn(TunnelManager.running.value, TunnelManager.starting.value, TunnelManager.autoPausedForWifi.value)) {
            pendingStartAfterVpnPermission = false
            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
            return
        }
        // Защёлка после остановки: окно, в которое повторный тап не поднимает
        // сессию заново. Это защита от двойного тапа, а не состояние интерфейса —
        // статус и вид логотипа в это время уже «Отключено», без «Ожидания».
        // Стоит после ветки остановки: «стоп» в этом окне обязан срабатывать.
        if (TunnelManager.startCooldownActive()) return
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
        Column(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TunnelConnectionPanel(
                tunnelRunning = tunnelRunning,
                tunnelStarting = tunnelStarting,
                tunnelStopping = tunnelStopping,
                autoPausedForWifi = autoPausedForWifi,
                networkPauseReason = networkPauseReason,
                tunnelConnected = tunnelConnected,
                tunnelStalled = tunnelStalled,
                linkMissing = csqttLink.isBlank(),
                emptyProfiles = emptyProfiles,
                onToggleTunnel = { toggleTunnel() },
            )

            ProfileListSection(
                profiles = profiles,
                activeId = activeProfile,
                onSelect = onSelectProfile,
                onEdit = onEditProfile,
                onDelete = onDeleteProfile,
                onAdd = onAddProfile,
                effectiveActiveWorkers = effectiveActiveWorkers,
                liveSessionProfileId = liveSessionProfileId,
            )
        }
    }
}

@Composable
private fun TunnelConnectionPanel(
    tunnelRunning: Boolean,
    tunnelStarting: Boolean,
    tunnelStopping: Boolean,
    autoPausedForWifi: Boolean,
    networkPauseReason: PhysicalNetworkPauseReason?,
    tunnelConnected: Boolean,
    tunnelStalled: Boolean,
    linkMissing: Boolean,
    emptyProfiles: Boolean,
    onToggleTunnel: () -> Unit,
) {
    val isStarting = tunnelStarting && !tunnelRunning && !tunnelStopping
    // Кликабельность — только «есть профиль» и «не идёт остановка». Защёлка после
    // остановки сюда не входит: она гасит только повторный старт внутри 300 мс
    // в `toggleTunnel`, иначе кнопка становилась бы недоступной в «Отключено».
    val canToggle = !emptyProfiles && !tunnelStopping
    val uptimeSeconds by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    val status = tunnelConnectionStatus(
        emptyProfiles = emptyProfiles,
        stopping = tunnelStopping,
        wifiPaused = autoPausedForWifi,
        networkPauseReason = networkPauseReason,
        connected = tunnelConnected,
        stalled = tunnelStalled,
        starting = isStarting,
        running = tunnelRunning,
    )
    val waiting = status == TunnelConnectionStatus.WAITING_WIFI ||
        status == TunnelConnectionStatus.WAITING_NETWORK
    val activeVisual = !emptyProfiles && (tunnelRunning || isStarting || waiting || tunnelStopping)
    val logoScale = if (activeVisual) 1.06f else 0.96f
    val statusColor = when (status) {
        TunnelConnectionStatus.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        TunnelConnectionStatus.STOPPING,
        TunnelConnectionStatus.WAITING_WIFI,
        TunnelConnectionStatus.WAITING_NETWORK,
        -> MaterialTheme.colorScheme.tertiary
        TunnelConnectionStatus.CONNECTED -> CsqttTheme.extendedColors.terminalSuccess
        TunnelConnectionStatus.NO_CONNECTION -> CsqttTheme.extendedColors.terminalError
        TunnelConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
    }
    val statusText = tunnelConnectionStatusText(status, networkPauseReason)
    // Таймер — длительность соединения: до первого READY его нет, а в ожидании
    // и на остановке остаётся замороженное значение, если сессия успела начаться.
    val showUptime = !emptyProfiles && showsSessionTimer(status, uptimeSeconds)
    val timerSlotHeight = with(LocalDensity.current) {
        maxOf(MaterialTheme.typography.labelLarge.lineHeight.toDp(), TimerIconSize)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(timerSlotHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (showUptime) {
                    TunnelUptimeRow(active = status == TunnelConnectionStatus.CONNECTED)
                }
            }

            Image(
                painter = painterResource(id = R.drawable.ic_c_logo),
                contentDescription = when {
                    emptyProfiles -> "VPN отключён: нет профиля"
                    tunnelStopping -> "Ожидание отключения VPN"
                    tunnelRunning || waiting -> "Остановить VPN"
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
                // Три достижимых вида: активный (1f), пустые профили (0.60f) и
                // доступный простой (0.66f). Четвёртая ветка 0.34f была недостижима:
                // `canToggle=false` без пустых профилей бывает только при `stopping`,
                // а он уже активен.
                alpha = when {
                    activeVisual -> 1f
                    emptyProfiles -> 0.60f
                    else -> 0.66f
                },
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center,
            )

            if (emptyProfiles) {
                Text(
                    text = "Добавьте профиль, чтобы подключиться",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else if (linkMissing) {
                Text(
                    text = "Вставьте ссылку csqtt:// из панели",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else if (status == TunnelConnectionStatus.NO_CONNECTION) {
                Text(
                    text = "Проверьте подключение к сети",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TimerIconSize = 16.dp
private val TimerIconGap = 6.dp

@Composable
private fun TunnelUptimeRow(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val uptimeSeconds by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    val timerColor = if (active) {
        CsqttTheme.extendedColors.terminalSuccess
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimerIconGap),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(TimerIconSize),
            tint = timerColor,
        )
        Text(
            text = formatTunnelUptime(uptimeSeconds),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = timerColor,
        )
    }
}

private fun formatTunnelUptime(seconds: Long?): String {
    val totalSeconds = seconds?.coerceAtLeast(0L) ?: 0L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, sec)
}
