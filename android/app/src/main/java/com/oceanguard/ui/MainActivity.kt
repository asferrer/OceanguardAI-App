package com.oceanguard.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.ui.screens.BatchResultsScreen
import com.oceanguard.ai.ui.screens.CameraScreen
import com.oceanguard.ai.ui.screens.LiveDetectionScreen
import com.oceanguard.ai.ui.screens.HistoryScreen
import com.oceanguard.ai.ui.screens.HomeScreen
import com.oceanguard.ai.ui.screens.MapScreen
import com.oceanguard.ai.ui.screens.ReportScreen
import com.oceanguard.ai.ui.screens.OnboardingScreen
import com.oceanguard.ai.ui.screens.ResultsScreen
import com.oceanguard.ai.ui.screens.SessionDetailScreen
import com.oceanguard.ai.ui.screens.SettingsScreen
import com.oceanguard.ai.ui.screens.AchievementsScreen
import com.oceanguard.ai.ui.screens.LocationPickerScreen
import com.oceanguard.ai.ui.screens.MarineDexDetailScreen
import com.oceanguard.ai.ui.screens.MarineDexScreen
import com.oceanguard.ai.ui.screens.SplashScreen
import com.oceanguard.ai.ui.components.AchievementUnlockOverlay
import com.oceanguard.ai.data.collection.AchievementDef
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.BottomBarTab
import com.oceanguard.ai.ui.components.OceanBottomBar
import com.oceanguard.ai.ui.components.ScreenEnterTransition
import com.oceanguard.ai.ui.components.ScreenExitTransition
import com.oceanguard.ai.ui.components.ScreenPopEnterTransition
import com.oceanguard.ai.ui.components.ScreenPopExitTransition
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.theme.OceanGuardTheme

/** Ordered tab definitions for the custom bottom navigation bar. */
private val BOTTOM_NAV_TABS = listOf(
    BottomBarTab("home", R.string.nav_home, Icons.Filled.Home),
    BottomBarTab("map", R.string.nav_map, Icons.Filled.Map),
    BottomBarTab("reports", R.string.nav_reports, Icons.Filled.Analytics),
    BottomBarTab("history", R.string.nav_history, Icons.Filled.History),
    BottomBarTab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

/** Routes that display the bottom navigation bar. */
private val BOTTOM_NAV_ROUTES = BOTTOM_NAV_TABS.map { it.route }.toSet()

/**
 * MainActivity — single-activity host for the OceanGuard AI application.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // No-op: notifications are optional. Inference still works without them,
            // but the persistent progress notification won't show on Android 13+.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS on Android 13+ for the foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val app = application as OceanGuardApp

        // Apply saved app language (empty = follow system)
        val savedLocale = app.settingsRepository.getAppLanguageSync()
        if (savedLocale.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(savedLocale)
            )
        }

        val viewModel = MainViewModel(applicationContext, app.detectionOrchestrator, app.repository, app.settingsRepository, app.locationProvider)

        setContent {
            val darkMode by app.settingsRepository.darkMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.DEFAULT_DARK_MODE,
            )
            OceanGuardTheme(useDarkTheme = darkMode) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val baseRoute = currentRoute?.substringBefore("?")

                // Collection data for HomeScreen and overlay
                val dexEntries by app.collectionRepository.allDexEntries.collectAsStateWithLifecycle(initialValue = emptyList())
                val discoveredCount by app.collectionRepository.discoveredCount.collectAsStateWithLifecycle(initialValue = 0)
                val allAchievements by app.collectionRepository.allAchievements.collectAsStateWithLifecycle(initialValue = emptyList())
                val latestUnlocked = remember(allAchievements) {
                    allAchievements.filter { it.unlockedAt > 0 }
                        .maxByOrNull { it.unlockedAt }
                }

                // Achievement unlock overlay state
                var pendingUnlock by remember { mutableStateOf<AchievementDef?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    app.achievementChecker.achievementUnlocked.collect { def ->
                        pendingUnlock = def
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        if (baseRoute in BOTTOM_NAV_ROUTES) {
                            OceanBottomBar(
                                tabs = BOTTOM_NAV_TABS,
                                currentRoute = baseRoute,
                                onTabSelected = { route ->
                                    navController.navigate(route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    },
                ) { paddingValues ->
                    NavHost(
                        navController = navController,
                        startDestination = "splash",
                        modifier = Modifier.padding(paddingValues),
                        enterTransition = { ScreenEnterTransition },
                        exitTransition = { ScreenExitTransition },
                        popEnterTransition = { ScreenPopEnterTransition },
                        popExitTransition = { ScreenPopExitTransition },
                    ) {
                        composable("splash") {
                            SplashScreen(
                                settingsRepository = app.settingsRepository,
                                onNavigateToHome = {
                                    navController.navigate("home") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                },
                                onNavigateToOnboarding = {
                                    navController.navigate("onboarding") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("onboarding") {
                            OnboardingScreen(
                                onComplete = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        app.settingsRepository.setOnboardingComplete(true)
                                        app.settingsRepository.setGuidedTourActive(true)
                                    }
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                navController = navController,
                                viewModel = viewModel,
                                dexEntries = dexEntries,
                                discoveredCount = discoveredCount,
                                latestAchievement = latestUnlocked,
                            )
                        }
                        composable("camera") {
                            CameraScreen(navController = navController, viewModel = viewModel)
                        }
                        composable("live_detection") {
                            LiveDetectionScreen(navController = navController, viewModel = viewModel)
                        }
                        composable("results") {
                            ResultsScreen(navController = navController, viewModel = viewModel)
                        }
                        composable("history") {
                            HistoryScreen(navController = navController, viewModel = viewModel)
                        }
                        composable("settings") {
                            SettingsScreen(navController = navController, viewModel = viewModel)
                        }
                        composable("map") {
                            MapScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(
                            route = "reports?lat={lat}&lon={lon}&name={name}",
                            arguments = listOf(
                                navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                                navArgument("lon") { type = NavType.StringType; nullable = true; defaultValue = null },
                                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { backStackEntry ->
                            ReportScreen(
                                navController = navController,
                                viewModel = viewModel,
                                preselectedLat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull(),
                                preselectedLon = backStackEntry.arguments?.getString("lon")?.toDoubleOrNull(),
                                preselectedName = backStackEntry.arguments?.getString("name"),
                            )
                        }
                        composable("batch") {
                            val batchUris by viewModel.batchUris.collectAsStateWithLifecycle()
                            BatchResultsScreen(
                                navController = navController,
                                viewModel = viewModel,
                                uriList = batchUris,
                            )
                        }
                        composable(
                            route = "session/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
                            SessionDetailScreen(
                                navController = navController,
                                viewModel = viewModel,
                                sessionId = sessionId,
                                onNavigateToLocationPicker = { id ->
                                    navController.navigate("location_picker/$id")
                                },
                            )
                        }
                        composable(
                            route = "location_picker/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
                            LocationPickerScreen(
                                sessionId = sessionId,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("marinedex") {
                            MarineDexScreen(
                                collectionRepository = app.collectionRepository,
                                onNavigateToDetail = { type -> navController.navigate("marinedex/$type") },
                                onNavigateBack = { navController.popBackStack() },
                                navController = navController,
                            )
                        }
                        composable(
                            route = "marinedex/{debrisType}",
                            arguments = listOf(navArgument("debrisType") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val debrisType = backStackEntry.arguments?.getString("debrisType") ?: return@composable
                            MarineDexDetailScreen(
                                debrisType = debrisType,
                                collectionRepository = app.collectionRepository,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToSession = { sessionId ->
                                    navController.navigate("session/$sessionId")
                                },
                                navController = navController,
                            )
                        }
                        composable("achievements") {
                            AchievementsScreen(
                                collectionRepository = app.collectionRepository,
                                onNavigateBack = { navController.popBackStack() },
                                navController = navController,
                            )
                        }
                    }
                }

                // Achievement unlock overlay (rendered above everything)
                AchievementUnlockOverlay(
                    achievementDef = pendingUnlock,
                    onDismiss = { pendingUnlock = null },
                )
                } // Box
            }
        }
    }
}

