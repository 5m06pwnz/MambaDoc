package com.mambadoc.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mambadoc.app.ui.screens.ChatScreen
import com.mambadoc.app.ui.screens.SettingsScreen
import com.mambadoc.app.ui.screens.VaultScreen

sealed class Screen(val route: String, val label: String) {
    data object Vault : Screen("vault", "Vault")
    data object Chat : Screen("chat", "Chat")
    data object Settings : Screen("settings", "Models")
}

@Composable
fun MambaDocNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current == Screen.Vault.route,
                    onClick = { navController.navigate(Screen.Vault.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Vault") }
                )
                NavigationBarItem(
                    selected = current == Screen.Chat.route,
                    onClick = { navController.navigate(Screen.Chat.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = current == Screen.Settings.route,
                    onClick = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Models") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Vault.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Vault.route) { VaultScreen() }
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
