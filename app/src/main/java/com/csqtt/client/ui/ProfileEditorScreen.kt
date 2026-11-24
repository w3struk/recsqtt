package com.csqtt.client.ui

import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
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
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }

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
                    if (target.id < 0) {
                        onDismiss()
                        return@LaunchedEffect
                    }
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
    val title = draft?.name?.ifBlank { null } ?: "Профиль"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

        if (!view.isInEditMode) {
            @Suppress("DEPRECATION")
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window
                    ?: (view as? DialogWindowProvider)?.window
                    ?: findDialogWindow(view)
                if (window != null) {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
            }
        }

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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(
                            onClick = { showRenameDialog = true },
                            enabled = draft != null,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Переименовать профиль",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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
                            onCloseVkCalls = if (existingId != null && baseHasToken) {
                                {
                                    scope.launch {
                                        try {
                                            val token = settingsStore.profile.vkAccessToken(existingId)
                                            val closed = VkAutoCallsManager.closeAll(context, token, existingId)
                                            if (closed) {
                                                TunnelManager.updateLog(
                                                    "vk_calls_closed",
                                                    "[OK] Звонки VK закрыты",
                                                    40,
                                                    false,
                                                )
                                                context.showRaisedToast("Звонки VK закрыты", Toast.LENGTH_SHORT)
                                            } else {
                                                TunnelManager.updateLog(
                                                    "vk_calls_closed",
                                                    "[ERR] Звонки VK не закрыты: нет VK-токена",
                                                    90,
                                                    true,
                                                )
                                                context.showRaisedToast("Не удалось закрыть звонки VK", Toast.LENGTH_SHORT)
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            TunnelManager.updateLog(
                                                "vk_calls_close_error",
                                                "[ERR] Не удалось закрыть звонки VK: ${e.message ?: e.javaClass.simpleName}",
                                                90,
                                                true,
                                            )
                                            context.showRaisedToast("Не удалось закрыть звонки VK", Toast.LENGTH_SHORT)
                                        }
                                    }
                                }
                            } else null,
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

        if (showRenameDialog) {
            val currentDraft = draft
            if (currentDraft != null) {
                RenameProfileDialog(
                    currentName = currentDraft.name,
                    onDismiss = { showRenameDialog = false },
                    onConfirm = { newName ->
                        val trimmed = newName.trim()
                        val finalName = if (trimmed.isBlank()) {
                            ProfileEditorPolicy.defaultName(currentDraft.id)
                        } else {
                            trimmed
                        }
                        draft = currentDraft.copy(name = finalName)
                        showRenameDialog = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameProfileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var nameText by rememberSaveable { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Переименовать профиль",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = {
                        Text(
                            "Название профиля",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingText = {
                        Text("Имя профиля отображается в списке и используется в журнале соединений.")
                    },
                    trailingIcon = if (nameText.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { nameText = "" },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Очистить",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(nameText) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    shape = CsqttShapes.Control,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(nameText) },
            ) {
                Text(
                    text = "Сохранить",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Отмена")
            }
        },
    )
}

private tailrec fun findDialogWindow(view: View): Window? {
    if (view is DialogWindowProvider) return view.window
    val parent = view.parent
    return if (parent is View) findDialogWindow(parent) else null
}
