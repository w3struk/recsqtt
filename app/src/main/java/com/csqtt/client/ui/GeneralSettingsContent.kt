package com.csqtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.components.CsqttSettingRow

/**
 * Глобальные настройки приложения. Профиль, отпечаток и TURN-подсеть живут
 * в полноэкранном редакторе профиля и здесь не показываются.
 */
@Composable
internal fun GeneralSettingsContent(
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    autoPauseOnWifi: Boolean,
    onAutoPauseOnWifiChange: (Boolean) -> Unit,
    participantShowLogs: Boolean,
    onParticipantShowLogsChange: (Boolean) -> Unit,
    onShowAutoPauseInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Основные параметры",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )

        CompactDropdownSetting(
            title = "Оформление",
            selectedKey = currentTheme,
            options = listOf(
                CsqttConstants.Theme.MODE_SYSTEM to "Система",
                CsqttConstants.Theme.MODE_LIGHT to "Светлая",
                CsqttConstants.Theme.MODE_DARK to "Тёмная",
            ),
            enabled = true,
            onSelected = onThemeChange,
        )

        CsqttSettingRow(
            title = "Автопауза при Wi-Fi",
            checked = autoPauseOnWifi,
            onCheckedChange = onAutoPauseOnWifiChange,
            onInfoClick = onShowAutoPauseInfo,
            infoContentDescription = "Как работает автопауза при Wi-Fi",
        )

        CsqttSettingRow(
            title = "Показывать логи",
            checked = participantShowLogs,
            onCheckedChange = onParticipantShowLogsChange,
        )
    }
}
