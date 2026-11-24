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
    fun `profile header is gone and profile list is rendered inline inside connection tab`() {
        assertFalse(
            "ProfileHeaderCard.kt не должен существовать",
            exists("src/main/java/com/csqtt/client/ui/ProfileHeaderCard.kt"),
        )
        val connection = source("src/main/java/com/csqtt/client/ui/ConnectionTab.kt")
        assertFalse(
            "ConnectionTab не должен содержать ProfileHeaderCard",
            connection.contains("ProfileHeaderCard"),
        )
        assertTrue(
            "список профилей должен идти после TunnelConnectionPanel",
            connection.indexOf("TunnelConnectionPanel(") < connection.indexOf("ProfileListSection("),
        )
    }

    @Test
    fun `profile list is inline with tune before delete and handles empty state`() {
        val sheet = source("src/main/java/com/csqtt/client/ui/ProfileListSheet.kt")
        assertFalse("лист не должен быть ModalBottomSheet", sheet.contains("ModalBottomSheet"))
        assertFalse("в инлайн-списке не должно быть кнопки закрытия", sheet.contains("Icons.Filled.Close"))
        assertFalse("вернулось переименование", sheet.contains("Icons.Filled.Edit"))
        assertFalse("вернулось переименование", sheet.contains("Icons.Outlined.Edit"))

        assertTrue("нет заголовка списка", sheet.contains("Профили подключения"))
        assertTrue("нет добавления", sheet.contains("Добавить"))
        assertFalse("старый текст кнопки остался", sheet.contains("Добавить профиль"))
        assertFalse("бейдж АКТИВЕН не должен присутствовать", sheet.contains("АКТИВЕН"))
        assertTrue("строки списка не оформлены карточкой", sheet.contains("Surface("))
        assertTrue("нет selectable на строке", sheet.contains("selectable("))
        assertTrue("RadioButton сохранил второй клик", sheet.contains("RadioButton(") && sheet.contains("onClick = null"))

        val tuneIndex = sheet.indexOf("Icons.Outlined.Tune")
        val deleteIndex = sheet.indexOf("Icons.Filled.Delete")
        assertTrue("нет иконки Tune", tuneIndex >= 0)
        assertTrue("нет иконки Delete", deleteIndex >= 0)
        assertTrue("Tune должен быть расположен в коде до Delete", tuneIndex < deleteIndex)

        val tuneBlock = sheet.substring(tuneIndex, deleteIndex)
        assertFalse("кнопка Tune не должна содержать BorderStroke", tuneBlock.contains("BorderStroke"))

        assertTrue("нет плейсхолдера пустого состояния", sheet.contains("Пока нет профилей"))

        val connection = source("src/main/java/com/csqtt/client/ui/ConnectionTab.kt")
        assertTrue(
            "ConnectionTab не содержит подсказки для пустого состояния",
            connection.contains("Добавьте профиль, чтобы подключиться"),
        )
        assertTrue(
            "ConnectionTab не содержит честного a11y-описания отключенного логотипа",
            connection.contains("VPN отключён: нет профиля"),
        )
    }

    @Test
    fun `editor is full screen and contains the required controls`() {
        val editor = source("src/main/java/com/csqtt/client/ui/ProfileEditorScreen.kt")
        listOf(
            "DialogProperties(",
            "usePlatformDefaultWidth = false",
            "Icons.AutoMirrored.Filled.ArrowBack",
            "Назад",
            "Icons.Rounded.Edit",
            "Переименовать профиль",
            "ProfileEditorPolicy.canSave",
            "addProfile(",
            "saveProfileDraft(",
            "ProfileEditorPolicy.reconnectRequired",
            "ProfileEditorPolicy.importedLinkModes",
            "credentialsChanged = pendingCredentials != null",
            "catch (e: IOException)",
            "prefill?.credentials?.userId.orEmpty()",
        ).forEach { token ->
            assertTrue("в редакторе нет `$token`", editor.contains(token))
        }
        assertFalse("автосохранение вернулось", editor.contains("delay(300)"))
        assertFalse("плашка VpnContinuityPlaque не должна быть в редакторе", editor.contains("VpnContinuityPlaque"))
        assertFalse(
            "текст плашки остался в редакторе",
            editor.contains("Текущий туннель работает без разрыва до сохранения"),
        )
        assertFalse(
            "заголовок не должен быть захардкожен как Новый профиль",
            editor.contains("\"Новый профиль\""),
        )
    }

    @Test
    fun `editor connection fields are grouped into two section cards`() {
        val fields = source("src/main/java/com/csqtt/client/ui/ProfileConnectionFields.kt")
        assertTrue("нет карточек редактора", fields.contains("AppSectionCard"))
        assertTrue("нет заголовка параметров соединения", fields.contains("Параметры соединения"))
        assertTrue("нет разделителей секции", fields.contains("HorizontalDivider"))
        assertTrue("поле ссылки без пометки обязательности", fields.contains("(обязательно)"))
        assertTrue("trailing actions не компактные", fields.contains("LocalMinimumInteractiveComponentSize"))
        assertFalse("поле названия профиля должно быть удалено из полей соединения", fields.contains("Название профиля"))
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
