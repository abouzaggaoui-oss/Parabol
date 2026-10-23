package app.parabol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parabol.MainViewModel
import app.parabol.NightBg
import app.parabol.R
import app.parabol.playSingle

enum class BrowseType { MOVIES, SERIES }

/** One poster in the Movies/Series grid, independent of whether it is a movie or a series. */
private data class BrowseItem(
    val id: Int,
    val name: String,
    val image: String?,
    val categoryId: String?,
    val favoriteKey: String,
    val extension: String?
)

/**
 * Movies and Series: the same layout as Live TV — groups menu on the left (with search and
 * Favorites) and a poster grid with search on the right.
 * On a phone in portrait the groups and the grid are shown one after the other.
 */
@Composable
fun BrowseScreen(
    vm: MainViewModel,
    type: BrowseType,
    onBack: () -> Unit,
    onOpenSeries: (Int) -> Unit,
    initialGroupId: String? = null
) {
    val context = LocalContext.current
    val isMovies = type == BrowseType.MOVIES

    var groupQuery by rememberSaveable { mutableStateOf("") }
    var titleQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroupId by rememberSaveable { mutableStateOf(initialGroupId) }

    val groupFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    var focusItemsTick by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()

    val categories = if (isMovies) vm.vodCategories else vm.seriesCategories
    val groups = remember(groupQuery, categories, vm.hiddenGroups) {
        if (isMovies) vm.filterVodCategories(groupQuery) else vm.filterSeriesCategories(groupQuery)
    }
    val allItems = remember(vm.vodStreams, vm.seriesList, type) {
        if (isMovies) vm.vodStreams.map {
            BrowseItem(it.streamId, it.name, it.streamIcon, it.categoryId, "vod:${it.streamId}", it.containerExtension)
        } else vm.seriesList.map {
            BrowseItem(it.seriesId, it.name, it.cover, it.categoryId, "series:${it.seriesId}", null)
        }
    }
    // distinctBy: some providers list the same title twice, and grid keys must be unique.
    val itemsByGroup = remember(allItems) { allItems.groupBy { it.categoryId }.mapValues { (_, v) -> v.distinctBy { it.favoriteKey } } }
    val counts = remember(itemsByGroup) { itemsByGroup.mapValues { it.value.size } }
    val favorites = remember(allItems, vm.favorites) { allItems.filter { vm.isFavorite(it.favoriteKey) }.distinctBy { it.favoriteKey } }
    val groupItems = when (selectedGroupId) {
        null -> emptyList()
        FAVORITES_GROUP -> favorites
        else -> itemsByGroup[selectedGroupId].orEmpty()
    }
    val items = remember(groupItems, titleQuery) {
        if (titleQuery.isBlank()) groupItems
        else groupItems.filter { it.name.contains(titleQuery, ignoreCase = true) }
    }
    val groupTitle = when (selectedGroupId) {
        null -> stringResource(R.string.choose_group)
        FAVORITES_GROUP -> stringResource(R.string.tab_favorites)
        else -> categories.firstOrNull { it.categoryId == selectedGroupId }?.categoryName ?: ""
    }

    LaunchedEffect(selectedGroupId) {
        titleQuery = ""
        gridState.scrollToItem(0)
    }
    LaunchedEffect(Unit) { groupFocus.requestAfterLayout() }
    LaunchedEffect(focusItemsTick) { if (focusItemsTick > 0) firstItemFocus.requestAfterLayout() }

    val open: (BrowseItem) -> Unit = { item ->
        if (isMovies) playSingle(context, vm.movieUrl(item.id, item.extension), item.name)
        else onOpenSeries(item.id)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NightBg)
    ) {
        val wide = maxWidth >= 600.dp
        BackHandler(enabled = !wide && selectedGroupId != null) { selectedGroupId = null }
        LaunchedEffect(wide, groups, favorites.isNotEmpty()) {
            if (wide && selectedGroupId == null) {
                selectedGroupId = if (favorites.isNotEmpty()) FAVORITES_GROUP else groups.firstOrNull()?.categoryId
            }
        }

        val groupsPanel: @Composable (Modifier) -> Unit = { modifier ->
            GroupsPanel(
                modifier = modifier,
                groups = groups,
                counts = counts,
                favoritesCount = favorites.size,
                selectedId = selectedGroupId,
                onSelect = { id ->
                    selectedGroupId = id
                    focusItemsTick++
                },
                query = groupQuery,
                onQueryChange = { groupQuery = it },
                focusRequester = groupFocus
            )
        }
        val gridPanel: @Composable (Modifier, Boolean) -> Unit = { modifier, showBack ->
            Column(modifier) {
                PanelHeader(
                    title = groupTitle,
                    subtitle = stringResource(R.string.count_titles, items.size),
                    query = titleQuery,
                    onQueryChange = { titleQuery = it },
                    searchHint = stringResource(R.string.search_titles),
                    onBack = if (showBack) ({ selectedGroupId = null }) else null
                )
                if (items.isEmpty()) {
                    EmptyBox(
                        stringResource(
                            if (selectedGroupId == FAVORITES_GROUP && titleQuery.isBlank()) R.string.favorites_empty
                            else R.string.no_results
                        )
                    )
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = if (wide) 140.dp else 110.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(items, key = { _, item -> item.favoriteKey }) { index, item ->
                            PosterCard(
                                title = item.name,
                                imageUrl = item.image,
                                isFavorite = vm.isFavorite(item.favoriteKey),
                                onToggleFavorite = { vm.toggleFavorite(item.favoriteKey) },
                                onClick = { open(item) },
                                focusRequester = if (index == 0) firstItemFocus else null
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = stringResource(if (isMovies) R.string.tab_movies else R.string.tab_series),
                onBack = {
                    if (!wide && selectedGroupId != null) {
                        selectedGroupId = null
                    } else {
                        onBack()
                    }
                }
            )
            when {
                wide -> Row(Modifier.fillMaxSize()) {
                    groupsPanel(Modifier.width(260.dp).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    gridPanel(Modifier.weight(1f).fillMaxHeight(), false)
                }
                selectedGroupId == null -> groupsPanel(Modifier.fillMaxSize())
                else -> gridPanel(Modifier.fillMaxSize(), true)
            }
        }
    }
}
