package com.folio.reader.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.folio.reader.FolioApp
import com.folio.reader.ui.library.FreeBooksScreen
import com.folio.reader.ui.library.FreeBooksViewModel
import com.folio.reader.ui.library.LibraryScreen
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.listen.ListenScreen
import com.folio.reader.ui.reader.ReaderScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun FolioNavHost(incoming: MutableStateFlow<Uri?>, openBook: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    // Scoped to the activity so "Open with" imports keep going while the reader is on screen.
    val libraryVm: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FolioApp) }
        },
    )

    // Audiobooks open in the player, everything else in the reader.
    val open: (String) -> Unit = { id ->
        val audio = libraryVm.allBooks.value.firstOrNull { it.id == id }?.isAudio == true
        nav.navigate(if (audio) "listen/$id" else "reader/$id") { launchSingleTop = true }
    }

    LaunchedEffect(Unit) {
        incoming.filterNotNull().collect { uri ->
            incoming.value = null
            nav.popBackStack("library", inclusive = false)
            libraryVm.import(listOf(uri), openAfter = true)
        }
    }

    LaunchedEffect(Unit) {
        openBook.filterNotNull().collect { id ->
            openBook.value = null
            open(id)
        }
    }

    NavHost(
        navController = nav,
        startDestination = "library",
        enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.96f) },
    ) {
        composable("library") {
            LibraryScreen(
                vm = libraryVm,
                onOpen = open,
                onFindFree = { nav.navigate("free") { launchSingleTop = true } },
            )
        }
        composable("free") {
            val freeVm: FreeBooksViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { FreeBooksViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FolioApp) }
                },
            )
            FreeBooksScreen(vm = freeVm, onBack = { nav.popBackStack() }, onOpen = open)
        }
        composable(
            "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
        composable(
            "listen/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            ListenScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
    }
}
