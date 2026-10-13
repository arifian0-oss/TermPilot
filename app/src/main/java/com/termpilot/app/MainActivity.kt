package com.termpilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.termpilot.app.ui.HostsScreen
import com.termpilot.app.ui.SettingsScreen
import com.termpilot.app.ui.TerminalScreen
import com.termpilot.app.ui.theme.TermPilotTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TermPilotApp).container
        setContent {
            TermPilotTheme {
                AppNav(container)
            }
        }
    }
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "hosts") {
        composable("hosts") {
            HostsScreen(
                container = container,
                onOpenTerminal = { hostId -> nav.navigate("terminal/$hostId") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable(
            route = "terminal/{hostId}",
            arguments = listOf(navArgument("hostId") { type = NavType.StringType }),
        ) { backStack ->
            val hostId = backStack.arguments?.getString("hostId") ?: return@composable
            TerminalScreen(
                container = container,
                hostId = hostId,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
