package com.redraft.ui.nav

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.about.AboutScreen
import com.redraft.ui.home.HomeScreen
import com.redraft.ui.overview.PageOverviewRoute
import com.redraft.ui.settings.SettingsScreen
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.ui.support.SupportScreen
import com.redraft.ui.viewer.ViewerRoute
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_PDF_VIEWER = "pdfViewer"
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
    initialUri: Uri? = null,
) {
    val navController = rememberNavController()
    val viewModel: PdfViewerViewModel = pdfViewerViewModel
    
    // Collect documentOpened events and navigate to viewer
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.documentOpened.receive()
            navController.navigate(ROUTE_VIEWER)
        }
    }

    val scope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            enterTransition = {
                slideInHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing))
            },
            exitTransition = {
                slideOutHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { -it / 3 } +
                    fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
            },
            popEnterTransition = {
                slideInHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { -it / 3 } +
                    fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { it }
            },
            predictivePopEnterTransition = { _ ->
                slideInHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { -it / 3 } +
                    fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
            },
            predictivePopExitTransition = { _ ->
                if (initialState.destination.route == ROUTE_OVERVIEW) {
                    fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                        slideOutVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { it / 4 }
                } else {
                    slideOutHorizontally(animationSpec = tween(350, easing = FastOutSlowInEasing)) { it }
                }
            },
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
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
            composable(route = ROUTE_PDF_VIEWER) { backStackEntry ->
                val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                val uri = Uri.parse(uriString)
                ViewerRoute(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenOverview = { navController.navigate(ROUTE_OVERVIEW) },
                    initialUri = uri
                )
            }
            composable(
                route = ROUTE_OVERVIEW,
                enterTransition = {
                    fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it / 4 }
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                        slideOutVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { it / 4 }
                },
            ) {
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
