package com.ayanami.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayanami.reader.ui.screens.BookDetailScreen
import com.ayanami.reader.ui.screens.ModsScreen
import com.ayanami.reader.ui.screens.ReadScreen
import com.ayanami.reader.ui.screens.ShelvesScreen
import com.ayanami.reader.ui.screens.SearchScreen
import com.ayanami.reader.ui.screens.SourcesScreen
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun ReaderApp(vm: ReaderViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val tokens = LocalReaderTokens.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(tokens.background)) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                ShelvesScreen(
                    vm, state,
                    onSearch = { nav.navigate("search") },
                    onSources = { nav.navigate("sources") },
                    onMods = { nav.navigate("mods") }
                )
            }
            composable("search") {
                SearchScreen(
                    vm, state,
                    onBack = { nav.popBackStack() },
                    onOpen = { hit ->
                        vm.openHit(hit)
                        nav.navigate("book")
                    }
                )
            }
            composable("book") {
                BookDetailScreen(
                    vm, state,
                    onBack = { nav.popBackStack() },
                    onRead = { chapter, index ->
                        vm.loadChapter(chapter, index)
                        nav.navigate("read")
                    }
                )
            }
            composable("read") {
                ReadScreen(vm, state, onBack = { nav.popBackStack() })
            }
            composable("sources") {
                SourcesScreen(vm, state, onBack = { nav.popBackStack() })
            }
            composable("mods") {
                ModsScreen(vm, state, onBack = { nav.popBackStack() })
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
    }
}
