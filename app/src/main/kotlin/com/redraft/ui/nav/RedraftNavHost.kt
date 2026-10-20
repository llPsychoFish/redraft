package com.redraft.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.home.HomeScreen
import com.redraft.ui.overview.PageOverviewRoute
import com.redraft.ui.viewer.ViewerRoute

const val ROUTE_HOME = "home"
const val ROUTE_VIEWER = "viewer"
const val ROUTE_OVERVIEW = "overview"

@Composable
fun RedraftNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: PdfViewerViewModel = viewModel(factory = PdfViewerViewModel.factory(context))

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        modifier = modifier,
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                viewModel = viewModel,
                onDocumentOpened = { navController.navigate(ROUTE_VIEWER) },
            )
        }
        composable(ROUTE_VIEWER) {
            ViewerRoute(
                viewModel = viewModel,
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
                    navController.popBackStack()
                },
            )
        }
    }
}
