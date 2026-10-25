package com.arkhome.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arkhome.app.ui.ArkTheme
import com.arkhome.app.ui.ArkViewModel
import com.arkhome.app.ui.HomeScreen
import com.arkhome.app.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArkTheme {
                Surface(Modifier.fillMaxSize()) {
                    ArkApp()
                }
            }
        }
    }
}

@Composable
private fun ArkApp(vm: ArkViewModel = viewModel()) {
    val needsSetup by vm.needsSetup.collectAsState()

    if (needsSetup) {
        SetupScreen(
            initialUrl = vm.savedUrl,
            onConnect = vm::saveAndConnect,
        )
    } else {
        HomeScreen(vm)
    }
}
