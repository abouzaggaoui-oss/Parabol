package app.parabol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parabol.R
import app.parabol.data.Category

/** Pseudo group id for the user's favorites, shown at the top of every groups menu. */
const val FAVORITES_GROUP = "__favorites__"

/**
 * Left-hand groups menu shared by Live TV, Movies and Series: a search toggle at the top,
 * "Favorites" and then the groups. Which groups appear is chosen in Settings › Manage groups.
 *
 * [focusRequester] is attached to the selected group (or the first row), so a screen can
 * put the remote's initial focus there.
 */
@Composable
fun GroupsPanel(
    modifier: Modifier,
    groups: List<Category>,
    counts: Map<String?, Int>,
    favoritesCount: Int,
    selectedId: String?,
    onSelect: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester
) {
    // Open the list with the selected group in view (so it is composed and can take focus),
    // keeping a few rows above it — e.g. Favorites — visible as well.
    val selectedIndex = remember(groups, selectedId) {
        if (selectedId == null || selectedId == FAVORITES_GROUP) 0
        else (groups.indexOfFirst { it.categoryId == selectedId } + 1).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 4).coerceAtLeast(0))
    val focusFavorites = selectedId == null || selectedId == FAVORITES_GROUP ||
            groups.none { it.categoryId == selectedId }

    Column(modifier) {
        PanelHeader(
            title = stringResource(R.string.groups_title),
            subtitle = null,
            query = query,
            onQueryChange = onQueryChange,
            searchHint = stringResource(R.string.search_groups)
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = FAVORITES_GROUP) {
                MenuRow(
                    label = stringResource(R.string.tab_favorites),
                    count = favoritesCount,
                    selected = selectedId == FAVORITES_GROUP,
                    onClick = { onSelect(FAVORITES_GROUP) },
                    focusRequester = if (focusFavorites) focusRequester else null,
                    leading = {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            items(groups, key = { it.categoryId }) { group ->
                MenuRow(
                    label = group.categoryName,
                    count = counts[group.categoryId],
                    selected = selectedId == group.categoryId,
                    onClick = { onSelect(group.categoryId) },
                    focusRequester = if (!focusFavorites && group.categoryId == selectedId) focusRequester else null
                )
            }
        }
    }
}
