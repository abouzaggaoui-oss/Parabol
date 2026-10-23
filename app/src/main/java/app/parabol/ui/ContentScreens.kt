package app.parabol.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.parabol.MainViewModel
import app.parabol.R
import app.parabol.data.EpgListing
import app.parabol.data.LiveStream
import app.parabol.data.SeriesStream
import app.parabol.data.VodStream
import app.parabol.PlayerQueue
import app.parabol.playQueue
import app.parabol.playSingle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats epoch seconds as HH:mm in the device's own timezone. */
fun formatEpochTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
}

// ---------------- Series ----------------

@Composable
fun SeriesDetailScreen(vm: MainViewModel, seriesId: Int) {
    val context = LocalContext.current
    LaunchedEffect(seriesId) { vm.loadSeriesInfo(seriesId) }

    val info = vm.seriesInfo
    if (vm.seriesInfoLoading) {
        LoadingBox()
        return
    }
    if (info == null) {
        // Previously this showed an endless spinner when loading failed.
        ErrorBox(
            text = stringResource(R.string.load_failed),
            onRetry = { vm.loadSeriesInfo(seriesId) }
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Artwork(
                    url = info.info?.cover,
                    modifier = Modifier
                        .width(110.dp)
                        .height(160.dp),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        info.info?.name ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!info.info?.plot.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            info.info?.plot ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        val seasons = info.episodes?.toSortedMap(compareBy<String> { it.toIntOrNull() ?: 0 })
        seasons?.forEach { (seasonNumber, unsortedEpisodes) ->
            val episodes = unsortedEpisodes.sortedBy { it.episodeNumber.toIntOrNull() ?: Int.MAX_VALUE }
            item(key = "season-$seasonNumber") {
                Text(
                    text = stringResource(R.string.season, seasonNumber) +
                            "  ·  ${episodes.size} " + stringResource(R.string.episodes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            itemsIndexed(episodes, key = { _, ep -> "ep-${ep.id}" }) { epIndex, ep ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvClickable {
                            playQueue(
                                context,
                                episodes.map {
                                    PlayerQueue.Item(
                                        vm.episodeUrl(it.id, it.containerExtension),
                                        it.title ?: ""
                                    )
                                },
                                epIndex
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ep.episodeNumber,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp)
                        )
                        Text(
                            text = ep.title ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ---------------- EPG (TV guide) ----------------

@Composable
fun EpgScreen(vm: MainViewModel, streamId: Int) {
    LaunchedEffect(streamId) { vm.loadFullEpg(streamId) }
    val context = LocalContext.current

    val channel = vm.liveStreams.firstOrNull { it.streamId == streamId }
    val listings = vm.fullEpg

    Column(Modifier.fillMaxSize()) {
        Text(
            text = (channel?.name ?: "") + " — " + stringResource(R.string.epg_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
        when {
            vm.epgLoading || listings == null -> LoadingBox()
            listings.isEmpty() -> EmptyBox(stringResource(R.string.epg_empty))
            else -> {
                val nowSeconds = System.currentTimeMillis() / 1000
                val offset = vm.epgOffsetHours
                val sorted = remember(listings) {
                    listings.sortedBy { it.startSeconds(0) }
                }
                // Open the guide at the programme that is on now, instead of days in the past.
                val listState = rememberLazyListState()
                LaunchedEffect(sorted, offset) {
                    val nowIndex = sorted.indexOfFirst { it.stopSeconds(offset) >= nowSeconds }
                    if (nowIndex > 0) listState.scrollToItem(nowIndex)
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sorted.size) { index ->
                        val item = sorted[index]
                        val start = item.startSeconds(offset)
                        val stop = item.stopSeconds(offset)
                        val isNow = start <= nowSeconds && stop >= nowSeconds
                        val isFuture = start > nowSeconds
                        val isPast = stop < nowSeconds

                        val alreadyScheduled = vm.scheduledRecordings.any {
                            it.streamId == streamId && it.startEpochSeconds in start until maxOf(stop, start + 1)
                        }

                        val catchupAvailable = isPast && channel != null && channel.hasCatchup &&
                                (nowSeconds - start) <= channel.catchupDurationDays.toLong() * 86400

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isNow)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatEpochTime(start) + " – " + formatEpochTime(stop),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (isNow) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.epg_now).uppercase(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (item.description.isNotBlank()) {
                                    Text(
                                        item.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if ((isFuture || isNow) && channel != null) {
                                    Spacer(Modifier.height(8.dp))
                                    if (alreadyScheduled) {
                                        OutlinedButton(onClick = {
                                            vm.scheduledRecordings.firstOrNull {
                                                it.streamId == streamId && it.startEpochSeconds in start until maxOf(stop, start + 1)
                                            }?.let { vm.cancelScheduledRecording(it) }
                                        }) {
                                            Text(stringResource(R.string.recording_scheduled_cancel))
                                        }
                                    } else {
                                        Button(onClick = {
                                            vm.scheduleRecording(channel, item.title, start, stop)
                                        }) {
                                            Text(
                                                stringResource(
                                                    if (isNow) R.string.recording_now
                                                    else R.string.recording_schedule
                                                )
                                            )
                                        }
                                    }
                                }

                                if (catchupAvailable && channel != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        val url = vm.catchupUrl(
                                            streamId,
                                            item.start,
                                            start,
                                            ((stop - start) / 60).toInt().coerceAtLeast(1)
                                        )
                                        playSingle(context, url, item.title)
                                    }) {
                                        Text(stringResource(R.string.catchup_watch))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// ---------------- Shared components ----------------

@Composable
fun SearchField(
    query: String,
    hint: String = stringResource(R.string.search),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        label = { Text(hint) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Poster tile for movies and series: one focus target. Long-press toggles the favorite,
 * shown as a star badge on the poster.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Column(
        Modifier.tvClickable(
            cornerRadius = 10,
            focusRequester = focusRequester,
            onLongClick = onToggleFavorite,
            onClick = onClick
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Artwork(
                url = imageUrl,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = stringResource(R.string.tab_favorites),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))
                        .padding(4.dp)
                        .size(16.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}
