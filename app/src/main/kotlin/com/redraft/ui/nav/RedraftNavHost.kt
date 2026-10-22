package com.redraft.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.about.AboutScreen
import com.redraft.ui.home.HomeScreen
import com.redraft.ui.overview.PageOverviewRoute
import com.redraft.ui.settings.SettingsScreen
import com.redraft.ui.support.SupportScreen
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.ui.viewer.ViewerRoute
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_VIEWER = "viewer"
const val ROUTE_OVERVIEW = "overview"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_ABOUT = "about"
const val ROUTE_SUPPORT = "support"

@Composable
fun RedraftNavHost(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel,
    pdfViewerViewModel: PdfViewerViewModel,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: PdfViewerViewModel = pdfViewerViewModel
    val scope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            enterTransition = {
                slideInHorizontally(animationSpec = tween(300)) { it } +
                    fadeIn(animationSpec = tween(150))
            },
            exitTransition = {
                slideOutHorizontally(animationSpec = tween(300)) { -it / 3 } +
                    fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideInHorizontally(animationSpec = tween(300)) { -it / 4 }
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(300)) { it / 4 }
            },
        ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                viewModel = viewModel,
                settingsViewModel = settingsViewModel,
                onDocumentOpened = { navController.navigate(ROUTE_VIEWER) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_VIEWER) {
            ViewerRoute(
                viewModel = viewModel,
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenOverview = { navController.navigate(ROUTE_OVERVIEW) },
            )
        }
        composable(ROUTE_OVERVIEW) {
            PageOverviewRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onPageSelected = { pageIndex ->
                    viewModel.onScrollCommanded(pageIndex)
                    scope.launch { navController.popBackStack(ROUTE_VIEWER, inclusive = false) }
                },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                settingsViewModel = settingsViewModel,
                pdfViewerViewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                onOpenSupport = { navController.navigate(ROUTE_SUPPORT) },
            )
        }
        composable(ROUTE_ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onSupport = { navController.navigate(ROUTE_SUPPORT) },
            )
        }
        composable(ROUTE_SUPPORT) {
            SupportScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
}