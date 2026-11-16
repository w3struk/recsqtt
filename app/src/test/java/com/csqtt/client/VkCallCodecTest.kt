package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallCodecTest {
    private fun entry(
        callId: String = "call-1",
        hash: String = "abcdef0123456789",
        joinLink: String = "https://vk.ru/call/join/aaa",
        createdAtMs: Long = 1_700_000_000_000,
        idleSinceMs: Long? = null,
        tokenFp: String = "fp-1",
        accountUserId: String = "",
        mode: String = CsqttConstants.VkAutoHash.MODE_AUTO_API,
        failCount: Int = 0,
        lastFailMs: Long? = null,
    ) = VkCallEntry(
        callId = callId,
        hash = hash,
        joinLink = joinLink,
        createdAtMs = createdAtMs,
        idleSinceMs = idleSinceMs,
        tokenFp = tokenFp,
        accountUserId = accountUserId,
        mode = mode,
        failCount = failCount,
        lastFailMs = lastFailMs,
    )

    @Test
    fun roundTripKeepsEveryFieldAndOrder() {
        val source = listOf(
            entry(
                callId = "call-1",
                idleSinceMs = 42L,
                accountUserId = "user-42",
                failCount = 2,
                lastFailMs = 900L,
            ),
            entry(
                callId = "call-2",
                hash = "0011223344556677",
                mode = CsqttConstants.VkAutoHash.MODE_AUTO_JS,
                idleSinceMs = null,
                failCount = 0,
                lastFailMs = null,
            ),
        )

        val restored = VkCallCodec.decode(VkCallCodec.encode(source))

        assertEquals(source, restored)
    }

    @Test
    fun nullsSurviveRoundTrip() {
        val source = listOf(entry(idleSinceMs = null, lastFailMs = null))

        val restored = VkCallCodec.decode(VkCallCodec.encode(source))

        assertEquals(1, restored.size)
        assertEquals(null, restored.single().idleSinceMs)
        assertEquals(null, restored.single().lastFailMs)
        assertEquals(source, restored)
    }

    @Test
    fun accountUserIdSurvivesRoundTrip() {
        val source = listOf(entry(callId = "call-acct", accountUserId = "user-777"))

        val restored = VkCallCodec.decode(VkCallCodec.encode(source))

        assertEquals(source, restored)
        assertEquals("user-777", restored.single().accountUserId)
    }

    @Test
    fun legacyJsonWithoutAccountUserIdDecodesToEmpty() {
        val raw = """
            [
              {"call_id":"call-old","hash":"abcdef0123456789","join_link":"https://vk.ru/call/join/aaa",
               "created_at_ms":1700000000000,"idle_since_ms":null,"token_fp":"fp-1",
               "mode":"auto_api","fail_count":0,"last_fail_ms":null}
            ]
        """.trimIndent()

        val decoded = VkCallCodec.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals("", decoded.single().accountUserId)
        assertEquals("fp-1", decoded.single().tokenFp)
    }

    @Test
    fun blankOrCorruptInputDecodesToEmpty() {
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode(null))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode(""))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode("   "))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode("not json at all"))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode("{\"calls\":[]}"))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode("[{\"call_id\":\"x\","))
        assertEquals(emptyList<VkCallEntry>(), VkCallCodec.decode("[]"))
    }

    @Test
    fun emptyListEncodesToEmptyArray() {
        val encoded = VkCallCodec.encode(emptyList())

        assertEquals("[]", encoded)
        assertTrue(VkCallCodec.decode(encoded).isEmpty())
    }

    @Test
    fun malformedEntryIsSkippedAndValidOneSurvives() {
        val valid = entry(callId = "call-ok")
        val raw = """
            [
              {"call_id":"call-ok","hash":"abcdef0123456789","join_link":"https://vk.ru/call/join/aaa",
               "created_at_ms":1700000000000,"idle_since_ms":null,"token_fp":"fp-1",
               "mode":"auto_api","fail_count":0,"last_fail_ms":null},
              {"call_id":"","hash":"abcdef0123456789","join_link":"https://vk.ru/x",
               "created_at_ms":1,"token_fp":"fp-1"},
              {"call_id":"no-hash","hash":"","join_link":"https://vk.ru/y",
               "created_at_ms":1,"token_fp":"fp-1"},
              {"call_id":"no-created-at","hash":"abcdef0123456789","join_link":"https://vk.ru/z",
               "token_fp":"fp-1"},
              {"call_id":"no-join-link","hash":"abcdef0123456789","created_at_ms":5,"token_fp":"fp-1"},
              "garbage",
              {"call_id":"no-token-fp","hash":"abcdef0123456789","join_link":"https://vk.ru/w",
               "created_at_ms":5}
            ]
        """.trimIndent()

        val decoded = VkCallCodec.decode(raw)

        assertEquals(listOf(valid), decoded)
    }
}
