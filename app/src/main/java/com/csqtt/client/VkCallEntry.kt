package com.csqtt.client

/**
 * Один заминченный VK-звонок, переживающий смерть процесса.
 *
 * [joinLink] — единственное, что нужно для возврата в звонок; остальные поля служат
 * для решения, можно ли ссылку переиспользовать ([VkCallReusePolicy]).
 */
internal data class VkCallEntry(
    val callId: String,
    val hash: String,
    val joinLink: String,
    val createdAtMs: Long,
    val idleSinceMs: Long? = null,
    val tokenFp: String,
    val accountUserId: String = "",
    val mode: String = CsqttConstants.VkAutoHash.MODE_AUTO_API,
    val failCount: Int = 0,
    val lastFailMs: Long? = null,
)
