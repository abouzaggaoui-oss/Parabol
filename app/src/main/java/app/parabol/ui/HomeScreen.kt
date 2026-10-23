package app.parabol.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parabol.MainViewModel
import app.parabol.NightBg
import app.parabol.R
import java.text.DateFormat
import java.util.Date

private val LiveAccent = Color(0xFFF5B841)   // signal amber (brand)
private val MoviesAccent = Color(0xFF9C8CFF) // soft violet
private val SeriesAccent = Color(0xFF4FD1C5) // teal

/**
 * Start screen after login: choose Live TV, Movies or Series. Recordings, settings and the
 * active account are reachable from the header. There is no bottom navigation bar.
 */
@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val nowSeconds = rememberNowSeconds()
    val firstTile = rememberFirstItemFocus(ready = true)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NightBg)
            // Faint amber glow behind the logo: the one decorative touch on this screen.
            .background(
                Brush.radialGradient(
                    colors = listOf(LiveAccent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 900f
                )
            )
    ) {
        val wide = maxWidth >= 600.dp
        val wideTileHeight = minOf(240.dp, maxHeight * 0.42f)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 48.dp else 20.dp, vertical = 20.dp)
        ) {
            HomeHeader(vm = vm, nowSeconds = nowSeconds, wide = wide, onOpen = onOpen)

            Spacer(Modifier.height(if (wide) 48.dp else 28.dp))

            val tiles = listOf(
                HomeTileSpec(
                    route = "live",
                    title = stringResource(R.string.tab_live),
                    subtitle = countOrLoading(vm, vm.liveStreams.size, R.string.home_live_subtitle),
                    icon = Icons.Default.LiveTv,
                    accent = LiveAccent
                ),
                HomeTileSpec(
                    route = "movies",
                    title = stringResource(R.string.tab_movies),
                    subtitle = countOrLoading(vm, vm.vodStreams.size, R.string.home_movies_subtitle),
                    icon = Icons.Default.Movie,
                    accent = MoviesAccent
                ),
                HomeTileSpec(
                    route = "series",
                    title = stringResource(R.string.tab_series),
                    subtitle = countOrLoading(vm, vm.seriesList.size, R.string.home_series_subtitle),
                    icon = Icons.Default.VideoLibrary,
                    accent = SeriesAccent
                )
            )

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    tiles.forEachIndexed { index, tile ->
                        HomeTile(
                            spec = tile,
                            large = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(wideTileHeight),
                            focusRequester = if (index == 0) firstTile else null,
                            onClick = { onOpen(tile.route) }
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    tiles.forEachIndexed { index, tile ->
                        HomeTile(
                            spec = tile,
                            large = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp),
                            focusRequester = if (index == 0) firstTile else null,
                            onClick = { onOpen(tile.route) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            HomeStatus(vm)
        }
    }
}

private data class HomeTileSpec(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
)

@Composable
private fun countOrLoading(vm: MainViewModel, count: Int, formatRes: Int): String =
    if (count == 0 && vm.loading) stringResource(R.string.loading)
    else stringResource(formatRes, count)

@Composable
private fun HomeHeader(vm: MainViewModel, nowSeconds: Long, wide: Boolean, onOpen: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Parabol",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        // On a phone the header only has room for the logo and the two icon buttons.
        if (wide) ClockText(nowSeconds, Modifier.padding(end = 8.dp))

        // Active account: opens Settings, where accounts can be switched or added.
        val profileName = vm.activeProfile?.name
        if (profileName != null && wide) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .tvClickable(cornerRadius = 20) { onOpen("accounts") }
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    profileName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
            }
        }
        if (!wide) {
            TvIconButton(onClick = { onOpen("accounts") }) {
                Icon(Icons.Default.Person, contentDescription = stringResource(R.string.accounts_switch))
            }
        }
        TvIconButton(onClick = { onOpen("recordings") }) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = stringResource(R.string.tab_recordings))
        }
        TvIconButton(onClick = { onOpen("settings") }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings))
        }
    }
}

@Composable
private fun HomeTile(
    spec: HomeTileSpec,
    large: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .tvClickable(cornerRadius = 20, focusRequester = focusRequester, onClick = onClick)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(
                Brush.linearGradient(
                    colors = listOf(spec.accent.copy(alpha = 0.30f), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 900f)
                )
            )
            .padding(if (large) 28.dp else 20.dp)
    ) {
        if (large) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                TileIcon(spec, 64.dp)
                Column {
                    Text(spec.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        spec.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TileIcon(spec, 56.dp)
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(spec.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        spec.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TileIcon(spec: HomeTileSpec, size: Dp) {
    Box(
        Modifier
            .size(size)
            .background(spec.accent.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(spec.icon, contentDescription = null, tint = spec.accent, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun HomeStatus(vm: MainViewModel) {
    when {
        vm.contentError && !vm.loading -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.load_failed),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.retryConnection() }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
        vm.loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.home_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            val exp = vm.userInfo?.expDate?.toLongOrNull()
            if (exp != null && exp > 0) {
                Text(
                    stringResource(
                        R.string.home_subscription_until,
                        DateFormat.getDateInstance().format(Date(exp * 1000))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
