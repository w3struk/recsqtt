package com.csqtt.client

/** Куда внешний импорт `csqtt://` направляет ссылку (design §4.5). */
internal sealed interface ExternalImportRoute {
    /** Стартовый профиль 0 пуст: сохранить ссылку и креды туда и активировать его. */
    data object AutoSaveStartProfile : ExternalImportRoute

    /** Профиль 0 занят или отсутствует: открыть редактор нового профиля. */
    data object OpenNewProfileEditor : ExternalImportRoute
}

/**
 * Чистое решение о маршруте внешнего импорта. Окна выбора профиля нет:
 * заполненный или отсутствующий профиль 0 всегда ведёт в редактор нового.
 */
internal object ProfileImportPolicy {

    fun route(profiles: List<ProfileSummary>): ExternalImportRoute {
        val start = profiles.firstOrNull { it.id == ProfileRegistryPolicy.DEFAULT_PROFILE_ID }
        return if (start != null && !start.hasLink) {
            ExternalImportRoute.AutoSaveStartProfile
        } else {
            ExternalImportRoute.OpenNewProfileEditor
        }
    }

    fun prefill(decision: VkLinkImportPolicy.Decision): ProfileImportPrefill =
        ProfileImportPrefill(
            link = decision.sanitizedLink,
            credentials = if (decision.token.isEmpty()) {
                null
            } else {
                ImportedLinkCredentials(token = decision.token, userId = decision.userId)
            },
        )
}
