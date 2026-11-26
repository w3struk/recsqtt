package com.csqtt.client

object CsqttConstants {
    object General {
        const val APP_NAME = "reCSQTT"
        const val PACKAGE_VK = "com.vkontakte.android"
        const val PACKAGE_VK_CALLS = "com.vk.calls"

        /** Пакеты VK, которые VPN держит мимо тоннеля во всех режимах. */
        val VK_TRANSPORT_PACKAGES: Set<String> = setOf(PACKAGE_VK, PACKAGE_VK_CALLS)
        const val CSQTT_EVENT_PREFIX = "__CSQTT_EVENT__|"
        const val ACTION_RESTART_WHEN_VK_REACHABLE = "com.csqtt.client.RESTART_WHEN_VK_REACHABLE"
        const val ACTION_VERIFY_VK_REACHABILITY = "com.csqtt.client.VERIFY_VK_REACHABILITY"
        const val ACTION_RECOVER_VK_CALL = "com.csqtt.client.RECOVER_VK_CALL"
        /**
         * Явный запрос переподключения к активному профилю: сохранение уже
         * активного профиля не меняет `active_profile`, поэтому наблюдатель
         * смены профиля не сработает.
         */
        const val ACTION_RECONNECT_ACTIVE_PROFILE = "com.csqtt.client.RECONNECT_ACTIVE_PROFILE"
        const val EXTRA_UNAVAILABLE_VK_HASH = "unavailable_vk_hash"
        const val EXTRA_UNAVAILABLE_VK_CODE = "unavailable_vk_code"
    }

    object Network {
        const val LOCAL_LISTEN_HOST = "127.0.0.1"
    }

    object Tunnel {
        const val BINARY_NAME = "libclient.so"
        const val PROCESS_ENV_EVENTS = "CSQTT_EVENTS"

        const val DEFAULT_FINGERPRINT = "firefox"
        const val DEFAULT_CLIENT_IDS = "8202606,6287487"
        const val DEFAULT_OBFS_MODE = "unified"
        const val DEFAULT_TURN_TRANSPORT = "udp"
        const val TURN_TRANSPORT_TCP_TLS = "tcp_tls"

        const val WORKERS_PER_GROUP = 10
        const val GROUPS_PER_VK_HASH = 1
        const val MAX_VK_HASHES = 6
        const val MAX_WORKERS = 100
    }

    object VkAuth {
        const val MODE_CALLS = "vkcalls"
        const val MODE_CAPTCHA = "legacy"
        const val MODE_AUTO_JS = "auto_js"
    }

    object Timeouts {
        const val CAPTCHA_AUTO_TIMEOUT_MS = 10_000L
        const val CAPTCHA_MANUAL_TIMEOUT_MS = 60_000L
        const val CAPTCHA_WV_CREATE_TIMEOUT_MS = 3_000L

        /** Окно после остановки, в котором повторный старт не поднимает сессию заново. */
        const val POST_STOP_START_COOLDOWN_MS = 300L

        const val UPDATE_RATE_LIMIT_COOLDOWN_MS = 30L * 60L * 1000L
    }

    object Update {
        const val DEFAULT_CHECK_INTERVAL_HOURS = 12
        const val CHECK_NEVER = -1
        const val ACTION_POSTPONED = "postponed"
        const val ACTION_UPDATE = "update"

        const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/w3struk/recsqtt/releases?per_page=30"
        const val GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/w3struk/recsqtt/releases/latest"
        const val GITHUB_LATEST_RELEASE_WEB_URL =
            "https://github.com/w3struk/recsqtt/releases/latest"
        const val GITHUB_RELEASE_TAG_URL_PREFIX =
            "https://github.com/w3struk/recsqtt/releases/tag/"
        const val GITHUB_TAGS_URL =
            "https://api.github.com/repos/w3struk/recsqtt/tags?per_page=100"
        const val GITHUB_TAG_TREE_URL_PREFIX =
            "https://github.com/w3struk/recsqtt/tree/"
    }

    object Notifications {
        const val TUNNEL_CHANNEL_ID = "csqtt_tunnel_v4"
        const val TUNNEL_NOTIFICATION_ID = 1

        const val VPN_CHANNEL_ID = "csqtt_vpn_v1"

        const val CAPTCHA_CHANNEL_ID = "captcha_channel"
        const val CAPTCHA_NOTIFICATION_ID = 9001
    }

    object Widget {
        const val ACTION_TOGGLE = "com.csqtt.client.ACTION_WIDGET_TOGGLE"
    }

    object Captcha {
        const val ERROR_SLIDER_DETECTED = "slider_detected"
        val AUTO_VIEWPORT_WIDTHS = intArrayOf(356, 358, 360, 362, 364, 366, 368)
        val AUTO_VIEWPORT_HEIGHTS = intArrayOf(376, 378, 380, 382, 384, 386, 388)
    }

    object SecureStore {
        const val KEY_ALIAS = "csqtt.settings.secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION_PREFIX = "v1:"
    }

    object VkAutoHash {
        const val MODE_MANUAL = "manual"
        const val MODE_AUTO_API = "auto_api"
        const val MODE_AUTO_JS = "auto_js"

        const val API_METHOD_BASE_URL = "https://api.vk.ru/method/"
        const val API_VERSION = "5.199"

        const val AUTO_CALL_SMALL_DELAY_MS = 380L
        const val AUTO_CALL_LARGE_DELAY_MS = 380L
        const val AUTO_CALL_MAX_ATTEMPTS = 3
        const val FINISH_CALLS_TIMEOUT_MS = 8_000L
    }

    object Vpn {
        const val DEFAULT_MTU = 1500
    }

    object Theme {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        const val PALETTE_INDIGO = "indigo"
        const val PALETTE_ESPRESSO = "espresso"
        const val PALETTE_FOREST = "forest"
    }

    object Links {
        const val RELEASES = "https://github.com/w3struk/recsqtt/releases"
    }

    object Patterns {
        val VERSION_NUMBER = kotlin.text.Regex("\\d+(?:\\.\\d+)*")
    }
}
