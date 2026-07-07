package io.github.mtnmanak.rocketlocator26

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mtnmanak.rocketlocator26.ui.HomeScreen
import io.github.mtnmanak.rocketlocator26.ui.HomeViewModel
import io.github.mtnmanak.rocketlocator26.ui.theme.RocketLocatorTheme

/**
 * Single-activity host. All UI is Compose; navigation (when it arrives)
 * will live inside [HomeScreen]'s successor, not in additional activities.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RocketLocatorTheme {
                val viewModel: HomeViewModel = viewModel()
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}
