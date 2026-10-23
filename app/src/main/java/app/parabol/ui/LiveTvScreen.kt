package app.parabol.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.parabol.MainViewModel
import app.parabol.NightBg
import app.parabol.R
import app.parabol.data.EpgListing
import app.parabol.data.LiveSession
import app.parabol.data.LiveStream
import app.parabol.player.PlayerFactory
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of the channel column in the guide timeline. The time ruler uses the same value. */
private val ChannelColumnWidth = 230.dp

/**
 * Live TV: groups, channel list with guide timeline, and a split view while watching.
 *
 * Browse:      groups menu (left)  |  channels with an EPG timeline of the coming programmes.
 * Split view:  groups  |  channels  |  preview: the playing channel + now / up next.
 * Full screen: picking a channel always goes full screen. Back returns to split view,
 *              back again stops playback, back again returns to Home.
 *
 * The video is a single PlayerView that is positioned over the preview slot in split view and
 * stretched to the whole screen in full screen, so switching never restarts the stream.
 *
 * On a phone in portrait the groups and channels don't fit side by side, so they are shown
 * one after the other (groups -> channels), and split view puts the video above the list.
 */
@Composable
fun LiveTvScreen(vm: MainViewModel, onBack: () -> Unit, onOpenGuide: (Int) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val nowSeconds = rememberNowSeconds()

    // ---- Browse state ----
    var groupQuery by rememberSaveable { mutableStateOf("") }
    var channelQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroupId by rememberSaveable { mutableStateOf(vm.lastLiveGroupId) }

    // ---- Playback state ----
    var session by remember { mutableStateOf<LiveSession?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var exo by remember { mutableStateOf<ExoPlayer?>(null) }
    val tsFallbackTried = remember { mutableSetOf<String>() }
    val videoPlaceholder = LocalLiveVideoPlaceholder.current

    // ---- Focus ----
    val groupFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    val playingChannelFocus = remember { FocusRequester() }
    var focusChannelsTick by remember { mutableIntStateOf(0) }
    val channelListState = rememberLazyListState()

    // ---- Data ----
    val groups = remember(groupQuery, vm.liveCategories, vm.hiddenGroups) {
        vm.filterLiveCategories(groupQuery)
    }
    // distinctBy: some providers list a channel twice, and list keys must be unique.
    val channelsByGroup = remember(vm.liveStreams) {
        vm.liveStreams.groupBy { it.categoryId }.mapValues { (_, v) -> v.distinctBy { it.streamId } }
    }
    val counts = remember(channelsByGroup) { channelsByGroup.mapValues { it.value.size } }
    val favorites = remember(vm.liveStreams, vm.favorites) { vm.favoriteChannels.distinctBy { it.streamId } }
    val groupChannels = when (selectedGroupId) {
        null -> emptyList()
        FAVORITES_GROUP -> favorites
        else -> channelsByGroup[selectedGroupId].orEmpty()
    }
    val channels = remember(groupChannels, channelQuery) {
        if (channelQuery.isBlank()) groupChannels
        else groupChannels.filter { it.name.contains(channelQuery, ignoreCase = true) }
    }
    val groupTitle = when (selectedGroupId) {
        null -> stringResource(R.string.choose_group)
        FAVORITES_GROUP -> stringResource(R.string.tab_favorites)
        else -> vm.liveCategories.firstOrNull { it.categoryId == selectedGroupId }?.categoryName ?: ""
    }

    // ---- Playback control ----
    fun ensurePlayer(): ExoPlayer = exo ?: PlayerFactory.create(context).also { player ->
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Fell too far behind a live HLS stream: jump back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Provider doesn't serve this channel as HLS: retry once as MPEG-TS.
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (uri != null && uri.endsWith(".m3u8") && tsFallbackTried.add(uri)) {
                    player.setMediaItem(MediaItem.fromUri(uri.removeSuffix(".m3u8") + ".ts"))
                    player.prepare()
                    player.play()
                    return
                }
                Toast.makeText(context, context.getString(R.string.player_error), Toast.LENGTH_LONG).show()
            }
        })
        exo = player
    }

    fun play(list: List<LiveStream>, index: Int, goFullscreen: Boolean = true) {
        if (list.isEmpty()) return
        val i = ((index % list.size) + list.size) % list.size // wrap around when zapping
        val channel = list[i]
        session = LiveSession(selectedGroupId, list, i)
        if (videoPlaceholder == null) {
            val player = ensurePlayer()
            player.setMediaItem(MediaItem.fromUri(vm.liveUrl(channel.streamId)))
            player.prepare()
            player.playWhenReady = true
        }
        vm.loadShortEpg(channel.streamId)
        if (goFullscreen) fullscreen = true
        bannerTick++
    }

    fun zap(delta: Int) {
        val s = session ?: return
        if (s.channels.size < 2) return
        play(s.channels, s.index + delta, goFullscreen = fullscreen)
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        session = null
        fullscreen = false
    }

    fun openGuide(channel: LiveStream) {
        // Resume this channel in split view when the user comes back from the full guide.
        vm.resumeLiveSession = session
        onOpenGuide(channel.streamId)
    }

    // ---- Effects ----
    LaunchedEffect(selectedGroupId) {
        vm.lastLiveGroupId = selectedGroupId
        channelQuery = ""
        channelListState.scrollToItem(0)
    }

    LaunchedEffect(Unit) {
        val resume = vm.resumeLiveSession
        if (resume != null) {
            vm.resumeLiveSession = null
            selectedGroupId = resume.groupId
            play(resume.channels, resume.index, goFullscreen = false)
        } else {
            groupFocus.requestAfterLayout()
        }
    }

    LaunchedEffect(focusChannelsTick) {
        if (focusChannelsTick > 0) {
            withFrameNanosSafe()
            if (runCatching { firstChannelFocus.requestFocus() }.isFailure) {
                runCatching { playingChannelFocus.requestFocus() }
            }
        }
    }

    // Back from full screen: put focus on the channel that is playing.
    LaunchedEffect(fullscreen) {
        val s = session
        if (!fullscreen && s != null) {
            val index = channels.indexOfFirst { it.streamId == s.channels[s.index].streamId }
            if (index >= 0) {
                channelListState.scrollToItem(index)
                playingChannelFocus.requestAfterLayout()
            }
        }
    }

    // Full screen: hide the system bars, and turn a phone to landscape.
    val isPhone = LocalConfiguration.current.smallestScreenWidthDp < 600
    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        if (isPhone) {
            activity?.requestedOrientation = if (fullscreen)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    // Pause in the background, resume when the app returns; release when leaving the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exo?.pause()
                Lifecycle.Event.ON_START -> if (session != null) exo?.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exo?.release()
            exo = null
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            if (isPhone) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---- Back: full screen -> split view -> stop playback -> (phone) groups -> Home ----
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen && session != null) { stop() }

    // ---- Layout ----
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var slotCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NightBg)
            .onGloballyPositioned { rootCoords = it }
    ) {
        val wide = maxWidth >= 600.dp
        val screenWidth = maxWidth
        BackHandler(enabled = !wide && !fullscreen && session == null && selectedGroupId != null) {
            selectedGroupId = null
        }

        val playing = session?.let { it.channels[it.index] }

        val onSelectGroup: (String) -> Unit = { id ->
            selectedGroupId = id
            focusChannelsTick++
        }
        val channelsPanel: @Composable (Modifier, Boolean) -> Unit = { modifier, showBack ->
            ChannelsPanel(
                modifier = modifier,
                vm = vm,
                title = groupTitle,
                channels = channels,
                isFavoritesGroup = selectedGroupId == FAVORITES_GROUP,
                query = channelQuery,
                onQueryChange = { channelQuery = it },
                playingStreamId = playing?.streamId,
                nowSeconds = nowSeconds,
                listState = channelListState,
                firstFocus = firstChannelFocus,
                playingFocus = playingChannelFocus,
                onBack = if (showBack) ({ selectedGroupId = null }) else null,
                onPlay = { index ->
                    val target = channels[index]
                    if (playing?.streamId == target.streamId) {
                        fullscreen = true
                    } else {
                        play(channels, index)
                    }
                },
                onToggleFavorite = { vm.toggleFavorite("live:${it.streamId}") }
            )
        }
        val groupsPanel: @Composable (Modifier) -> Unit = { modifier ->
            GroupsPanel(
                modifier = modifier,
                groups = groups,
                counts = counts,
                favoritesCount = favorites.size,
                selectedId = selectedGroupId,
                onSelect = onSelectGroup,
                query = groupQuery,
                onQueryChange = { groupQuery = it },
                focusRequester = groupFocus
            )
        }

        // On TV/tablet, open on a group straight away so the channel list isn't empty.
        LaunchedEffect(wide, groups, favorites.isNotEmpty()) {
            if (wide && selectedGroupId == null) {
                selectedGroupId = if (favorites.isNotEmpty()) FAVORITES_GROUP else groups.firstOrNull()?.categoryId
            }
        }

        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = stringResource(R.string.tab_live),
                onBack = {
                    when {
                        session != null -> { stop() }
                        !wide && selectedGroupId != null -> { selectedGroupId = null }
                        else -> { onBack() }
                    }
                },
                actions = { ClockText(nowSeconds, Modifier.padding(end = 12.dp)) }
            )
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    groupsPanel(Modifier.width(if (playing != null) 220.dp else 260.dp).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    channelsPanel(Modifier.weight(1f).fillMaxHeight(), false)
                    if (playing != null) {
                        VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        PreviewPanel(
                            modifier = Modifier
                                .width((screenWidth * 0.36f).coerceIn(300.dp, 460.dp))
                                .fillMaxHeight(),
                            vm = vm,
                            channel = playing,
                            nowSeconds = nowSeconds,
                            onSlotPositioned = { slotCoords = it },
                            onFullscreen = { fullscreen = true },
                            onGuide = { openGuide(playing) }
                        )
                    }
                }
            } else if (playing != null) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                            .onGloballyPositioned { slotCoords = it }
                    )
                    CompactNowBar(
                        vm = vm,
                        channel = playing,
                        nowSeconds = nowSeconds,
                        onFullscreen = { fullscreen = true },
                        onGuide = { openGuide(playing) }
                    )
                    channelsPanel(Modifier.weight(1f), false)
                }
            } else if (selectedGroupId == null) {
                groupsPanel(Modifier.fillMaxSize())
            } else {
                channelsPanel(Modifier.fillMaxSize(), true)
            }
        }

        // ---- Video layer: over the preview slot, or the whole screen ----
        val livePlayer = exo
        if (playing != null && (livePlayer != null || videoPlaceholder != null)) {
            val density = LocalDensity.current
            val bounds: Rect? = run {
                val root = rootCoords
                val slot = slotCoords
                if (!fullscreen && root != null && slot != null && root.isAttached && slot.isAttached) {
                    root.localBoundingBoxOf(slot, clipBounds = false)
                } else null
            }
            val videoModifier = when {
                fullscreen -> Modifier.fillMaxSize()
                bounds != null -> with(density) {
                    Modifier
                        .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .size(bounds.width.toDp(), bounds.height.toDp())
                }
                else -> Modifier.size(0.dp)
            }
            Box(videoModifier.background(Color.Black)) {
                if (videoPlaceholder != null) videoPlaceholder() else AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false // live TV: no seek bar, no LIVE badge
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                            player = livePlayer
                        }
                    },
                    update = { it.player = livePlayer },
                    modifier = Modifier.fillMaxSize()
                )
                if (fullscreen) {
                    FullscreenControls(
                        bannerTick = bannerTick,
                        onZap = { zap(it) },
                        onShowBanner = { bannerTick++ }
                    )
                    LiveBanner(
                        vm = vm,
                        session = session,
                        nowSeconds = nowSeconds,
                        bannerTick = bannerTick,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    // Split view: tapping the video goes (back) to full screen.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures(onTap = { fullscreen = true }) }
                    )
                }
            }
        }
    }
}

// ---------------- Full screen ----------------

/**
 * Invisible layer over the full-screen video that owns focus and input:
 * remote up/down and channel up/down zap, OK/left/right/info shows the banner;
 * on a touch screen a tap shows the banner and a vertical swipe zaps.
 */
@Composable
private fun FullscreenControls(bannerTick: Int, onZap: (Int) -> Unit, onShowBanner: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestAfterLayout() }
    var dragTotal by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val action: (() -> Unit)? = when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> ({ onZap(1) })
                    Key.DirectionDown, Key.ChannelDown -> ({ onZap(-1) })
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    Key.DirectionLeft, Key.DirectionRight, Key.Info -> onShowBanner
                    else -> null
                }
                if (action == null) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) action()
                true // consume key down and key up, so nothing behind the video reacts
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { onShowBanner() }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onVerticalDrag = { _, dy -> dragTotal += dy },
                    onDragEnd = {
                        if (abs(dragTotal) > 120f) onZap(if (dragTotal < 0) 1 else -1) // swipe up = next
                    }
                )
            }
    )
}

/** Channel + current programme at the bottom of the full-screen video, for 3 seconds. */
@Composable
private fun LiveBanner(
    vm: MainViewModel,
    session: LiveSession?,
    nowSeconds: Long,
    bannerTick: Int,
    modifier: Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(bannerTick) {
        if (bannerTick > 0) {
            visible = true
            delay(3_000)
            visible = false
        }
    }
    val s = session ?: return
    val channel = s.channels[s.index]
    val epg = vm.nowAndNext(channel.streamId, nowSeconds)
    val offset = vm.epgOffsetHours

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60E1116))))
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (channel.num ?: (s.index + 1)).toString(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                ChannelLogo(channel, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val current = epg.current
                    if (current != null) {
                        Text(
                            timeRange(current, offset) + "   " + current.title,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        ProgrammeProgress(current, offset, nowSeconds, Modifier.fillMaxWidth(0.6f))
                    } else {
                        Text(stringResource(R.string.no_epg), color = Color.White.copy(alpha = 0.7f))
                    }
                    epg.next.firstOrNull()?.let { next ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.up_next) + "  " +
                                    formatEpochTime(next.startSeconds(offset)) + "  " + next.title,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ---------------- Channels panel ----------------

@Composable
private fun ChannelsPanel(
    modifier: Modifier,
    vm: MainViewModel,
    title: String,
    channels: List<LiveStream>,
    isFavoritesGroup: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    playingStreamId: Int?,
    nowSeconds: Long,
    listState: LazyListState,
    firstFocus: FocusRequester,
    playingFocus: FocusRequester,
    onBack: (() -> Unit)?,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (LiveStream) -> Unit
) {
    Column(modifier) {
        PanelHeader(
            title = title,
            subtitle = stringResource(R.string.count_channels, channels.size),
            query = query,
            onQueryChange = onQueryChange,
            searchHint = stringResource(R.string.search_channels),
            onBack = onBack
        )
        if (channels.isEmpty()) {
            EmptyBox(
                stringResource(
                    if (isFavoritesGroup && query.isBlank()) R.string.favorites_empty else R.string.no_results
                )
            )
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The timeline needs room; in a narrow panel show now / next as text instead.
            val timeline = maxWidth >= 560.dp
            val windowLength = if (maxWidth - ChannelColumnWidth > 900.dp) 3 * 3600L else 2 * 3600L
            val windowStart = nowSeconds - nowSeconds % 1800 // round down to the half hour

            Column(Modifier.fillMaxSize()) {
                if (timeline) TimeRuler(windowStart, windowLength, nowSeconds)
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, channel ->
                        LaunchedEffect(channel.streamId) { vm.loadShortEpg(channel.streamId) }
                        val isPlaying = channel.streamId == playingStreamId
                        val requester = when {
                            isPlaying -> playingFocus
                            index == 0 -> firstFocus
                            else -> null
                        }
                        val isFavorite = vm.isFavorite("live:${channel.streamId}")
                        if (timeline) {
                            TimelineChannelRow(
                                vm = vm,
                                channel = channel,
                                number = channel.num ?: (index + 1),
                                isPlaying = isPlaying,
                                isFavorite = isFavorite,
                                nowSeconds = nowSeconds,
                                windowStart = windowStart,
                                windowLength = windowLength,
                                focusRequester = requester,
                                onClick = { onPlay(index) },
                                onLongClick = { onToggleFavorite(channel) }
                            )
                        } else {
                            CompactChannelRow(
                                vm = vm,
                                channel = channel,
                                number = channel.num ?: (index + 1),
                                isPlaying = isPlaying,
                                isFavorite = isFavorite,
                                nowSeconds = nowSeconds,
                                focusRequester = requester,
                                onClick = { onPlay(index) },
                                onLongClick = { onToggleFavorite(channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, windowLength: Long, nowSeconds: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 8.dp)
    ) {
        Spacer(Modifier.width(ChannelColumnWidth))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp)
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            var t = windowStart
            while (t < windowStart + windowLength) {
                val x = ((t - windowStart).toFloat() / windowLength * widthPx).roundToInt()
                Text(
                    formatEpochTime(t),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset { IntOffset(x, 0) }
                )
                t += 1800
            }
            val nowX = (nowSeconds - windowStart).toFloat() / windowLength * widthPx
            Box(
                Modifier
                    .offset { IntOffset(nowX.roundToInt(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun TimelineChannelRow(
    vm: MainViewModel,
    channel: LiveStream,
    number: Int,
    isPlaying: Boolean,
    isFavorite: Boolean,
    nowSeconds: Long,
    windowStart: Long,
    windowLength: Long,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .tvClickable(cornerRadius = 10, focusRequester = focusRequester, onLongClick = onLongClick, onClick = onClick)
            .background(
                if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelIdentity(
            channel = channel,
            number = number,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            modifier = Modifier.width(ChannelColumnWidth)
        )
        TimelineStrip(
            vm = vm,
            streamId = channel.streamId,
            nowSeconds = nowSeconds,
            windowStart = windowStart,
            windowLength = windowLength,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}

/** The coming programmes of one channel as blocks on the shared time window. */
@Composable
private fun TimelineStrip(
    vm: MainViewModel,
    streamId: Int,
    nowSeconds: Long,
    windowStart: Long,
    windowLength: Long,
    modifier: Modifier
) {
    val offset = vm.epgOffsetHours
    val windowEnd = windowStart + windowLength
    val listings = vm.epgCache[streamId]?.listings
    val visible = listings.orEmpty().filter {
        it.stopSeconds(offset) > windowStart && it.startSeconds(offset) < windowEnd
    }
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        if (visible.isEmpty()) {
            Text(
                if (listings == null) "" else stringResource(R.string.no_epg),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
            )
        }
        visible.forEach { programme ->
            val start = programme.startSeconds(offset).coerceAtLeast(windowStart)
            val end = programme.stopSeconds(offset).coerceAtMost(windowEnd)
            if (end > start) {
                val x = (start - windowStart).toFloat() / windowLength * widthPx
                val w = (end - start).toFloat() / windowLength * widthPx
                val isNow = programme.startSeconds(offset) <= nowSeconds && programme.stopSeconds(offset) > nowSeconds
                val wDp = with(density) { w.toDp() }
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), 0) }
                        .width((wDp - 3.dp).coerceAtLeast(1.dp))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (wDp > 40.dp) {
                        Text(
                            programme.title,
                            fontSize = 13.sp,
                            fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        val nowX = (nowSeconds - windowStart).toFloat() / windowLength * widthPx
        Box(
            Modifier
                .offset { IntOffset(nowX.roundToInt(), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        )
    }
}

@Composable
private fun CompactChannelRow(
    vm: MainViewModel,
    channel: LiveStream,
    number: Int,
    isPlaying: Boolean,
    isFavorite: Boolean,
    nowSeconds: Long,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val epg = vm.nowAndNext(channel.streamId, nowSeconds)
    val offset = vm.epgOffsetHours
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .tvClickable(cornerRadius = 10, focusRequester = focusRequester, onLongClick = onLongClick, onClick = onClick)
            .background(
                if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            number.toString(),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp)
        )
        ChannelLogo(channel, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val current = epg.current
            if (current != null) {
                Text(
                    current.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                ProgrammeProgress(current, offset, nowSeconds, Modifier.fillMaxWidth())
            }
            epg.next.firstOrNull()?.let { next ->
                Text(
                    formatEpochTime(next.startSeconds(offset)) + "  " + next.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (isFavorite) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp)
            )
        }
    }
}

@Composable
private fun ChannelIdentity(
    channel: LiveStream,
    number: Int,
    isPlaying: Boolean,
    isFavorite: Boolean,
    modifier: Modifier
) {
    Row(modifier.padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            number.toString(),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp)
        )
        ChannelLogo(channel, 40.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            channel.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isFavorite) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ---------------- Split view: preview of the playing channel ----------------

@Composable
private fun PreviewPanel(
    modifier: Modifier,
    vm: MainViewModel,
    channel: LiveStream,
    nowSeconds: Long,
    onSlotPositioned: (LayoutCoordinates) -> Unit,
    onFullscreen: () -> Unit,
    onGuide: () -> Unit
) {
    val epg = vm.nowAndNext(channel.streamId, nowSeconds)
    val offset = vm.epgOffsetHours
    val isFavorite = vm.isFavorite("live:${channel.streamId}")

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp)
    ) {
        // The video is drawn over this slot by the screen's video layer.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .onGloballyPositioned { onSlotPositioned(it) }
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 36.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        val current = epg.current
        if (current != null) {
            Text(
                stringResource(R.string.epg_now).uppercase() + "  ·  " + timeRange(current, offset),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(current.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            ProgrammeProgress(current, offset, nowSeconds, Modifier.fillMaxWidth())
            if (current.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    current.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                stringResource(R.string.no_epg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFullscreen) {
                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_fullscreen))
            }
            OutlinedButton(onClick = { vm.toggleFavorite("live:${channel.streamId}") }) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.favorite_remove else R.string.favorite_add
                    ),
                    modifier = Modifier.size(18.dp)
                )
            }
            OutlinedButton(onClick = onGuide) {
                Icon(Icons.Default.ViewList, contentDescription = stringResource(R.string.epg_title), modifier = Modifier.size(18.dp))
            }
        }

        if (epg.next.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.up_next),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Each row is a focus stop, so the remote can move down and the panel scrolls along.
            epg.next.take(5).forEach { programme ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tvClickable(cornerRadius = 8, onClick = onGuide)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text(
                        formatEpochTime(programme.startSeconds(offset)),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(52.dp)
                    )
                    Text(
                        programme.title,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Phone split view: the line under the video with the programme and quick actions. */
@Composable
private fun CompactNowBar(
    vm: MainViewModel,
    channel: LiveStream,
    nowSeconds: Long,
    onFullscreen: () -> Unit,
    onGuide: () -> Unit
) {
    val epg = vm.nowAndNext(channel.streamId, nowSeconds)
    val offset = vm.epgOffsetHours
    val isFavorite = vm.isFavorite("live:${channel.streamId}")
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val current = epg.current
            Text(
                if (current != null) timeRange(current, offset) + "  " + current.title
                else stringResource(R.string.no_epg),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TvIconButton(onClick = { vm.toggleFavorite("live:${channel.streamId}") }) {
            Icon(
                if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(if (isFavorite) R.string.favorite_remove else R.string.favorite_add),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TvIconButton(onClick = onGuide) {
            Icon(Icons.Default.ViewList, contentDescription = stringResource(R.string.epg_title))
        }
        TvIconButton(onClick = onFullscreen) {
            Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.action_fullscreen))
        }
    }
}

// ---------------- Small shared pieces ----------------

@Composable
private fun ChannelLogo(channel: LiveStream, size: androidx.compose.ui.unit.Dp) {
    Artwork(
        url = channel.streamIcon,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
    )
}

@Composable
private fun ProgrammeProgress(programme: EpgListing, offsetHours: Int, nowSeconds: Long, modifier: Modifier) {
    val start = programme.startSeconds(offsetHours)
    val stop = programme.stopSeconds(offsetHours)
    val progress = if (stop > start) ((nowSeconds - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    )
}

private fun timeRange(programme: EpgListing, offsetHours: Int): String =
    formatEpochTime(programme.startSeconds(offsetHours)) + " – " + formatEpochTime(programme.stopSeconds(offsetHours))

private suspend fun withFrameNanosSafe() {
    androidx.compose.runtime.withFrameNanos { }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
