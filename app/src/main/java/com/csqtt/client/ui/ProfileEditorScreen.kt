package com.csqtt.client.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ImportedLinkCredentials
import com.csqtt.client.ProfileDraft
import com.csqtt.client.ProfileEditorPolicy
import com.csqtt.client.ProfileEditorTarget
import com.csqtt.client.ProfileImportPrefill
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkAutoCallsManager
import com.csqtt.client.VkLinkImportPolicy
import com.csqtt.client.normalizeVkModeSelection
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes
import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.vkHashModeForAuthMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Полноэкранный редактор профиля, общий для создания и редактирования.
 *
 * Все поля живут в локальном черновике: отмена ничего не пишет, сохранение
 * пишет один раз. Переподключение активного профиля возвращается вызывающему
 * через [onSaved]; сам редактор сервис не дёргает.
 */
@Composable
internal fun ProfileEditorScreen(
    settingsStore: SettingsStore,
    target: ProfileEditorTarget,
    prefill: ProfileImportPrefill?,
    onDismiss: () -> Unit,
    onSaved: (reconnectRequired: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = target is ProfileEditorTarget.New

    var draft by remember(target, prefill) { mutableStateOf<ProfileDraft?>(null) }
    var baseHasToken by remember(target, prefill) { mutableStateOf(false) }
    var storedUserId by remember(target, prefill) { mutableStateOf("") }
    var hashCheckResultsJson by remember(target, prefill) { mutableStateOf("{}") }
    var clientIds by remember(target, prefill) {
        mutableStateOf(CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    }
    var pendingCredentials by remember(target, prefill) { mutableStateOf(prefill?.credentials) }
    var pendingLink by remember(target, prefill) { mutableStateOf(prefill?.link.orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(target, prefill) {
        try {
            when (target) {
                ProfileEditorTarget.New -> {
                    val base = settingsStore.profile.newDraft()
                    val loaded = base.copy(name = ProfileEditorPolicy.defaultName(base.id))
                    draft = if (prefill != null) {
                        ProfileEditorPolicy.importedLinkModes(
                            loaded.copy(
                                link = prefill.link,
                                hasVkToken = prefill.credentials != null,
                            ),
                            importedToken = prefill.credentials != null,
                        )
                    } else {
                        loaded
                    }
                    baseHasToken = false
                    storedUserId = prefill?.credentials?.userId.orEmpty()
                }

                is ProfileEditorTarget.Existing -> {
                    val loaded = settingsStore.profile.draft(target.id)
                    if (loaded == null) {
                        onDismiss()
                        return@LaunchedEffect
                    }
                    draft = loaded
                    baseHasToken = loaded.hasVkToken
                    storedUserId = settingsStore.profile.vkAccessTokenUserId(target.id)
                    hashCheckResultsJson = settingsStore.profile.connectionSnapshot(target.id)?.vkHashCheckResults ?: "{}"
                    clientIds = settingsStore.profile.prepareSnapshot(target.id)?.clientIds
                        ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            context.showRaisedToast("Не удалось открыть профиль: ошибка хранилища", Toast.LENGTH_LONG)
            onDismiss()
        }
    }

    fun withLinkModes(current: ProfileDraft, importedToken: Boolean = false): ProfileDraft {
        val normalized = if (
            !parseCsqttLink(current.link)?.hashes.isNullOrEmpty() &&
            current.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
        ) {
            val hashMode = vkHashModeForAuthMode(
                requestedAuthMode = CsqttConstants.VkAuth.MODE_CALLS,
                currentHashMode = current.vkHashMode,
                hasVkToken = current.hasVkToken,
            )
            val selection = normalizeVkModeSelection(
                requestedAuthMode = CsqttConstants.VkAuth.MODE_CALLS,
                requestedHashMode = hashMode,
                hasVkToken = current.hasVkToken,
            )
            current.copy(vkAuthMode = selection.authMode, vkHashMode = selection.hashMode)
        } else {
            current
        }
        return ProfileEditorPolicy.importedLinkModes(normalized, importedToken)
    }

    fun editLink(value: String) {
        val current = draft ?: return
        if (value.trim() != pendingLink) pendingCredentials = null
        val importedToken = pendingCredentials != null
        // Токен из вставленной вручную ссылки сохранится при нажатии «Сохранить»,
        // поэтому статус должен сразу отражать его наличие, как при импорте.
        val typedToken = VkLinkImportPolicy.decide(value)?.token?.isNotEmpty() == true
        draft = withLinkModes(
            current.copy(
                link = value,
                hasVkToken = baseHasToken || importedToken || typedToken,
            ),
            importedToken = importedToken,
        )
    }

    fun importLink(decision: VkLinkImportPolicy.Decision) {
        val current = draft ?: return
        pendingLink = decision.sanitizedLink
        pendingCredentials = if (decision.token.isNotEmpty()) {
            ImportedLinkCredentials(token = decision.token, userId = decision.userId)
        } else {
            null
        }
        draft = withLinkModes(
            current.copy(
                link = decision.sanitizedLink,
                hasVkToken = baseHasToken || decision.token.isNotEmpty(),
            ),
            importedToken = decision.token.isNotEmpty(),
        )
    }

    fun save() {
        val current = draft ?: return
        if (saving) return
        val after = current.copy(
            name = current.name.trim(),
            link = current.link.trim(),
        )
        if (!ProfileEditorPolicy.canSave(after.link)) return
        val imported = pendingCredentials?.takeIf {
            pendingLink.isNotBlank() && pendingLink == after.link
        }
        saving = true
        scope.launch {
            try {
                when (target) {
                    ProfileEditorTarget.New -> {
                        val newId = settingsStore.profile.addProfile(after, imported)
                        saving = false
                        if (newId != null) {
                            context.showRaisedToast("Профиль сохранён", Toast.LENGTH_SHORT)
                            onSaved(false)
                        } else {
                            context.showRaisedToast("Не удалось сохранить профиль: проверьте ссылку", Toast.LENGTH_LONG)
                        }
                    }

                    is ProfileEditorTarget.Existing -> {
                        val before = settingsStore.profile.draft(target.id)
                        val reconnect = before != null && ProfileEditorPolicy.reconnectRequired(
                            before = before,
                            after = after,
                            credentialsChanged = pendingCredentials != null,
                        )
                        val saved = settingsStore.profile.saveProfileDraft(target.id, after, imported)
                        saving = false
                        if (saved) {
                            context.showRaisedToast("Профиль сохранён", Toast.LENGTH_SHORT)
                            onSaved(reconnect)
                        } else {
                            context.showRaisedToast("Не удалось сохранить профиль: проверьте ссылку", Toast.LENGTH_LONG)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                saving = false
                context.showRaisedToast("Не удалось сохранить профиль: ошибка хранилища", Toast.LENGTH_LONG)
            }
        }
    }

    val existingId = (target as? ProfileEditorTarget.Existing)?.id
    val canSave = draft?.let { ProfileEditorPolicy.canSave(it.link) } == true && !saving
    val title = if (isNew) "Новый профиль" else draft?.name ?: "Профиль"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(CsqttSizes.MinimumTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { save() },
                        enabled = canSave,
                        modifier = Modifier.height(CsqttSizes.CompactControlHeight),
                        shape = CsqttShapes.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = "Сохранить",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CsqttSizes.ScreenHorizontalPadding)
                        .padding(top = 8.dp, bottom = CsqttSizes.ScreenBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VpnContinuityPlaque(isNew = isNew)

                    val currentDraft = draft
                    if (currentDraft != null) {
                        val linkError = currentDraft.link.isNotBlank() &&
                            !ProfileEditorPolicy.canSave(currentDraft.link)
                        ProfileConnectionFields(
                            settingsStore = settingsStore,
                            draft = currentDraft,
                            linkError = linkError,
                            draftHasStoredToken = baseHasToken,
                            storedUserId = storedUserId,
                            hashCheckResultsJson = hashCheckResultsJson,
                            clientIds = clientIds,
                            onDraftChange = { updated -> draft = updated },
                            onLinkEdited = { value -> editLink(value) },
                            onLinkImported = { decision -> importLink(decision) },
                            onHashCheckResultsChange = { encoded ->
                                hashCheckResultsJson = encoded
                                existingId?.let { id ->
                                    scope.launch {
                                        try {
                                            settingsStore.profile.saveVkHashCheckResults(id, encoded)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: IOException) {
                                            Log.w(
                                                "ProfileEditor",
                                                "Не удалось сохранить кэш проверки хешей профиля $id",
                                                e,
                                            )
                                        }
                                    }
                                }
                            },
                            onForgetToken = existingId?.let { id ->
                                {
                                    scope.launch {
                                        val token = runCatching {
                                            settingsStore.profile.vkAccessToken(id)
                                        }.getOrDefault("")
                                        val cleared = try {
                                            settingsStore.profile.clearVkAccessToken(id)
                                            true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: IOException) {
                                            false
                                        }
                                        if (!cleared) {
                                            context.showRaisedToast(
                                                "Не удалось удалить токен: ошибка хранилища",
                                                Toast.LENGTH_LONG,
                                            )
                                            return@launch
                                        }
                                        val closed = if (token.isNotBlank()) {
                                            runCatching {
                                                VkAutoCallsManager.closeAll(context, token, id)
                                            }.getOrDefault(false)
                                        } else {
                                            false
                                        }
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
                                        baseHasToken = false
                                        storedUserId = ""
                                        pendingCredentials = null
                                        draft = draft?.copy(hasVkToken = false)
                                    }
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "Загрузка профиля…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnContinuityPlaque(isNew: Boolean) {
    Surface(
        shape = CsqttShapes.Card,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (isNew) {
                "Текущий туннель работает без разрыва до сохранения. После сохранения профиль станет активным, и туннель переподключится."
            } else {
                "Текущий туннель работает без разрыва до сохранения. После сохранения изменения применятся, и туннель переподключится."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
