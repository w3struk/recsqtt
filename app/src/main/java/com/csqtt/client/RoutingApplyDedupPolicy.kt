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
 * экземпляры [ServerRoutingRule]. `null` на входе — очистка, она отправляется
 * всегда.
 */
internal object RoutingApplyDedupPolicy {
    fun isRepeat(pushed: ServerRoutingRule?, incoming: ServerRoutingRule?): Boolean =
        incoming != null && pushed == incoming
}
