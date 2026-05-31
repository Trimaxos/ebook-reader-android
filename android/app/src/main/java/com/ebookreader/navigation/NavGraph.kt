package com.ebookreader.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ebookreader.data.db.AppDatabase
import com.ebookreader.data.model.Book
import com.ebookreader.ui.library.LibraryScreen
import com.ebookreader.ui.reader.ReaderScreen
import com.ebookreader.ui.settings.SettingsScreen
import com.google.gson.Gson
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val LIBRARY = "library"
    const val READER = "reader/{bookJson}"
    const val SETTINGS = "settings"

    fun readerRoute(book: Book): String {
        val json = Gson().toJson(book)
        val encoded = URLEncoder.encode(json, "UTF-8")
        return "reader/$encoded"
    }
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Routes.LIBRARY
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBookClick = { book ->
                    navController.navigate(Routes.readerRoute(book))
                }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("bookJson") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bookJson = backStackEntry.arguments?.getString("bookJson") ?: return@composable
            val decoded = URLDecoder.decode(bookJson, "UTF-8")
            val book = Gson().fromJson(decoded, Book::class.java)
            ReaderScreen(
                book = book,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
