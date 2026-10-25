package app.revedroid.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.revedroid.R
import app.revedroid.container
import app.revedroid.data.repo.CloudUris
import app.revedroid.ui.account.AccountScreen
import app.revedroid.ui.account.AccountViewModel
import app.revedroid.ui.challenge.ChallengeScreen
import app.revedroid.ui.connect.ConnectEvent
import app.revedroid.ui.connect.ConnectScreen
import app.revedroid.ui.connect.ConnectViewModel
import app.revedroid.ui.files.FilesScreen
import app.revedroid.ui.files.FilesViewModel
import app.revedroid.ui.login.LoginScreen
import app.revedroid.ui.login.LoginViewModel
import kotlinx.serialization.Serializable

@Serializable
data class ConnectRoute(
    val autoConnect: Boolean = true,
)

@Serializable
data class ChallengeRoute(
    val url: String,
)

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

/** Saved-state key the challenge screen sets on the entry below it when it succeeds. */
private const val CHALLENGE_PASSED = "challenge_passed"

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val container = LocalContext.current.container

    // Mid-session challenges (e.g. clearance expired, IP changed) and expired
    // sessions can surface from any request, so they're handled globally.
    LaunchedEffect(navController) {
        container.challengeMonitor.events.collect { url ->
            val dest = navController.currentBackStackEntry?.destination
            val busy = dest?.hasRoute<ChallengeRoute>() == true || dest?.hasRoute<ConnectRoute>() == true
            if (!busy) navController.navigate(ChallengeRoute(url)) { launchSingleTop = true }
        }
    }
    LaunchedEffect(navController) {
        container.sessions.expired.collect {
            navController.navigate(LoginRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = ConnectRoute()) {
        composable<ConnectRoute> { entry ->
            val route = entry.toRoute<ConnectRoute>()
            val vm = viewModel { ConnectViewModel(container, route.autoConnect) }
            val passed by entry.challengePassed()
            LaunchedEffect(passed) { if (passed > 0) vm.onChallengePassed() }
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        is ConnectEvent.OpenChallenge ->
                            navController.navigate(ChallengeRoute(event.url)) {
                                launchSingleTop = true
                            }
                        is ConnectEvent.Connected ->
                            navController.navigate(if (event.signedIn) HomeRoute else LoginRoute) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                    }
                }
            }
            ConnectScreen(vm)
        }

        composable<ChallengeRoute> { entry ->
            val route = entry.toRoute<ChallengeRoute>()
            ChallengeScreen(
                url = route.url,
                onPassed = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(CHALLENGE_PASSED, System.currentTimeMillis())
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable<LoginRoute> { entry ->
            val vm = viewModel { LoginViewModel(container) }
            val passed by entry.challengePassed()
            LaunchedEffect(passed) { if (passed > 0) vm.loadConfig() }
            LoginScreen(
                viewModel = vm,
                onLoggedIn = {
                    navController.navigate(HomeRoute) { popUpTo(navController.graph.id) { inclusive = true } }
                },
                onChangeServer = { navController.switchServer() },
            )
        }

        composable<HomeRoute> { entry ->
            val passed by entry.challengePassed()
            HomeScreen(
                refreshSignal = passed,
                onSignedOut = {
                    navController.navigate(LoginRoute) { popUpTo(navController.graph.id) { inclusive = true } }
                },
                onSwitchServer = { navController.switchServer() },
            )
        }
    }
}

private fun NavHostController.switchServer() {
    navigate(ConnectRoute(autoConnect = false)) { popUpTo(graph.id) { inclusive = true } }
}

@Composable
private fun NavBackStackEntry.challengePassed() = savedStateHandle.getStateFlow(CHALLENGE_PASSED, 0L).collectAsStateWithLifecycle()

private enum class HomeTab(
    val label: Int,
    val icon: ImageVector,
) {
    Files(R.string.nav_files, Icons.Rounded.Folder),
    Trash(R.string.nav_trash, Icons.Rounded.Delete),
    Account(R.string.nav_account, Icons.Rounded.AccountCircle),
}

@Composable
private fun HomeScreen(
    refreshSignal: Long,
    onSignedOut: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    val container = LocalContext.current.container
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val filesVm = viewModel(key = "files-my") { FilesViewModel(container, CloudUris.MY_ROOT) }
    val trashVm = viewModel(key = "files-trash") { FilesViewModel(container, CloudUris.TRASH_ROOT) }
    val accountVm = viewModel { AccountViewModel(container) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            // Re-entering Trash should show what was just deleted.
                            if (item == HomeTab.Trash && tab != index) trashVm.refresh()
                            tab = index
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
        when (HomeTab.entries[tab]) {
            HomeTab.Files -> FilesScreen(filesVm, refreshSignal, modifier)
            HomeTab.Trash -> FilesScreen(trashVm, refreshSignal, modifier)
            HomeTab.Account -> AccountScreen(accountVm, refreshSignal, onSignedOut, onSwitchServer, modifier)
        }
    }
}
