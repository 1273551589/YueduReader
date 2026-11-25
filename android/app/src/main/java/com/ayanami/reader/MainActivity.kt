package com.ayanami.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayanami.reader.mod.ModLoader
import com.ayanami.reader.ui.ReaderApp
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.theme.ReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@Composable
private fun Root() {
    val vm: ReaderViewModel = viewModel()
    val modTheme: ModLoader.ThemeTokens? = vm.activeModTheme
    ReaderTheme(modTheme = modTheme) {
        ReaderApp(vm)
    }
}
