package com.csqtt.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сообщения о сохранении ссылки CSQTT должны быть правдивыми, а роли «Владелец» в
 * приложении больше нет: исходов ровно два — ссылка записалась или нет. Поэтому текстов
 * о смене режима не существует, а экраны не печатают собственных безусловных тостов.
 */
class LinkSaveNoticeTest {

    private fun readSource(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText()

    private val profileConnectionFieldsSource: String by lazy {
        readSource(
            "app/src/main/java/com/csqtt/client/ui/ProfileConnectionFields.kt",
            "src/main/java/com/csqtt/client/ui/ProfileConnectionFields.kt",
        )
    }

    private val mainActivitySource: String by lazy {
        readSource(
            "app/src/main/java/com/csqtt/client/MainActivity.kt",
            "src/main/java/com/csqtt/client/MainActivity.kt",
        )
    }

    // --- Матрица решений -------------------------------------------------

    @Test
    fun `saved participant link is SAVED`() {
        assertEquals(LinkSaveOutcome.SAVED, LinkSaveNotice.outcomeForParticipantLink(saved = true))
    }

    @Test
    fun `failed participant link is FAILED`() {
        assertEquals(LinkSaveOutcome.FAILED, LinkSaveNotice.outcomeForParticipantLink(saved = false))
    }

    // --- Тексты ----------------------------------------------------------

    @Test
    fun `link toast texts are exact`() {
        assertEquals("Ссылка сохранена ✓", LinkSaveNotice.linkToast(LinkSaveOutcome.SAVED))
        assertEquals("Не удалось сохранить ссылку", LinkSaveNotice.linkToast(LinkSaveOutcome.FAILED))
    }

    @Test
    fun `config toast texts are exact`() {
        assertEquals("Конфигурация reCSQTT сохранена ✓", LinkSaveNotice.configToast(LinkSaveOutcome.SAVED))
        assertEquals("Не удалось сохранить конфигурацию reCSQTT", LinkSaveNotice.configToast(LinkSaveOutcome.FAILED))
    }

    // --- Структурные проверки экранов ------------------------------------

    @Test
    fun `QR branch stays inside the profile editor and does not claim a role change`() {
        assertFalse(
            "редактор всё ещё печатает безусловный тост о включении режима участника",
            profileConnectionFieldsSource.contains("Ссылка подключена из QR-кода"),
        )
        assertTrue(
            "редактор не открывает QR-сканер",
            profileConnectionFieldsSource.contains("QrScannerDialog"),
        )
    }

    @Test
    fun `deep-link import no longer claims the configuration was connected`() {
        assertFalse(
            "MainActivity всё ещё печатает «Конфигурация reCSQTT успешно подключена»",
            mainActivitySource.contains("Конфигурация reCSQTT успешно подключена"),
        )
        assertTrue(
            "MainActivity не использует LinkSaveNotice",
            mainActivitySource.contains("LinkSaveNotice"),
        )
        assertTrue(
            "MainActivity не поглощает intent после импорта",
            mainActivitySource.contains("setIntent(Intent())"),
        )
        assertTrue(
            "MainActivity не использует ProfileImportPolicy",
            mainActivitySource.contains("ProfileImportPolicy"),
        )
    }

    @Test
    fun `the old replace-configuration dialog is gone`() {
        assertFalse(
            "старый диалог замены конфигурации остался",
            mainActivitySource.contains("Заменить текущую конфигурацию"),
        )
    }
}
