package com.epubreader.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.epubreader.app.ui.bookshelf.BookshelfScreen
import com.epubreader.app.ui.reader.ReaderScreen
import com.epubreader.app.ui.log.LogViewerScreen

@Composable
fun EpubReaderNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = BookshelfRoute) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onBookClick = { uuid -> navController.navigate(ReaderRoute(uuid)) },
                onLogViewerClick = { navController.navigate(LogViewerRoute) }
            )
        }
        composable<ReaderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReaderRoute>()
            ReaderScreen(
                bookUuid = route.bookUuid,
                onBack = { navController.popBackStack() }
            )
        }
        composable<LogViewerRoute> {
            LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
