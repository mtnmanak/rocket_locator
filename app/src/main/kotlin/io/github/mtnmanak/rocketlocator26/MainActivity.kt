package io.github.mtnmanak.rocketlocator26

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mtnmanak.rocketlocator26.ui.AppNav
import io.github.mtnmanak.rocketlocator26.ui.theme.RocketLocatorTheme

/**
 * Single-activity host. All UI is Compose; screen navigation lives in
 * [AppNav]'s NavHost, not in additional activities.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RocketLocatorTheme {
                AppNav()
            }
        }
    }
}
