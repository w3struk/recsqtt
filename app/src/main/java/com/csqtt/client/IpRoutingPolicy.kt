package com.csqtt.client

/**
 * Пересборка VPN при изменении серверного правила.
 *
 * Пользовательского списка IP и отдельного переключателя нет: адреса и режим
 * задаёт только правило сервера (`ServerRoutingRule.ips`/`ipIsWhite`). Список
 * IP — это и есть намерение: пустой список не включает IP-роутинг.
 */
internal object IpRoutingPolicy {
    /**
     * Правило сервера меняет маршруты VPN: `excludeRoutes` собираются из
     * `ips`/`ipIsWhite`, поэтому смена списка IP или режима сама по себе требует
     * пересборки — иначе маршруты остаются прежними до переподключения.
     *
     * В режиме белого списка (`ipIsWhite`) IP маршрутов не создают, поэтому
     * смена именно этого списка пересборки не требует.
     *
     * Пресет российских приложений сервер присылает в том же правиле, и он тоже
     * задаёт маршруты, поэтому его смена (как и смена встроенного списка)
     * пересборки требует.
     */
    fun needsVpnRebuild(previous: ServerRoutingRule?, next: ServerRoutingRule): Boolean =
        previous == null ||
            previous.isWhite != next.isWhite ||
            previous.packages.toSet() != next.packages.toSet() ||
            previous.presetRed.toSet() != next.presetRed.toSet() ||
            previous.presetOrange.toSet() != next.presetOrange.toSet() ||
            previous.ipIsWhite != next.ipIsWhite ||
            (!next.ipIsWhite && previous.ips.toSet() != next.ips.toSet())
}
