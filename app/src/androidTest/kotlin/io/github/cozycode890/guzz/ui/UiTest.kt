package io.github.cozycode890.guzz.ui

import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cozycode890.guzz.MainActivity
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.ui.components.NumberSetting
import io.github.cozycode890.guzz.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Điều hướng và đổi ngôn ngữ trên app thật (Stage 8 B7). */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreLanguage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun tabsAndSettingsPagesOpenAndGoBack() {
        waitForTag("tab:${Routes.QUOTA}")
        rule.onNodeWithTag("tab:${Routes.QUOTA}").performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.quota_models)).assertIsDisplayed()

        rule.onNodeWithTag("tab:${Routes.SETTINGS}").performClick()
        val pages = listOf(
            Routes.GOOGLE_AI, Routes.AUDIO, Routes.SPEAKERS, Routes.OUTPUT, Routes.QUEUE, Routes.SYSTEM, Routes.LOGS, Routes.ABOUT,
        )
        for (route in pages) {
            waitForTag("entry:$route")
            rule.onNodeWithTag("entry:$route").performScrollTo().performClick()
            rule.waitForIdle()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            waitForTag("entry:$route")
        }

        // Back từ tab Cài đặt về tab Chuyển đổi, chưa thoát app.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("tab:${Routes.CONVERT}").assertIsSelected()
    }

    @Test
    fun switchingLanguageRelabelsTabsWithoutRestart() {
        for ((tag, expected) in listOf("en" to "Transcribe", "vi" to "Chuyển đổi")) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            // AppCompat dựng lại Activity: chờ nhãn tab đổi sang ngôn ngữ mới.
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("tab:${Routes.CONVERT}").assertTextContains(expected) }.isSuccess
            }
        }
    }
}

/** Ô số chỉ lưu giá trị hợp lệ trong khoảng của mục (Stage 8 B4). */
@RunWith(AndroidJUnit4::class)
class NumberSettingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "ui-test/settings.preferences_pb").also { it.parentFile?.deleteRecursively() }
    private val settings = Settings.create(file, scope)

    @After
    fun cleanUp() {
        scope.cancel()
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun outOfRangeIsNotSaved() {
        val key = SettingKeys.LOG_SIZE_MB // min = 1
        rule.setContent { NumberSetting(settings, key, "MB") }
        val field = rule.onNodeWithTag("number:${key.id}")

        field.performTextReplacement("0")
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.common_number_range, "≥ 1")).assertIsDisplayed()
        assertEquals(key.default, runBlocking { settings.get(key) })

        field.performTextReplacement("7")
        rule.waitUntil(5_000) { runBlocking { settings.get(key) } == 7 }
    }
}
