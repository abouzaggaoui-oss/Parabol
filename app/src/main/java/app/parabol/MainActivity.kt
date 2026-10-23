package app.parabol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.parabol.ui.*

// Parabol palette: deep night sky + signal amber
val NightBg = Color(0xFF0E1116)
val NightSurface = Color(0xFF171C24)
val NightSurfaceHigh = Color(0xFF212936)
val SignalAmber = Color(0xFFF5B841)
val TextPrimary = Color(0xFFE8EDF2)
val TextSecondary = Color(0xFF93A0B0)

internal val ParabolColors = darkColorScheme(
    primary = SignalAmber,
    onPrimary = Color(0xFF231A05),
    secondary = SignalAmber,
    background = NightBg,
    onBackground = TextPrimary,
    surface = NightSurface,
    onSurface = TextPrimary,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = TextSecondary
)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws apps edge-to-edge; the content is padded by the system bars below.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Android 13+: without this runtime permission the recording progress/finished
        // notifications are silently hidden. Recording itself still works if denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = ParabolColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = NightBg) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        TvScale { ParabolApp() }
                    }
                }
            }
        }
    }
}

@Composable
fun ParabolApp(vm: MainViewModel = viewModel()) {
    val nav: NavHostController = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = if (vm.store.hasCredentials) "accounts" else "login"
    ) {
        composable("login") {
            LoginScreen(vm = vm, onLoggedIn = {
                nav.navigate("accounts") { popUpTo("login") { inclusive = true } }
                nav.navigate("home")
            })
        }
        // Start screen: choose which account to open; Home's back button returns here.
        composable("accounts") {
            AccountsScreen(
                vm = vm,
                onOpen = { id ->
                    vm.switchProfile(id)
                    nav.navigate("home")
                },
                onAdd = { nav.navigate("addAccount") }
            )
        }
        composable("addAccount") {
            LoginScreen(
                vm = vm,
                onLoggedIn = {
                    // The new account is now active: account screen underneath, Home on top.
                    nav.navigate("accounts") { popUpTo(nav.graph.id) { inclusive = true } }
                    nav.navigate("home")
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("home") {
            HomeScreen(vm = vm, onOpen = { route ->
                if (route == "accounts") {
                    nav.navigate("accounts") { popUpTo(nav.graph.id) { inclusive = true } }
                } else {
                    nav.navigate(route)
                }
            })
        }
        composable("live") {
            LiveTvScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenGuide = { streamId -> nav.navigate("epg/$streamId") }
            )
        }
        composable("movies") {
            BrowseScreen(vm = vm, type = BrowseType.MOVIES, onBack = { nav.popBackStack() }, onOpenSeries = {})
        }
        composable("series") {
            BrowseScreen(
                vm = vm,
                type = BrowseType.SERIES,
                onBack = { nav.popBackStack() },
                onOpenSeries = { id -> nav.navigate("seriesDetail/$id") }
            )
        }
        composable("seriesDetail/{seriesId}") { backStack ->
            val id = backStack.arguments?.getString("seriesId")?.toIntOrNull() ?: return@composable
            SeriesDetailScreen(vm = vm, seriesId = id)
        }
        composable("epg/{streamId}") { backStack ->
            val id = backStack.arguments?.getString("streamId")?.toIntOrNull() ?: return@composable
            EpgScreen(vm = vm, streamId = id)
        }
        composable("recordings") {
            ScreenScaffold(
                title = stringResource(R.string.tab_recordings),
                onBack = { nav.popBackStack() }
            ) { RecordingsScreen(vm) }
        }
        composable("settings") {
            ScreenScaffold(
                title = stringResource(R.string.tab_settings),
                onBack = { nav.popBackStack() }
            ) {
                SettingsScreen(
                    vm = vm,
                    onLogout = { nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true } } },
                    onManageGroups = { nav.navigate("groups") }
                )
            }
        }
        composable("groups") {
            GroupsManagerScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}

/** Plays a single item (movie, catch-up programme or recording) full screen. */
fun playSingle(context: android.content.Context, url: String, title: String) {
    playQueue(context, listOf(PlayerQueue.Item(url, title)), 0)
}

/** Plays a queue full screen (e.g. the episodes of a season). Live TV has its own player. */
fun playQueue(context: android.content.Context, items: List<PlayerQueue.Item>, startIndex: Int) {
    if (items.isEmpty()) return
    PlayerQueue.items = items
    PlayerQueue.startIndex = startIndex.coerceIn(0, items.size - 1)
    context.startActivity(Intent(context, PlayerActivity::class.java))
}

/** True on Android TV / Google TV. */
fun isTelevision(context: android.content.Context): Boolean {
    val ui = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    val type = android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    return ui?.currentModeType == type ||
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == type ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Chosen menu size on TV ("small", "normal", "large"); null until read from the store. */
val TvUiSizeState = androidx.compose.runtime.mutableStateOf<String?>(null)

/** Width in dp of the canvas the TV menus are laid out on, per size choice. */
fun tvCanvasWidthDp(size: String): Float = when (size) {
    "small" -> 1280f
    "large" -> 960f
    else -> 1120f
}

/**
 * TVs report very different densities: most give a 960x540 dp screen, but some
 * (often 4K sets) report e.g. 640x360 dp, which makes every menu far too big.
 * On TV we therefore lay out on our own 16:9 canvas (width from the size setting
 * in Settings) and cap an enlarged system font, whatever the set reports.
 */
@Composable
fun TvScale(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tv = androidx.compose.runtime.remember { isTelevision(context) }
    if (!tv) { content(); return }
    if (TvUiSizeState.value == null) TvUiSizeState.value = app.parabol.data.Store(context).tvUiSize
    val canvasW = tvCanvasWidthDp(TvUiSizeState.value ?: "normal")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val hgt = constraints.maxHeight.toFloat()
        val current = androidx.compose.ui.platform.LocalDensity.current
        val target = if (w > 0 && hgt > 0) minOf(w / canvasW, hgt / (canvasW * 9f / 16f)) else current.density
        val density = androidx.compose.ui.unit.Density(
            density = target,
            fontScale = current.fontScale.coerceAtMost(1.1f)
        )
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides density
        ) { content() }
    }
}
