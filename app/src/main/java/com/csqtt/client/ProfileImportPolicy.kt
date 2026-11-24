package com.csqtt.client

/** Куда внешний импорт `csqtt://` направляет ссылку (design §4.5). */
internal sealed interface ExternalImportRoute {
    /** Реестр пуст или стартовый профиль 0 свободен: сохранить ссылку и креды туда. */
    data object AutoSaveStartProfile : ExternalImportRoute

    /** Реестр не пуст, но свободного профиля 0 нет: открыть редактор нового профиля. */
    data object OpenNewProfileEditor : ExternalImportRoute
}

/**
 * Чистое решение о маршруте внешнего импорта. Окна выбора профиля нет:
 * ссылка автоматически сохраняется только в пустой реестр или в пустой
 * стартовый профиль 0; любой непустой реестр без свободного профиля 0 ведёт
 * в редактор нового профиля.
 */
internal object ProfileImportPolicy {

    fun route(profiles: List<ProfileSummary>): ExternalImportRoute {
        if (profiles.isEmpty()) return ExternalImportRoute.AutoSaveStartProfile
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
