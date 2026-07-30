package com.example.artemis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.artemis.permissions.PermissionManager
import com.example.artemis.ui.screens.DashboardScreen
import com.example.artemis.ui.screens.PermissionsScreen
import com.example.artemis.ui.screens.SettingsScreen
import com.example.artemis.ui.theme.ArtemisTheme

/**
 * Simple screen routing sealed class for v1 (no Jetpack Navigation).
 */
private sealed class Screen {
    /** Loading / initial permission check. */
    data object Loading : Screen()

    /** Permissions grant flow. */
    data object Permissions : Screen()

    /** Main dashboard — shown once permissions are granted. */
    data object Dashboard : Screen()

    /** Settings screen. */
    data object Settings : Screen()
}

/**
 * Entry point for Artemis Sentinel.
 *
 * On launch the activity checks whether all required runtime permissions
 * have been granted. If not, [PermissionsScreen] is shown; once the
 * user grants every permission the app transitions to [DashboardScreen].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArtemisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

/**
 * Top-level navigation composable.
 *
 * Manages the current screen, creates a [PermissionManager] instance
 * scoped to the composition, and wires together the permission check
 * on first launch.
 */
@Composable
private fun AppNavigation() {
    val context = LocalContext.current
    val permissionManager = remember { PermissionManager(context) }

    // null = still loading / checking
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Loading) }

    // Run the initial permission check once.
    LaunchedEffect(Unit) {
        val allGranted = permissionManager.checkAllPermissions()
        currentScreen = if (allGranted) Screen.Dashboard else Screen.Permissions
    }

    when (currentScreen) {
        is Screen.Loading -> {
            LoadingIndicator()
        }

        is Screen.Permissions -> {
            PermissionsScreen(
                permissionManager = permissionManager,
                onAllPermissionsGranted = {
                    currentScreen = Screen.Dashboard
                }
            )
        }

        is Screen.Dashboard -> {
            DashboardScreen(
                onNavigateToSettings = {
                    currentScreen = Screen.Settings
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                onNavigateBack = {
                    currentScreen = Screen.Dashboard
                }
            )
        }
    }
}

/**
 * Simple full-screen loading indicator shown while the permission
 * manager performs the initial check.
 */
@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Artemis Sentinel",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
