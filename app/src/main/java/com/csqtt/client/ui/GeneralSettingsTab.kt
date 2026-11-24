package com.csqtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.components.CsqttScreen

@Composable
internal fun GeneralSettingsTab(
    settingsStore: SettingsStore,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    autoPauseOnWifi: Boolean,
    onAutoPauseOnWifiChange: (Boolean) -> Unit,
    participantShowLogs: Boolean,
    onParticipantShowLogsChange: (Boolean) -> Unit,
    savedExcluded: String,
    savedWhitelist: String,
    showSystemApps: Boolean,
    isWhitelist: Boolean,
    serverRoutingActive: Boolean,
) {
    var showAutoPauseInfo by rememberSaveable { mutableStateOf(false) }

    CsqttScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppSectionCard {
                GeneralSettingsContent(
                    currentTheme = currentTheme,
                    onThemeChange = onThemeChange,
                    autoPauseOnWifi = autoPauseOnWifi,
                    onAutoPauseOnWifiChange = onAutoPauseOnWifiChange,
                    participantShowLogs = participantShowLogs,
                    onParticipantShowLogsChange = onParticipantShowLogsChange,
                    onShowAutoPauseInfo = { showAutoPauseInfo = true },
                )
            }

            AppRoutingSection(
                settingsStore = settingsStore,
                savedExcluded = savedExcluded,
                savedWhitelist = savedWhitelist,
                showSystemApps = showSystemApps,
                isWhitelist = isWhitelist,
                serverRoutingActive = serverRoutingActive,
            )

            CsqttActionsCard(
                settingsStore = settingsStore,
            )
        }
    }

    if (showAutoPauseInfo) {
        WifiAutoPauseInfoDialog(onDismiss = { showAutoPauseInfo = false })
    }
}
