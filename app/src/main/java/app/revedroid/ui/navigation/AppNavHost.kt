package app.revedroid.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
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
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.FileType
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
import app.revedroid.ui.share.SaveToScreen
import app.revedroid.ui.shares.SharesScreen
import app.revedroid.ui.shares.SharesViewModel
import app.revedroid.ui.update.UpdateDialog
import app.revedroid.ui.update.UpdateViewModel
import app.revedroid.ui.viewer.ViewerScreen
import app.revedroid.ui.viewer.ViewerViewModel
import kotlinx.serialization.Serializable

/** [addAccount] leads to the sign-in form even if an account for the server exists. */
@Serializable
data class ConnectRoute(
    val autoConnect: Boolean = true,
    val addAccount: Boolean = false,
)

@Serializable
data class ChallengeRoute(
    val url: String,
)

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

/** Where files shared from another app are sent once the user is signed in. */
@Serializable
data object SaveRoute

/** The fields of a [CloudFile] the viewer needs to fetch and cache it. */
@Serializable
data class ViewerRoute(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val updatedAt: String?,
) {
    constructor(file: CloudFile) : this(file.id, file.name, file.path, file.size, file.updatedAt)

    fun toFile() = CloudFile(type = FileType.FILE, id = id, name = name, path = path, size = size, updatedAt = updatedAt)
}

/** Saved-state key the challenge screen sets on the entry below it when it succeeds. */
private const val CHALLENGE_PASSED = "challenge_passed"

/**
 * The app's screens. With [incoming] files (shared from another app) the flow
 * ends at the folder picker instead of the file browser, and
 * [onIncomingHandled] closes the share screen when the user is done.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    incoming: List<Uri>? = null,
    onIncomingHandled: () -> Unit = {},
) {
    val container = LocalContext.current.container
    val signedInRoute: Any = if (incoming != null) SaveRoute else HomeRoute

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
            val vm = viewModel { ConnectViewModel(container, route.autoConnect, route.addAccount) }
            val passed by entry.challengePassed()
            LaunchedEffect(passed) { if (passed > 0) vm.onChallengePassed() }
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        is ConnectEvent.OpenChallenge -> {
                            navController.navigate(ChallengeRoute(event.url)) {
                                launchSingleTop = true
                            }
                        }

                        is ConnectEvent.Connected -> {
                            navController.navigate(if (event.signedIn) signedInRoute else LoginRoute) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
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
                    navController.navigate(signedInRoute) { popUpTo(navController.graph.id) { inclusive = true } }
                },
                onChangeServer = { navController.switchServer() },
            )
        }

        composable<HomeRoute> { entry ->
            val passed by entry.challengePassed()
            HomeScreen(
                refreshSignal = passed,
                onSignedOut = { another ->
                    if (another) {
                        navController.reconnect()
                    } else {
                        navController.navigate(LoginRoute) { popUpTo(navController.graph.id) { inclusive = true } }
                    }
                },
                onSwitchServer = { navController.switchServer() },
                onAccountSwitched = { navController.reconnect() },
                onAddAccount = {
                    navController.navigate(ConnectRoute(autoConnect = false, addAccount = true)) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onPreview = { navController.navigate(ViewerRoute(it)) },
            )
        }

        composable<SaveRoute> {
            SaveToScreen(items = incoming.orEmpty(), onDone = onIncomingHandled)
        }

        composable<ViewerRoute> { entry ->
            val file = entry.toRoute<ViewerRoute>().toFile()
            val vm = viewModel { ViewerViewModel(container, file) }
            ViewerScreen(vm, onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.switchServer() {
    navigate(ConnectRoute(autoConnect = false)) { popUpTo(graph.id) { inclusive = true } }
}

/** Starts over with the saved server, e.g. after switching to another account. */
private fun NavHostController.reconnect() {
    navigate(ConnectRoute()) { popUpTo(graph.id) { inclusive = true } }
}

@Composable
private fun NavBackStackEntry.challengePassed() = savedStateHandle.getStateFlow(CHALLENGE_PASSED, 0L).collectAsStateWithLifecycle()

private enum class HomeTab(
    val label: Int,
    val icon: ImageVector,
) {
    Files(R.string.nav_files, Icons.Rounded.Folder),
    Shares(R.string.nav_shares, Icons.Rounded.Link),
    Trash(R.string.nav_trash, Icons.Rounded.Delete),
    Settings(R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
private fun HomeScreen(
    refreshSignal: Long,
    onSignedOut: (anotherAccount: Boolean) -> Unit,
    onSwitchServer: () -> Unit,
    onAccountSwitched: () -> Unit,
    onAddAccount: () -> Unit,
    onPreview: (CloudFile) -> Unit,
) {
    val container = LocalContext.current.container
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val filesVm = viewModel(key = "files-my") { FilesViewModel(container, CloudUris.MY_ROOT) }
    val sharesVm = viewModel { SharesViewModel(container) }
    val trashVm = viewModel(key = "files-trash") { FilesViewModel(container, CloudUris.TRASH_ROOT) }
    val accountVm = viewModel { AccountViewModel(container) }
    val updateVm = viewModel { UpdateViewModel(container) }
    val update by updateVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(updateVm) { updateVm.checkAutomatically() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            // Re-entering Trash or Shares should show what changed elsewhere.
                            if (tab != index) {
                                when (item) {
                                    HomeTab.Trash -> trashVm.refresh()
                                    HomeTab.Shares -> sharesVm.refresh()
                                    else -> Unit
                                }
                            }
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
            HomeTab.Files -> {
                FilesScreen(filesVm, refreshSignal, onPreview, modifier)
            }

            HomeTab.Shares -> {
                SharesScreen(sharesVm, refreshSignal, modifier)
            }

            HomeTab.Trash -> {
                FilesScreen(trashVm, refreshSignal, onPreview, modifier)
            }

            HomeTab.Settings -> {
                AccountScreen(
                    viewModel = accountVm,
                    updates = updateVm,
                    refreshSignal = refreshSignal,
                    onSignedOut = onSignedOut,
                    onSwitchServer = onSwitchServer,
                    onAccountSwitched = onAccountSwitched,
                    onAddAccount = onAddAccount,
                    modifier = modifier,
                )
            }
        }
    }

    update.available?.let {
        UpdateDialog(it, update.downloading, onInstall = updateVm::install, onDismiss = updateVm::dismiss)
    }
}
