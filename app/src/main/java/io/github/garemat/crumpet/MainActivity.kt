package io.github.garemat.crumpet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Apps
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
import io.github.garemat.crumpet.ui.gym.ExerciseHistoryScreen
import io.github.garemat.crumpet.ui.gym.GymHomeScreen
import io.github.garemat.crumpet.ui.gym.HistoryScreen
import io.github.garemat.crumpet.ui.gym.SessionDetailScreen
import io.github.garemat.crumpet.ui.gym.GymViewModel
import io.github.garemat.crumpet.ui.gym.RoutineEditorScreen
import io.github.garemat.crumpet.ui.gym.SessionScreen
import io.github.garemat.crumpet.ui.screens.AppsHubScreen
import io.github.garemat.crumpet.ui.screens.WebAppsScreen
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
        // A workout survives the app being killed (it's on disk) — bring its notification back.
        if (io.github.garemat.crumpet.gym.GymStore.get(this).active.value != null) {
            io.github.garemat.crumpet.gym.GymSessionService.start(this)
        }
        takeRoute(intent)
        setContent { CrumpetTheme { CrumpetApp() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeRoute(intent)
    }

    private fun takeRoute(intent: android.content.Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "crumpet.route"
        /** A screen a notification asked to open (e.g. the workout); consumed by the nav host. */
        val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Outlined.Home),
    Health("health", "Health", Icons.Outlined.Favorite),
    Calendar("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Chat("chat", "Chat", Icons.AutoMirrored.Outlined.Chat),
    Apps("apps", "Apps", Icons.Outlined.Apps),
    Setup("setup", "Settings", Icons.Outlined.Settings),
}

@Composable
private fun CrumpetApp(vm: AppViewModel = viewModel(), gym: GymViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Start (and keep) the gateway connection whenever paired config is present — on launch
    // once DataStore loads, and right after Save & connect writes it. Race-free vs the service.
    val pairedUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val pairedToken by vm.token.collectAsStateWithLifecycle()
    LaunchedEffect(pairedUrl, pairedToken) {
        if (pairedUrl.isNotBlank() && pairedToken.isNotBlank()) PresenceService.start(context)
    }

    val route by MainActivity.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        route?.let { nav.navigate(it) { launchSingleTop = true }; MainActivity.pendingRoute.value = null }
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
                    // Screens opened from the Apps launcher keep the Apps tab lit.
                    val inApps = route?.route?.let { it == "webapps" || it.startsWith("gym") } == true
                    val selected = route?.hierarchy?.any { it.route == d.route } == true ||
                        (d == Dest.Apps && inApps)
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
            // Apps = a launcher: native apps (Gym) + Crumpet's web apps (the WebView launcher).
            composable(Dest.Apps.route) {
                val active by gym.active.collectAsStateWithLifecycle()
                AppsHubScreen(
                    gymSubtitle = active?.let { "In progress · ${it.title}" } ?: "Routines, sessions, PRs",
                    onGym = { nav.navigate(if (active != null) "gym/session" else "gym") },
                    onWebApps = { nav.navigate("webapps") },
                )
            }
            composable("webapps") { WebAppsScreen(vm) }
            composable("gym") {
                GymHomeScreen(gym,
                    onSession = { nav.navigate("gym/session") { launchSingleTop = true } },
                    onEditRoutine = { id -> nav.navigate("gym/routine/$id") },
                    onHistory = { nav.navigate("gym/history") })
            }
            composable("gym/session") {
                SessionScreen(gym, onExit = {
                    nav.navigate("gym") { popUpTo("gym") { inclusive = true }; launchSingleTop = true }
                }, onExercise = { name -> nav.navigate("gym/exercise/" + android.net.Uri.encode(name)) })
            }
            composable("gym/history") {
                HistoryScreen(gym, onOpen = { key -> nav.navigate("gym/history/$key") })
            }
            composable("gym/history/{key}") { entry ->
                SessionDetailScreen(gym, entry.arguments?.getString("key") ?: "",
                    onExercise = { name -> nav.navigate("gym/exercise/" + android.net.Uri.encode(name)) })
            }
            composable("gym/exercise/{name}") { entry ->
                ExerciseHistoryScreen(gym, entry.arguments?.getString("name") ?: "",
                    onOpen = { key -> nav.navigate("gym/history/$key") })
            }
            composable("gym/routine/{id}") { entry ->
                RoutineEditorScreen(gym, entry.arguments?.getString("id") ?: "new", onDone = { nav.popBackStack() })
            }
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
