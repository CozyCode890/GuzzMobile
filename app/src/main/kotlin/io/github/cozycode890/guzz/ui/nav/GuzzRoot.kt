package io.github.cozycode890.guzz.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.ui.audio.AudioScreen
import io.github.cozycode890.guzz.ui.convert.ConvertScreen
import io.github.cozycode890.guzz.ui.googleai.GoogleAiScreen
import io.github.cozycode890.guzz.ui.logs.LogsScreen
import io.github.cozycode890.guzz.ui.quota.QuotaScreen
import io.github.cozycode890.guzz.ui.settings.AboutScreen
import io.github.cozycode890.guzz.ui.settings.OutputScreen
import io.github.cozycode890.guzz.ui.settings.QueueSettingsScreen
import io.github.cozycode890.guzz.ui.settings.SettingsHomeScreen
import io.github.cozycode890.guzz.ui.settings.SystemScreen
import io.github.cozycode890.guzz.ui.speakers.SpeakersScreen
import io.github.cozycode890.guzz.ui.transcript.TranscriptScreen
import kotlinx.coroutines.flow.StateFlow

/** Tên các trang (D114). Ba trang đầu là ba tab của thanh dưới (D110). */
object Routes {
    const val CONVERT = "convert"
    const val QUOTA = "quota"
    const val SETTINGS = "settings"
    const val GOOGLE_AI = "settings/google_ai"
    const val AUDIO = "settings/audio"
    const val SPEAKERS = "settings/speakers"
    const val OUTPUT = "settings/output"
    const val QUEUE = "settings/queue"
    const val SYSTEM = "settings/system"
    const val LOGS = "settings/logs"
    const val ABOUT = "settings/about"
    private const val TRANSCRIPT = "transcript"
    const val TRANSCRIPT_PATTERN = "$TRANSCRIPT?location={location}&name={name}"

    /** Mọi trang mở thẳng được bằng extra `route` của bản debug (`tools/device/ui_check.ps1`). */
    val pages = listOf(CONVERT, QUOTA, SETTINGS, GOOGLE_AI, AUDIO, SPEAKERS, OUTPUT, QUEUE, SYSTEM, LOGS, ABOUT)

    fun transcript(location: String, name: String?): String =
        "$TRANSCRIPT?location=${Uri.encode(location)}&name=${Uri.encode(name.orEmpty())}"
}

/** Cỡ chữ tối đa của nhãn thanh dưới. */
private const val MAX_TAB_FONT_SCALE = 1.3f

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.CONVERT, R.string.nav_convert, Icons.Filled.Home),
    Tab(Routes.QUOTA, R.string.nav_quota, Icons.Filled.DateRange),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** Tab mà một trang thuộc về: trang con của Cài đặt sáng tab Cài đặt. */
private fun tabOf(route: String?): String? = when {
    route == null -> null
    route.startsWith(Routes.SETTINGS) -> Routes.SETTINGS
    route == Routes.QUOTA -> Routes.QUOTA
    else -> Routes.CONVERT
}

/** Chuyển sang một tab, giữ trạng thái từng tab; Back từ tab khác về tab Chuyển đổi rồi mới thoát app. */
fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Khung của app (D110, D114): thanh dưới 3 tab và mọi trang. [openRoute]: trang cần mở thẳng (bản debug, hoặc lối vào
 * từ thông báo / app khác), `null` khi không có.
 */
@Composable
fun GuzzRoot(container: AppContainer, resumes: StateFlow<Int>, openRoute: StateFlow<String?>, onRouteOpened: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentTab = tabOf(backStack?.destination?.route)
    val pending by androidx.compose.runtime.produceState<String?>(null, openRoute) { openRoute.collect { value = it } }

    LaunchedEffect(pending) {
        val route = pending ?: return@LaunchedEffect
        if (route in Routes.pages) {
            nav.openTab(tabOf(route) ?: Routes.CONVERT)
            if (tabOf(route) != route) nav.navigate(route)
        }
        onRouteOpened()
    }

    Scaffold(
        // Thanh dưới tự lo thanh điều hướng của hệ thống; từng trang tự lo thanh trạng thái (TopAppBar).
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // Cỡ chữ hệ thống rất lớn (×2) làm nhãn "Chuyển đổi" xuống dòng và bị cắt ở 360 dp: nhãn thanh dưới chỉ phóng
            // tới ×1,3, như các app hệ thống; chữ trong trang vẫn theo đúng cỡ người dùng chọn.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, MAX_TAB_FONT_SCALE))) {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab.route,
                        onClick = {
                            if (currentTab == tab.route) {
                                // Bấm lại tab đang mở: về trang gốc của tab (vd từ Google AI về danh sách Cài đặt).
                                nav.popBackStack(tab.route, inclusive = false)
                            } else {
                                nav.openTab(tab.route)
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("tab:${tab.route}"),
                    )
                }
            }
            }
        },
    ) { padding ->
        val back: () -> Unit = { nav.popBackStack() }
        NavHost(
            navController = nav,
            startDestination = Routes.CONVERT,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.CONVERT) {
                ConvertScreen(
                    container, resumes,
                    open = { route -> if (tabOf(route) == Routes.SETTINGS) { nav.openTab(Routes.SETTINGS); nav.navigate(route) } else nav.navigate(route) },
                )
            }
            composable(Routes.QUOTA) { QuotaScreen(container) }
            composable(Routes.SETTINGS) { SettingsHomeScreen(container) { nav.navigate(it) } }
            composable(Routes.GOOGLE_AI) { GoogleAiScreen(container, back) }
            composable(Routes.AUDIO) { AudioScreen(container.settings, back) }
            composable(Routes.SPEAKERS) { SpeakersScreen(container.settings, back) }
            composable(Routes.OUTPUT) { OutputScreen(container.settings, back) }
            composable(Routes.QUEUE) { QueueSettingsScreen(container.settings, back) }
            composable(Routes.SYSTEM) { SystemScreen(container, resumes, back) { nav.navigate(it) } }
            composable(Routes.LOGS) { LogsScreen(container, back) }
            composable(Routes.ABOUT) { AboutScreen(container, back) }
            composable(
                Routes.TRANSCRIPT_PATTERN,
                arguments = listOf(
                    navArgument("location") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val location = entry.arguments?.getString("location").orEmpty()
                val name = entry.arguments?.getString("name")?.ifEmpty { null }
                TranscriptScreen(location, name, back)
            }
        }
    }
}
