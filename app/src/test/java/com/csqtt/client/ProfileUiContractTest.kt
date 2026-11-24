package com.csqtt.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract for the Sprint 3 profile UI. */
class ProfileUiContractTest {

    private fun source(relativePath: String): String =
        listOf(File("app/$relativePath"), File(relativePath))
            .first(File::isFile)
            .readText()

    private fun exists(relativePath: String): Boolean =
        listOf(File("app/$relativePath"), File(relativePath)).any(File::isFile)

    @Test
    fun `profile header is below the connection panel with tune on the right`() {
        val connection = source("src/main/java/com/csqtt/client/ui/ConnectionTab.kt")
        assertTrue(
            "ProfileHeaderCard должен идти после TunnelConnectionPanel",
            connection.indexOf("TunnelConnectionPanel(") < connection.indexOf("ProfileHeaderCard("),
        )
        val header = source("src/main/java/com/csqtt/client/ui/ProfileHeaderCard.kt")
        assertTrue("нет Tune", header.contains("Icons.Outlined.Tune"))
        assertTrue("нет раскладки по краям", header.contains("Arrangement.SpaceBetween"))
        assertTrue("пилюля не ограничивает длинное имя", header.contains("weight(1f, fill = false)"))
        assertTrue("нет обводки пилюли", header.contains("BorderStroke(1.dp"))
        assertTrue("нет тональной кнопки Tune", header.contains("FilledTonalIconButton"))
        assertTrue("пилюля не использует Surface(onClick)", header.contains("Surface(") && header.contains("onClick = onOpenProfiles"))
        assertFalse("в шапке остался карандаш", header.contains("Edit"))
    }

    @Test
    fun `profile sheet has no rename and keeps add and delete`() {
        val sheet = source("src/main/java/com/csqtt/client/ui/ProfileListSheet.kt")
        assertTrue("нет удаления", sheet.contains("Icons.Filled.Delete"))
        assertTrue("нет добавления", sheet.contains("+ Добавить профиль"))
        assertTrue("список не ограничен по высоте", sheet.contains("weight(1f, fill = false)"))
        assertTrue("строки списка не оформлены карточкой", sheet.contains("Surface("))
        assertTrue("нет selectable на строке", sheet.contains("selectable("))
        assertTrue("RadioButton сохранил второй клик", sheet.contains("RadioButton(") && sheet.contains("onClick = null"))
        assertTrue("нет заголовка списка", sheet.contains("Профили подключения"))
        assertTrue("нет кнопки закрытия", sheet.contains("Icons.Filled.Close"))
        assertFalse("вернулось переименование", sheet.contains("Icons.Filled.Edit"))
        assertFalse("вернулось переименование", sheet.contains("Icons.Outlined.Edit"))
    }

    @Test
    fun `editor is full screen and contains the required controls`() {
        val editor = source("src/main/java/com/csqtt/client/ui/ProfileEditorScreen.kt")
        listOf(
            "DialogProperties(",
            "usePlatformDefaultWidth = false",
            "Новый профиль",
            "Icons.AutoMirrored.Filled.ArrowBack",
            "Назад",
            "ProfileEditorPolicy.canSave",
            "addProfile(",
            "saveProfileDraft(",
            "ProfileEditorPolicy.reconnectRequired",
            "ProfileEditorPolicy.importedLinkModes",
            "credentialsChanged = pendingCredentials != null",
            "catch (e: IOException)",
            "prefill?.credentials?.userId.orEmpty()",
            "Текущий туннель работает без разрыва до сохранения",
        ).forEach { token ->
            assertTrue("в редакторе нет `$token`", editor.contains(token))
        }
        assertFalse("автосохранение вернулось", editor.contains("delay(300)"))
    }

    @Test
    fun `editor connection fields are grouped into two section cards`() {
        val fields = source("src/main/java/com/csqtt/client/ui/ProfileConnectionFields.kt")
        assertTrue("нет карточек редактора", fields.contains("AppSectionCard"))
        assertTrue("нет заголовка параметров соединения", fields.contains("Параметры соединения"))
        assertTrue("нет разделителей секции", fields.contains("HorizontalDivider"))
        assertTrue("поле ссылки без пометки обязательности", fields.contains("(обязательно)"))
        assertTrue("trailing actions не компактные", fields.contains("LocalMinimumInteractiveComponentSize"))
    }

    @Test
    fun `link field swaps QR and paste for X when filled`() {
        val fields = source("src/main/java/com/csqtt/client/ui/ProfileConnectionFields.kt")
        assertTrue("нет QR", fields.contains("QrScannerDialog"))
        assertTrue("нет вставки", fields.contains("ContentPaste"))
        assertTrue("нет очистки", fields.contains("contentDescription = \"Очистить ссылку\""))
        assertTrue("нет ветки пустого поля", fields.contains("if (draft.link.isBlank())"))
        assertTrue("нет ветки заполненного поля", fields.contains("onLinkEdited(\"\")"))
    }

    @Test
    fun `tunnel tab and header QR are gone`() {
        assertFalse("SettingsTab всё ещё существует", exists("src/main/java/com/csqtt/client/ui/SettingsTab.kt"))
        assertFalse("FloatingToolbar всё ещё существует", exists("src/main/java/com/csqtt/client/ui/FloatingToolbar.kt"))
        val activity = source("src/main/java/com/csqtt/client/MainActivity.kt")
        assertFalse("старая вкладка туннеля осталась", activity.contains("nav_" + "tunnel"))
        assertFalse("SettingsTab остался", Regex("""\bSettingsTab\(""").containsMatchIn(activity))
        assertFalse("FloatingToolbar остался", activity.contains("FloatingToolbar"))
        assertFalse("QR остался в шапке", activity.contains("QrScannerDialog"))
    }

    @Test
    fun `connection layout is top aligned when it scrolls`() {
        val connection = source("src/main/java/com/csqtt/client/ui/ConnectionTab.kt")
        assertFalse("ConnectionTab снова центрирует скролл", connection.contains("Arrangement.Center"))
        assertFalse("ConnectionTab снова смещает скролл", connection.contains("offset(y = 10.dp)"))
        assertTrue("нет 12dp промежутка", connection.contains("spacedBy(12.dp)"))
    }

    @Test
    fun `navigation order is connection logs settings`() {
        val activity = source("src/main/java/com/csqtt/client/MainActivity.kt")
        val connection = activity.indexOf("NavItem(0")
        val logs = activity.indexOf("NavItem(LOGS_TAB_ID")
        val settings = activity.indexOf("NavItem(3")
        assertTrue("вкладка подключения не первая", connection >= 0 && connection < logs)
        assertTrue("логи не перед настройками", logs < settings)
        assertTrue("нет списка [0, 2, 3]", activity.contains("listOf(0, LOGS_TAB_ID, 3)"))
        assertTrue("нет списка [0, 3]", activity.contains("listOf(0, 3)"))
    }

    @Test
    fun `external import is single-shot and has no picker`() {
        val activity = source("src/main/java/com/csqtt/client/MainActivity.kt")
        listOf(
            "setIntent(Intent())",
            "if (savedInstanceState == null) importCsqttLink(intent)",
            "VkAutoCallsManager.closeAll(context, token, id)",
            "ProfileImportPolicy",
            "importExternalLinkIntoStartProfile",
            "ProfileEditorTarget.New",
            "TunnelManager.wantsTunnel()",
            "Не удалось сохранить ссылку: ошибка хранилища",
        ).forEach { token ->
            assertTrue("в импорте нет `$token`", activity.contains(token))
        }
        assertFalse("вернулся выбор профиля", activity.contains("ProfileImportPicker"))
        assertFalse("вернулся старый диалог замены", activity.contains("Заменить текущую конфигурацию"))
    }

    @Test
    fun `deleting the live session profile reconnects to the active one`() {
        val activity = source("src/main/java/com/csqtt/client/MainActivity.kt")
        assertTrue("удаление не проверяет профиль сессии", activity.contains("sessionProfile == id"))
        assertTrue("удаление не переподключает туннель", activity.contains("ACTION_RECONNECT_ACTIVE_PROFILE"))
        assertTrue("удаление не проверяет запущенность", activity.contains("TunnelManager.wantsTunnel()"))

        val sheet = source("src/main/java/com/csqtt/client/ui/ProfileListSheet.kt")
        assertTrue(
            "диалог обещает переподключение к ненастроенному профилю",
            sheet.contains("но он не настроен"),
        )
    }

    @Test
    fun `editor reports storage failures and keeps typed tokens visible`() {
        val editor = source("src/main/java/com/csqtt/client/ui/ProfileEditorScreen.kt")
        assertTrue(
            "забывание токена не обрабатывает ошибку хранилища",
            editor.contains("Не удалось удалить токен: ошибка хранилища"),
        )
        assertTrue(
            "ручной ввод ссылки теряет признак токена",
            editor.contains("VkLinkImportPolicy.decide(value)"),
        )
    }

    @Test
    fun `global settings no longer expose profile fields`() {
        val settings = source("src/main/java/com/csqtt/client/ui/GeneralSettingsContent.kt")
        listOf("Профиль конфигурации", "Отпечаток", "Выбор TURN-подсетей").forEach { token ->
            assertFalse("`$token` остался в общих настройках", settings.contains(token))
        }
        val tab = source("src/main/java/com/csqtt/client/ui/GeneralSettingsTab.kt")
        assertFalse("saveProfileName остался в общих настройках", tab.contains("saveProfileName"))
        assertFalse("TurnSubnetsDialog остался в общих настройках", tab.contains("TurnSubnetsDialog"))
    }
}
