package com.semavoice.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.semavoice.app.screens.AboutScreen
import com.semavoice.app.screens.DictationScreen
import com.semavoice.app.screens.DictionaryScreen
import com.semavoice.app.screens.HistoryScreen
import com.semavoice.app.screens.HomeScreen
import com.semavoice.app.screens.ModelsScreen
import com.semavoice.app.screens.NoteDetailScreen
import com.semavoice.app.screens.NotesScreen
import com.semavoice.app.screens.OnboardingScreen
import com.semavoice.app.screens.PrivacyScreen
import com.semavoice.app.screens.SettingsScreen
import com.semavoice.app.screens.TranslateScreen
import com.semavoice.app.screens.VoiceStudioScreen
import com.semavoice.app.ui.SemaTheme
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.settings.AppUiLanguage

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DICTATION = "dictation"
    const val TRANSLATE = "translate"
    const val NOTES = "notes"
    const val NOTE = "note/{id}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val DICTIONARY = "dictionary"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    const val STUDIO = "studio"
    const val PERMISSION = "permission"
    fun note(id: String) = "note/$id"
}

class MainActivity : AppCompatActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SemaGraph.init(this)
        handleIntent(intent)
        setContent {
            val settings by SemaGraph.settings.settings.collectAsState(initial = null)
            val s = settings ?: return@setContent
            applyUiLanguage(s.uiLanguage)
            SemaTheme(mode = s.theme, highContrast = s.highContrast) {
                SemaApp(
                    startRoute = if (s.onboardingComplete) Routes.HOME else Routes.ONBOARDING,
                    pendingRoute = pendingRoute.value,
                    onPendingConsumed = { pendingRoute.value = null },
                    sharedText = sharedText.value,
                    onSharedConsumed = { sharedText.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("route")?.let { pendingRoute.value = it }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it; pendingRoute.value = Routes.TRANSLATE }
        }
    }

    private fun applyUiLanguage(lang: AppUiLanguage) {
        val desired = if (lang == AppUiLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang.tag)
        if (AppCompatDelegate.getApplicationLocales() != desired) AppCompatDelegate.setApplicationLocales(desired)
    }
}

private data class Tab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun SemaApp(startRoute: String, pendingRoute: String?, onPendingConsumed: () -> Unit, sharedText: String?, onSharedConsumed: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    val tabs = listOf(
        Tab(Routes.HOME, R.string.tab_home, Icons.Default.Home),
        Tab(Routes.DICTATION, R.string.tab_voice, Icons.Default.Mic),
        Tab(Routes.TRANSLATE, R.string.tab_translate, Icons.Default.Translate),
        Tab(Routes.NOTES, R.string.tab_notes, Icons.Default.StickyNote2),
        Tab(Routes.HISTORY, R.string.tab_history, Icons.Default.History),
        Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Default.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }

    if (pendingRoute != null) {
        val target = when (pendingRoute) { Routes.PERMISSION -> Routes.DICTATION; else -> pendingRoute }
        androidx.compose.runtime.LaunchedEffect(pendingRoute) {
            nav.navigate(target) { launchSingleTop = true }
            onPendingConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { nav.navigate(tab.route) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(padding)) {
            composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } }) }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.DICTATION) { DictationScreen(nav) }
            composable(Routes.TRANSLATE) { TranslateScreen(nav, initialText = sharedText, onConsumedShared = onSharedConsumed) }
            composable(Routes.NOTES) { NotesScreen(nav) }
            composable(Routes.NOTE, arguments = listOf(navArgument("id") { })) { entry -> NoteDetailScreen(nav, entry.arguments?.getString("id") ?: "") }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.MODELS) { ModelsScreen(nav) }
            composable(Routes.DICTIONARY) { DictionaryScreen(nav) }
            composable(Routes.PRIVACY) { PrivacyScreen(nav) }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable(Routes.STUDIO) { VoiceStudioScreen(nav) }
        }
    }
}
