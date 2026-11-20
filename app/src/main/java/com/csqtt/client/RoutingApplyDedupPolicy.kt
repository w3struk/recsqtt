package com.csqtt.client

/**
 * Пропуск повторного применения серверного правила внутри одного поколения процесса.
 *
 * `ROUTING` присылает только config-owner, но `CONFIG` эмитит каждый воркер, и у
 * каждого свой TUNCONF. Приложение на каждый кадр заново применяет уже активное
 * правило: повторно декодирует блоб адресов и шлёт в stdin полный `UPDATE_IPS`.
 * Поэтому то же самое правило, уже доведённое до клиента в этом поколении, второй
 * раз не отправляем.
 *
 * Сравнение идёт по значению (`==`), а не по ссылке: кадры разбираются в разные
 * экземпляры [ServerRoutingRule], а кешированный кадр ещё и приходит без
 * «тяжёлой» части — её подставляет [normalize]. `null` на входе — очистка, она
 * отправляется всегда.
 */
internal object RoutingApplyDedupPolicy {
    fun isRepeat(pushed: ServerRoutingRule?, incoming: ServerRoutingRule?): Boolean {
        if (pushed == null || incoming == null) return false
        return normalize(incoming, pushed) == pushed
    }

    /**
     * Кадр с кешированной «тяжёлой» частью приходит без доменов и приложений:
     * сервер отдаёт только `content_sha`, а сами списки приложение подставляет
     * из кеша. Применённое правило поэтому хранит уже заполненные списки, и
     * сравнение «как есть» не совпадало никогда — каждая периодическая сверка
     * правила (раз в 5 минут) выглядела новым правилом и применялась заново.
     *
     * Для сравнения подставляем в кадр то, что уже применено, но только когда
     * sha тот же: при другом sha это действительно другое правило.
     */
    private fun normalize(incoming: ServerRoutingRule, pushed: ServerRoutingRule): ServerRoutingRule {
        if (!incoming.contentCached) return incoming
        if (incoming.contentSha == null || incoming.contentSha != pushed.contentSha) return incoming
        return incoming.copy(
            domains = pushed.domains,
            packages = pushed.packages,
            presetRed = pushed.presetRed,
            presetOrange = pushed.presetOrange,
        )
    }
}
