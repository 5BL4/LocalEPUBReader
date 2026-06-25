package com.epubreader.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.epubreader.app.ui.bookshelf.BookshelfScreen
import com.epubreader.app.ui.reader.ReaderPlaceholderScreen

@Composable
fun EpubReaderNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = BookshelfRoute) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onBookClick = { uuid -> navController.navigate(ReaderRoute(uuid)) }
            )
        }
        composable<ReaderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReaderRoute>()
            ReaderPlaceholderScreen(
                bookUuid = route.bookUuid,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
