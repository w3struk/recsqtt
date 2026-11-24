package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink

/** Что открывает полноэкранный редактор: новый профиль или существующий. */
internal sealed interface ProfileEditorTarget {
    data object New : ProfileEditorTarget
    data class Existing(val id: Int) : ProfileEditorTarget
}

/**
 * Ссылка и креды, подготовленные внешним импортом для редактора нового
 * профиля. Токен не показывается и не переживает отмену.
 */
internal data class ProfileImportPrefill(
    val link: String,
    val credentials: ImportedLinkCredentials?,
)

/** Чистые решения редактора профиля. */
internal object ProfileEditorPolicy {
    fun defaultName(nextId: Int): String = "Профиль $nextId"

    fun canSave(link: String): Boolean = VkLinkImportPolicy.decide(link) != null

    /**
     * Панельная ссылка с токеном и без хешей включает авто-режим API: иначе
     * профиль сохранится в ручном режиме без единого хеша и не подключится.
     * Это поведение старого импорта, которое не должно теряться в редакторе.
     */
    fun importedLinkModes(draft: ProfileDraft, importedToken: Boolean): ProfileDraft {
        val parsed = parseCsqttLink(draft.link) ?: return draft
        if (parsed.hashes.isNotEmpty()) return draft
        val hasToken = importedToken || parsed.vkAccessToken.isNotEmpty()
        if (!hasToken) return draft
        if (draft.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) {
            return draft.copy(vkHashMode = CsqttConstants.VkAutoHash.MODE_AUTO_JS)
        }
        return draft.copy(
            vkAuthMode = CsqttConstants.VkAuth.MODE_CALLS,
            vkHashMode = CsqttConstants.VkAutoHash.MODE_AUTO_API,
        )
    }

    /**
     * Переподключение нужно только при изменении полей, которые попадают в
     * запуск туннеля. Имя и read-only `hasVkToken` не считаются.
     */
    fun reconnectRequired(
        before: ProfileDraft,
        after: ProfileDraft,
        credentialsChanged: Boolean = false,
    ): Boolean {
        if (credentialsChanged) return true
        if (before.link.trim() != after.link.trim()) return true
        if (before.vkHashes != after.vkHashes) return true
        if (before.vkAuthMode != after.vkAuthMode) return true
        if (before.vkHashMode != after.vkHashMode) return true
        if (before.captchaMode != after.captchaMode) return true
        if (before.captchaSolveMethod != after.captchaSolveMethod) return true
        if (before.captchaWbvSolveMethod != after.captchaWbvSolveMethod) return true
        if (before.turnTransport != after.turnTransport) return true
        if (before.fingerprint != after.fingerprint) return true
        if (before.selectedTurnSubnet != after.selectedTurnSubnet) return true
        if (before.turnSubnetIpLimit != after.turnSubnetIpLimit) return true
        if (before.workersPerHash != after.workersPerHash) return true
        return false
    }
}
