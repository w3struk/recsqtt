package com.csqtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.ProfileRegistryPolicy
import com.csqtt.client.ProfileSummary
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes

/**
 * Список профилей: активный маркер, имя, сводка и удаление. Переименования
 * нет — имя меняется только в полноэкранном редакторе. Профиль без ссылки
 * показан как «Не настроен» и не выбирается, но удалить его можно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileListSheet(
    profiles: List<ProfileSummary>,
    activeId: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    effectiveActiveWorkers: Int? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteTarget by remember { mutableStateOf<ProfileSummary?>(null) }
    val lastProfile = profiles.size <= 1
    val linkedIds = remember(profiles) { profiles.filter { it.hasLink }.map { it.id }.toSet() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Профили подключения",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(CsqttSizes.MinimumTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profiles.forEach { profile ->
                    val isActive = profile.id == activeId
                    val isEnabled = profile.hasLink

                    Surface(
                        shape = CsqttShapes.Card,
                        color = when {
                            isActive -> MaterialTheme.colorScheme.primaryContainer
                            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        contentColor = if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        border = if (isActive) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isActive,
                                    enabled = isEnabled,
                                    onClick = { onSelect(profile.id) },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RadioButton(
                                selected = isActive,
                                onClick = null,
                                enabled = isEnabled,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (!isEnabled) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        } else if (isActive) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isActive) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = CsqttShapes.Pill,
                                            color = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ) {
                                            Text(
                                                text = "АКТИВЕН",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (isActive && effectiveActiveWorkers != null) {
                                        profile.summaryWithWorkers(effectiveActiveWorkers)
                                    } else {
                                        profile.summary
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!isEnabled) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    } else if (isActive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { deleteTarget = profile },
                                enabled = !lastProfile,
                                modifier = Modifier.size(CsqttSizes.MinimumTouchTarget),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Удалить профиль ${profile.name}",
                                    modifier = Modifier.size(CsqttSizes.IconMedium),
                                    tint = if (lastProfile) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (lastProfile) {
                Text(
                    text = "Последний профиль удалить нельзя.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
            ) {
                Text(
                    text = "+ Добавить профиль",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }

    deleteTarget?.let { target ->
        val isActive = target.id == activeId
        val fallbackId = if (isActive) {
            ProfileRegistryPolicy.activeAfterDelete(
                ids = profiles.map { it.id },
                deleted = target.id,
                active = activeId,
                linked = linkedIds,
            )
        } else {
            null
        }
        val fallbackName = fallbackId?.let { id ->
            profiles.firstOrNull { it.id == id }?.name ?: "Профиль $id"
        }
        val fallbackHasLink = fallbackId != null && fallbackId in linkedIds
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить профиль?") },
            text = {
                Text(
                    when {
                        !isActive ->
                            "Профиль «${target.name}» будет удалён. Текущее подключение не изменится."

                        fallbackHasLink ->
                            "Профиль «${target.name}» будет удалён. Активным станет «$fallbackName», и туннель переподключится."

                        else ->
                            "Профиль «${target.name}» будет удалён. Активным станет «$fallbackName», но он не настроен — подключение нужно будет настроить заново."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDelete(target.id)
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Отмена")
                }
            },
        )
    }
}
