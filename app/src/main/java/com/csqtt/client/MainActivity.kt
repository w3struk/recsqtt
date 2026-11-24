package com.csqtt.client

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import com.csqtt.client.ui.AppUpdateDialog
import com.csqtt.client.ui.ConnectionTab
import com.csqtt.client.ui.GeneralSettingsTab
import com.csqtt.client.ui.LogsTab
import com.csqtt.client.ui.ProfileEditorScreen
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.design.CsqttMotion
import com.csqtt.client.ui.design.CsqttShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val appSettingsStore by lazy { SettingsStore(applicationContext) }
    private var renderUi by mutableStateOf(false)
    private var batteryPromptInFlight = false
    private var pendingExternalImport by mutableStateOf<ProfileImportPrefill?>(null)

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryPromptInFlight = false
        lifecycleScope.launch {
            appSettingsStore.markBatteryOptimizationPromptHandled()
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
    }

    companion object {
        @Volatile
        var activeActivities = 0
        val isForeground: Boolean
            get() = activeActivities > 0
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        renderUi = true
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        renderUi = false
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val discardStaleUiState = UpdateLaunchGuard.consumeStaleSavedState(this)
        super.onCreate(if (discardStaleUiState) null else savedInstanceState)

        lifecycleScope.launch {
            appSettingsStore.profile.migrateLinkCredentialsIfNeeded()
        }

        // Восстановление процесса не должно повторно импортировать тот же ACTION_VIEW:
        // setIntent обновляет только локальный Intent, а система пришлёт исходный.
        if (savedInstanceState == null) importCsqttLink(intent)

        TunnelManager.initObservers(this)

        enableEdgeToEdge()

        checkAndRequestNotifications()

        setContent {
            val uiStateHolder = rememberSaveableStateHolder()
            val settingsStore = remember { appSettingsStore }
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.Theme.MODE_SYSTEM)
            val scope = rememberCoroutineScope()

            val baseDensity = LocalDensity.current
            val fixedDensity = remember(baseDensity.density) { Density(baseDensity.density, 1f) }

            // Корневая поверхность монтируется безусловно: окно Activity и его
            // a11y-узел живут всё время жизни Activity, а тяжёлый экран снимается
            // с композиции по renderUi, пока приложение в фоне.
            CompositionLocalProvider(LocalDensity provides fixedDensity) {
                CSQTTTheme(themeMode = themeMode) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { isTraversalGroup = true },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (renderUi) {
                            uiStateHolder.SaveableStateProvider("main_ui") {
                                MainScreen(
                                    settingsStore = settingsStore,
                                    themeMode = themeMode,
                                    onThemeChange = { mode ->
                                        scope.launch {
                                            settingsStore.saveThemeMode(mode)
                                        }
                                    },
                                    pendingExternalImport = pendingExternalImport,
                                    onExternalImportHandled = { pendingExternalImport = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importCsqttLink(intent)
    }

    private fun importCsqttLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        // Обработанный intent поглощаем сразу: повторное создание Activity не
        // должно запускать импорт заново или открывать редактор.
        setIntent(Intent())
        val decision = VkLinkImportPolicy.decide(raw)
        if (decision == null) {
            showRaisedToast("Неверная ссылка reCSQTT", Toast.LENGTH_SHORT)
            return
        }
        lifecycleScope.launch {
            val profiles = try {
                appSettingsStore.profile.profiles.first()
            } catch (_: IOException) {
                emptyList()
            }
            when (ProfileImportPolicy.route(profiles)) {
                ExternalImportRoute.AutoSaveStartProfile -> {
                    val saved: Boolean? = try {
                        appSettingsStore.profile.importExternalLinkIntoStartProfile(raw)
                    } catch (_: IOException) {
                        null
                    }
                    when (saved) {
                        true -> showRaisedToast(
                            LinkSaveNotice.configToast(LinkSaveOutcome.SAVED),
                            Toast.LENGTH_SHORT,
                        )

                        false -> {
                            // Профиль 0 заполнили параллельно — ведём в новый редактор.
                            pendingExternalImport = ProfileImportPolicy.prefill(decision)
                        }

                        null -> {
                            // Сбой записи — не выдаём его за занятый профиль 0.
                            showRaisedToast(
                                "Не удалось сохранить ссылку: ошибка хранилища",
                                Toast.LENGTH_LONG,
                            )
                            pendingExternalImport = ProfileImportPolicy.prefill(decision)
                        }
                    }
                }

                ExternalImportRoute.OpenNewProfileEditor -> {
                    pendingExternalImport = ProfileImportPolicy.prefill(decision)
                }
            }
        }
    }

    private fun checkAndRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkAndRequestBattery() {
        if (batteryPromptInFlight) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        lifecycleScope.launch {
            val shouldRequest = shouldRequestBatteryOptimizationExemption(
                promptHandled = appSettingsStore.isBatteryOptimizationPromptHandled(),
                alreadyExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            )
            if (!shouldRequest) {
                if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    appSettingsStore.markBatteryOptimizationPromptHandled()
                }
                return@launch
            }

            batteryPromptInFlight = true
            val launched = runCatching {
                batteryLauncher.launch(
                    Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:$packageName".toUri(),
                    ),
                )
                true
            }.getOrDefault(false)
            if (!launched) {
                batteryPromptInFlight = false
                appSettingsStore.markBatteryOptimizationPromptHandled()
            }
        }
    }
}

private data class NavItem(
    val id: Int,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private const val LOGS_TAB_ID = 2
private val navItems = listOf(
    NavItem(0, R.string.nav_connection, Icons.Filled.PowerSettingsNew, Icons.Filled.PowerSettingsNew),
    NavItem(LOGS_TAB_ID, R.string.nav_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem(3, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

internal fun navigationSwipeProgress(totalDrag: Float, containerWidth: Int): Float =
    if (containerWidth <= 0) 0f else (abs(totalDrag) / (containerWidth.toFloat() * 0.25f)).coerceIn(0f, 1f)

internal fun shouldCommitNavigationSwipe(progress: Float): Boolean = progress > 0.4f

internal fun resolveActiveNavIds(participantShowLogs: Boolean): List<Int> =
    if (participantShowLogs) listOf(0, LOGS_TAB_ID, 3) else listOf(0, 3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = CsqttConstants.Theme.MODE_SYSTEM,
    onThemeChange: (String) -> Unit = {},
    pendingExternalImport: ProfileImportPrefill? = null,
    onExternalImportHandled: () -> Unit = {},
) {
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelAutoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val serverAssignedWorkers by TunnelManager.serverAssignedWorkers.collectAsStateWithLifecycle()
    val effectiveActiveWorkers = serverAssignedWorkers.takeIf { tunnelRunning && it in 3..100 }
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val activeProfile by settingsStore.profile.activeProfile.collectAsStateWithLifecycle(
        initialValue = ProfileRegistryPolicy.NO_PROFILE,
    )
    val profileSummaries by settingsStore.profile.profiles.collectAsStateWithLifecycle(
        initialValue = emptyList<ProfileSummary>(),
    )
    val tunnelAuthSettings by settingsStore.profile.tunnelAuthSnapshot.collectAsStateWithLifecycle(
        initialValue = TunnelAuthSnapshot()
    )
    val participantShowLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(initialValue = false)
    val autoPauseOnWifi by settingsStore.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val excludedApps by settingsStore.excludedApps.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedExcludedApps ?: ""
    )
    val whitelistApps by settingsStore.whitelistApps.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedWhitelistApps ?: ""
    )
    val showSystemApps by settingsStore.showSystemApps.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedShowSystemApps ?: false
    )
    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedIsWhitelist ?: false
    )
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorIsNew by rememberSaveable { mutableStateOf(false) }
    var openEditorId by rememberSaveable { mutableStateOf<Int?>(null) }
    val dragTargetIndex = remember { mutableIntStateOf(-1) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    var wasRunning by remember { mutableStateOf(tunnelRunning) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navToolbarHeight = 66.dp
    val navToolbarBottomMargin = 10.dp
    val navContentGap = 5.dp
    val navOverlayReserve = safeBottomInset + navToolbarBottomMargin + navToolbarHeight + navContentGap
    val navigationDragSlop = with(density) { 6.dp.toPx() }

    val serverRoutingActive by TunnelManager.serverRoutingActive.collectAsStateWithLifecycle()

    val activeNavItems = remember(participantShowLogs) {
        val allowedIds = resolveActiveNavIds(participantShowLogs)
        navItems.filter { it.id in allowedIds }
    }
    val tabStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(activeNavItems) {
        selectedTab = resolveSelectedTab(selectedTab, activeNavItems.map { it.id })
    }

    LaunchedEffect(tunnelRunning, tunnelAutoPausedForWifi) {
        if (wasRunning && !tunnelRunning && !tunnelAutoPausedForWifi) {
            TunnelManager.startCooldown(CsqttConstants.Timeouts.VPN_PERMISSION_COOLDOWN_MS)
        }
        wasRunning = tunnelRunning
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == LOGS_TAB_ID) TunnelManager.clearUnreadErrors()
    }

    LaunchedEffect(pendingExternalImport) {
        if (pendingExternalImport != null) {
            // Внешний импорт важнее открытого листа/редактора: незаписанное
            // состояние отбрасывается, показываем новый профиль с префиллом.
            editorOpen = true
            editorIsNew = true
            openEditorId = null
        }
    }

    val editorTarget: ProfileEditorTarget? = when {
        pendingExternalImport != null -> ProfileEditorTarget.New
        editorOpen && editorIsNew -> ProfileEditorTarget.New
        editorOpen && (openEditorId ?: -1) >= 0 -> ProfileEditorTarget.Existing(openEditorId!!)
        editorOpen -> ProfileEditorTarget.New
        else -> null
    }

    LaunchedEffect(updateCheckIntervalHours) {
        if (updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        suspend fun runUpdateCheck(reason: String) {
            val checkedAt = System.currentTimeMillis()
            val release = fetchLatestReleaseInfo(currentVersion)
            settingsStore.saveUpdateState(
                lastCheckAt = checkedAt,
                latestVersion = release?.versionTag ?: "",
                error = if (release == null) "Не удалось проверить" else ""
            )

            if (release == null) {
                Log.w("CSQTT", "[WARN] Update check: no release info, local=$currentVersion reason=$reason")
                return
            }

            val hasUpdate = isNewerVersion(currentVersion, release.versionTag)
            val postponeVer = settingsStore.updatePostponeVersion.first()
            val postponeUntil = settingsStore.updatePostponeUntil.first()
            val isPostponed = postponeVer == release.versionTag && checkedAt < postponeUntil
            Log.i(
                "CSQTT",
                "Update check: local=$currentVersion remote=${release.versionTag} newer=$hasUpdate postponed=$isPostponed reason=$reason"
            )

            if (hasUpdate && !isPostponed) {
                settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                pendingRelease = release
            }
        }

        runUpdateCheck("startup")

        while (isActive) {
            val now = System.currentTimeMillis()
            val lastCheck = settingsStore.updateLastCheckAt.first()
            val nextCheckAt = lastCheck + intervalMillis
            val waitMs = (nextCheckAt - now).coerceAtLeast(intervalMillis)
            delay(waitMs)
            if (isActive) {
                runUpdateCheck("periodic")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .pointerInput(selectedTab, participantShowLogs) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                            },
                            onDragCancel = {
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                            },
                            onDragEnd = {
                                if (dragTargetIndex.intValue in activeNavItems.indices && shouldCommitNavigationSwipe(dragProgress.floatValue)) {
                                    selectedTab = activeNavItems[dragTargetIndex.intValue].id
                                    if (selectedTab == LOGS_TAB_ID) TunnelManager.clearUnreadErrors()
                                }
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < navigationDragSlop) {
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                                return@detectHorizontalDragGestures
                            }

                            val currentActiveIndex = activeNavItems.indexOfFirst { it.id == selectedTab }
                            val candidate = if (totalDrag < 0f) currentActiveIndex + 1 else currentActiveIndex - 1
                            if (candidate !in activeNavItems.indices) {
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                                return@detectHorizontalDragGestures
                            }

                            dragTargetIndex.intValue = candidate
                            dragProgress.floatValue = navigationSwipeProgress(totalDrag, size.width)
                        }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = navOverlayReserve),
                    ) {
                        key(selectedTab) {
                            val tab = selectedTab
                            val tabStateKey: Any = when (tab) {
                                0 -> "connection:${tunnelAuthSettings.profile}"
                                else -> tab
                            }
                            tabStateHolder.SaveableStateProvider(tabStateKey) {
                                when (tab) {
                                0 -> if (tunnelAuthSettings.isLoaded) {
                                    ConnectionTab(
                                        settingsStore = settingsStore,
                                        tunnelAuthSettings = tunnelAuthSettings,
                                        activeProfile = activeProfile,
                                        profiles = profileSummaries,
                                        effectiveActiveWorkers = effectiveActiveWorkers,
                                        liveSessionProfileId = {
                                            if (TunnelManager.wantsTunnel()) {
                                                TunnelManager.getCurrentParams()?.profile?.takeIf { it >= 0 }
                                            } else {
                                                null
                                            }
                                        },
                                        onSelectProfile = { id ->
                                            scope.launch {
                                                try {
                                                    settingsStore.profile.saveActiveProfile(id)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: IOException) {
                                                    context.showRaisedToast(
                                                        "Не удалось переключить профиль: ошибка хранилища",
                                                        Toast.LENGTH_LONG,
                                                    )
                                                }
                                            }
                                        },
                                        onEditProfile = { id ->
                                            editorOpen = true
                                            editorIsNew = false
                                            openEditorId = id
                                        },
                                        onDeleteProfile = { id ->
                                            scope.launch {
                                                // Звонки закрываются до удаления: deleteProfile чистит их реестр.
                                                val token = runCatching { settingsStore.profile.vkAccessToken(id) }
                                                    .getOrDefault("")
                                                if (token.isNotBlank()) {
                                                    runCatching { VkAutoCallsManager.closeAll(context, token, id) }
                                                }
                                                val sessionProfile = TunnelManager.getCurrentParams()?.profile
                                                val deleted = try {
                                                    settingsStore.profile.deleteProfile(id)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: IOException) {
                                                    context.showRaisedToast(
                                                        "Не удалось удалить профиль: ошибка хранилища",
                                                        Toast.LENGTH_LONG,
                                                    )
                                                    false
                                                }
                                                // Удалили профиль живой сессии, когда активен уже другой:
                                                // наблюдатель смены active_profile молчит, и туннель остался бы
                                                // с мёртвыми хешами. Пересобираем его на активном профиле.
                                                if (
                                                    deleted &&
                                                    sessionProfile == id &&
                                                    id != activeProfile &&
                                                    activeProfile >= 0 &&
                                                    TunnelManager.wantsTunnel()
                                                ) {
                                                    context.startService(
                                                        Intent(context, TunnelService::class.java).apply {
                                                            action = CsqttConstants.General.ACTION_RECONNECT_ACTIVE_PROFILE
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        onAddProfile = {
                                            editorOpen = true
                                            editorIsNew = true
                                            openEditorId = null
                                        },
                                        onInvalidConfiguration = {
                                            editorOpen = true
                                            if (activeProfile >= 0) {
                                                editorIsNew = false
                                                openEditorId = activeProfile
                                            } else {
                                                editorIsNew = true
                                                openEditorId = null
                                            }
                                        },
                                    )
                                } else {
                                    Spacer(modifier = Modifier.fillMaxSize())
                                }
                                2 -> LogsTab()
                                3 -> GeneralSettingsTab(
                                    settingsStore = settingsStore,
                                    currentTheme = themeMode,
                                    onThemeChange = onThemeChange,
                                    autoPauseOnWifi = autoPauseOnWifi,
                                    onAutoPauseOnWifiChange = { enabled ->
                                        scope.launch { settingsStore.saveAutoPauseOnWifi(enabled) }
                                    },
                                    participantShowLogs = participantShowLogs,
                                    onParticipantShowLogsChange = { enabled ->
                                        scope.launch { settingsStore.saveParticipantShowLogs(enabled) }
                                    },
                                    savedExcluded = excludedApps,
                                    savedWhitelist = whitelistApps,
                                    showSystemApps = showSystemApps,
                                    isWhitelist = isWhitelist,
                                    serverRoutingActive = serverRoutingActive,
                                )
                                else -> Spacer(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }

                    ProxyNavigationBar(
                        navItems = activeNavItems,
                        selectedTab = selectedTab,
                        dragTargetIndex = dragTargetIndex,
                        dragProgress = dragProgress,
                        unreadErrors = unreadErrors,
                        tunnelRunning = tunnelRunning,
                        onTabSelected = { index ->
                            if (selectedTab != index) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                selectedTab = index
                                if (index == LOGS_TAB_ID) TunnelManager.clearUnreadErrors()
                            }
                            dragTargetIndex.intValue = -1
                            dragProgress.floatValue = 0f
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
    }

    editorTarget?.let { target ->
        val closeEditor: () -> Unit = {
            editorOpen = false
            editorIsNew = false
            openEditorId = null
            if (pendingExternalImport != null) onExternalImportHandled()
        }
        ProfileEditorScreen(
            settingsStore = settingsStore,
            target = target,
            prefill = if (target is ProfileEditorTarget.New) pendingExternalImport else null,
            onDismiss = closeEditor,
            onSaved = { reconnectRequired ->
                closeEditor()
                if (
                    reconnectRequired &&
                    target is ProfileEditorTarget.Existing &&
                    target.id == activeProfile &&
                    TunnelManager.wantsTunnel()
                ) {
                    context.startService(
                        Intent(context, TunnelService::class.java).apply {
                            action = CsqttConstants.General.ACTION_RECONNECT_ACTIVE_PROFILE
                        }
                    )
                }
            },
        )
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingRelease = null
                context.showRaisedToast("Обновление отложено на 24 часа.", Toast.LENGTH_SHORT)
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onUpdate = {
                pendingRelease = null
                scope.launch {
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_UPDATE,
                        actedAt = System.currentTimeMillis()
                    )
                    openReleaseUrl(context, release.releaseUrl)
                }
            }
        )
    }
}

@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: IntState,
    dragProgress: FloatState,
    unreadErrors: Int,
    tunnelRunning: Boolean,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.onPrimary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val indicatorShape = RoundedCornerShape(22.dp)
    val shellColor = if (isDark) {
        lerp(colors.background, colors.surface, 0.86f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    }
    val shellBorder = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.42f)
    } else {
        colors.outline.copy(alpha = 0.16f)
    }
    val indicatorColor = colors.primary
    val selectedVisualIndex = remember(selectedTab, navItems) {
        navItems.indexOfFirst { it.id == selectedTab }.coerceAtLeast(0)
    }
    val indicatorIndex = remember { Animatable(selectedVisualIndex.toFloat()) }
    val isDragging = dragTargetIndex.intValue in navItems.indices
    val requestedIndicatorIndex = if (isDragging) {
        selectedVisualIndex.toFloat() +
            (dragTargetIndex.intValue - selectedVisualIndex) * dragProgress.floatValue
    } else {
        selectedVisualIndex.toFloat()
    }

    LaunchedEffect(isDragging, requestedIndicatorIndex) {
        if (isDragging) {
            indicatorIndex.snapTo(requestedIndicatorIndex)
        } else {
                indicatorIndex.animateTo(
                    targetValue = requestedIndicatorIndex,
                    animationSpec = tween(
                    durationMillis = CsqttMotion.Fast,
                    easing = CsqttMotion.StandardEasing,
                )
            )
        }
    }
    val dragVisualIndex = indicatorIndex.value

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        val trackPadding = 6.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = CsqttShapes.Card,
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = if (isDark) 0.dp else 1.dp,
            shadowElevation = if (isDark) 4.dp else 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
            ) {
                Surface(
                    shape = indicatorShape,
                    color = indicatorColor,
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(vertical = 7.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 7.dp)
                        .selectableGroup()
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)
                        val label = stringResource(item.labelRes)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CsqttShapes.Control)
                                .selectable(
                                    selected = item.id == selectedTab,
                                    role = Role.Tab,
                                    onClick = { onTabSelected(item.id) },
                                ),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = if (emphasis > 0.55f) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor
                                )
                                if (item.id == LOGS_TAB_ID && unreadErrors > 0) {
                                    Badge(
                                        containerColor = if (tunnelRunning) colors.primary else CSQTTColors.warning,
                                        contentColor = colors.onPrimary,
                                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp)
                                    ) {
                                        Text("$unreadErrors")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = iconColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openReleaseUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        context.showRaisedToast("Не удалось открыть ссылку", Toast.LENGTH_SHORT)
    }
}
