package io.github.garemat.crumpet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.garemat.crumpet.geo.PresenceWorker
import io.github.garemat.crumpet.push.PresenceService
import io.github.garemat.crumpet.sync.SyncWorker
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.screens.CalendarScreen
import io.github.garemat.crumpet.ui.screens.ChatScreen
import io.github.garemat.crumpet.ui.screens.HealthScreen
import io.github.garemat.crumpet.ui.screens.HomeScreen
import io.github.garemat.crumpet.ui.screens.PlacesScreen
import io.github.garemat.crumpet.ui.screens.SetupScreen
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Bg2
import io.github.garemat.crumpet.ui.theme.CrumpetTheme
import io.github.garemat.crumpet.ui.theme.Faint

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.schedule(applicationContext)
        PresenceWorker.schedule(applicationContext)
        setContent { CrumpetTheme { CrumpetApp() } }
    }
}

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Outlined.Home),
    Health("health", "Health", Icons.Outlined.Favorite),
    Calendar("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Chat("chat", "Chat", Icons.AutoMirrored.Outlined.Chat),
    Setup("setup", "Settings", Icons.Outlined.Settings),
}

@Composable
private fun CrumpetApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Start (and keep) the gateway connection whenever paired config is present — on launch
    // once DataStore loads, and right after Save & connect writes it. Race-free vs the service.
    val pairedUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val pairedToken by vm.token.collectAsStateWithLifecycle()
    LaunchedEffect(pairedUrl, pairedToken) {
        if (pairedUrl.isNotBlank() && pairedToken.isNotBlank()) PresenceService.start(context)
    }

    val healthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { vm.syncNow() }

    val grantLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refresh() }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Bg2) {
                val current by nav.currentBackStackEntryAsState()
                val route = current?.destination
                Dest.entries.forEach { d ->
                    val selected = route?.hierarchy?.any { it.route == d.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, d.label) },
                        label = { Text(d.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brass,
                            selectedTextColor = Brass,
                            indicatorColor = Bg2,
                            unselectedIconColor = Faint,
                            unselectedTextColor = Faint,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Dest.Home.route, modifier = Modifier.padding(pad)) {
            composable(Dest.Home.route) { HomeScreen(vm, onChat = { nav.navigate(Dest.Chat.route) }) }
            composable(Dest.Health.route) { HealthScreen(vm) }
            composable(Dest.Calendar.route) { CalendarScreen(vm) }
            composable(Dest.Chat.route) { ChatScreen(vm) }
            composable("places") { PlacesScreen() }
            composable(Dest.Setup.route) {
                SetupScreen(
                    vm = vm,
                    onOpenPlaces = { nav.navigate("places") },
                    onRequestHealth = { healthLauncher.launch(vm.health.permissions) },
                    onRequestOther = {
                        grantLauncher.launch(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        )
                    },
                    onConnect = { u, t ->
                        vm.savePairing(u, t)  // LaunchedEffect above starts the service once saved
                        android.widget.Toast.makeText(
                            context, "Saved — connecting…", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}
