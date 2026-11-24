package com.csqtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.ProfileRegistryPolicy
import com.csqtt.client.ProfileSummary
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes

/**
 * Инлайн-список профилей подключения: выбор активного профиля, редактор настроек
 * каждого профиля через кнопку Tune и удаление. Профиль без ссылки показан как
 * «Не настроен» и не выбирается, но редактирование и удаление для него доступны.
 * Если профилей нет, отображается пустое состояние с подсказкой и кнопкой добавления.
 */
@Composable
internal fun ProfileListSection(
    profiles: List<ProfileSummary>,
    activeId: Int,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    effectiveActiveWorkers: Int? = null,
) {
    var deleteTarget by remember { mutableStateOf<ProfileSummary?>(null) }
    val linkedIds = remember(profiles) { profiles.filter { it.hasLink }.map { it.id }.toSet() }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.22f

    AppSectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Профили подключения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    )
                    .drawBehind {
                        val strokeWidth = 1.5.dp.toPx()
                        val dividerColor = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.08f)
                        else Color(0xFF111A33).copy(alpha = 0.08f)
                        drawRoundRect(
                            color = dividerColor,
                            style = Stroke(
                                width = strokeWidth,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                            ),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                        )
                    }
                    .padding(vertical = 18.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Пока нет профилей",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profiles.forEach { profile ->
                    val isActive = profile.id == activeId
                    val isEnabled = profile.hasLink

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isActive -> MaterialTheme.colorScheme.primaryContainer
                            !isEnabled -> if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.40f)
                            else -> if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
                        },
                        contentColor = if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        border = if (isActive) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
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
                        ) {
                            RadioButton(
                                selected = isActive,
                                onClick = null,
                                enabled = isEnabled,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = if (!isEnabled) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    disabledSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                                    disabledUnselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                ),
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp, end = 6.dp),
                            ) {
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
                                )
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

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                IconButton(
                                    onClick = { onEdit(profile.id) },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = "Настройки профиля ${profile.name}",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                IconButton(
                                    onClick = { deleteTarget = profile },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Удалить профиль ${profile.name}",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .height(CsqttSizes.ControlHeight),
            shape = CsqttShapes.Pill,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Добавить",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
        val fallbackName = fallbackId?.takeIf { it >= 0 }?.let { id ->
            profiles.firstOrNull { it.id == id }?.name ?: "Профиль $id"
        }
        val fallbackHasLink = fallbackId != null && fallbackId >= 0 && fallbackId in linkedIds
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить профиль?") },
            text = {
                Text(
                    when {
                        !isActive ->
                            "Профиль «${target.name}» будет удалён. Текущее подключение не изменится."

                        fallbackId == null || fallbackId == ProfileRegistryPolicy.NO_PROFILE || fallbackId < 0 ->
                            "Профиль «${target.name}» будет удалён, и туннель остановится."

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
