package app.parabol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parabol.MainViewModel
import app.parabol.NightBg
import app.parabol.R
import app.parabol.data.Category
import app.parabol.data.CountryGroup
import java.util.Locale

private enum class GroupType(val key: String, val titleRes: Int) {
    LIVE("live", R.string.tab_live),
    MOVIES("vod", R.string.tab_movies),
    SERIES("series", R.string.tab_series)
}

/**
 * Settings › Manage groups: per type (Live TV / Movies / Series) choose which countries are
 * shown — one tap turns all groups of a country on or off — and fine-tune single groups below.
 */
@Composable
fun GroupsManagerScreen(vm: MainViewModel, onBack: () -> Unit) {
    var typeName by rememberSaveable { mutableStateOf(GroupType.LIVE.name) }
    val type = GroupType.valueOf(typeName)
    var search by rememberSaveable(typeName) { mutableStateOf("") }

    val categoriesOf: (GroupType) -> List<Category> = {
        when (it) {
            GroupType.LIVE -> vm.liveCategories
            GroupType.MOVIES -> vm.vodCategories
            GroupType.SERIES -> vm.seriesCategories
        }
    }
    val categories = categoriesOf(type)
    val countries = remember(type, categories, vm.hiddenGroups) { vm.countryGroups(type.key, categories) }
    val visible = countries.sumOf { it.visibleCount }
    val filtered = remember(categories, search) {
        categories
            .filter { search.isBlank() || it.categoryName.contains(search, ignoreCase = true) }
            .distinctBy { it.categoryId } // grid keys must be unique
    }
    val firstTile = rememberFirstItemFocus(ready = countries.isNotEmpty())

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NightBg)
    ) {
        val wide = maxWidth >= 600.dp
        val side = if (wide) 32.dp else 16.dp

        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = stringResource(R.string.manage_groups),
                onBack = onBack,
                actions = {
                    if (wide && categories.isNotEmpty()) VisibleSummary(visible, categories.size)
                }
            )

            Row(
                modifier = Modifier.padding(horizontal = side, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GroupType.values().forEach { t ->
                    TypeTab(
                        label = stringResource(t.titleRes),
                        count = categoriesOf(t).size,
                        selected = t == type,
                        onClick = { typeName = t.name }
                    )
                }
            }

            if (categories.isEmpty()) {
                EmptyBox(stringResource(R.string.no_groups))
                return@Column
            }

            LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(270.dp) else GridCells.Fixed(1),
                contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!wide) {
                    item(span = { GridItemSpan(maxLineSpan) }) { VisibleSummary(visible, categories.size) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(
                        stringResource(R.string.countries_title),
                        stringResource(R.string.countries_hint)
                    )
                }
                items(countries, key = { "c:${it.code}" }) { country ->
                    CountryTile(
                        country = country,
                        modifier = Modifier.tvClickable(
                            cornerRadius = 16,
                            focusRequester = if (country == countries.first()) firstTile else null
                        ) { vm.toggleCountry(type.key, country) }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(
                            stringResource(R.string.single_groups_title),
                            stringResource(R.string.single_groups_hint)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                label = { Text(stringResource(R.string.search_groups)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.setAllGroupsHidden(type.key, filtered, false) }) {
                                Text(stringResource(R.string.show_all))
                            }
                            TextButton(onClick = { vm.setAllGroupsHidden(type.key, filtered, true) }) {
                                Text(stringResource(R.string.hide_all))
                            }
                        }
                    }
                }
                items(filtered, key = { "g:${it.categoryId}" }, span = { GridItemSpan(maxLineSpan) }) { cat ->
                    GroupRow(
                        name = cat.categoryName,
                        visible = !vm.isGroupHidden(type.key, cat.categoryId),
                        onClick = { vm.toggleGroupHidden(type.key, cat.categoryId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VisibleSummary(visible: Int, total: Int) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Visibility,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.groups_visible_summary, visible, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        "$label · $count",
        color = fg,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .tvClickable(cornerRadius = 20, onClick = onClick)
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CountryTile(country: CountryGroup, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val on = !country.noneVisible
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(
                    if (on) accent else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                country.code ?: "··",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                countryName(country.code),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    country.allVisible -> stringResource(R.string.country_all_visible, country.total)
                    country.noneVisible -> stringResource(R.string.country_none_visible, country.total)
                    else -> stringResource(R.string.country_partly, country.visibleCount, country.total)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Visual only: the whole tile is the single click / focus target.
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = if (country.allVisible) accent else accent.copy(alpha = 0.45f),
                checkedThumbColor = Color.White
            )
        )
    }
}

@Composable
private fun GroupRow(name: String, visible: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvClickable(cornerRadius = 12, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Visual only, so the row stays one focus target on the remote.
        Checkbox(checked = visible, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

/** "NL" -> "Nederland" (in the phone's language). Handles the usual IPTV prefixes. */
@Composable
private fun countryName(code: String?): String {
    if (code == null) return stringResource(R.string.country_other)
    val locale = Locale.getDefault()
    val fromCountry = when (code) {
        "UK" -> Locale("", "GB").getDisplayCountry(locale)
        "EN", "AR" -> null // language prefixes: English, Arabic
        else -> if (code.length == 2) Locale("", code).getDisplayCountry(locale) else null
    }
    if (fromCountry != null && !fromCountry.equals(code, ignoreCase = true)) return fromCountry
    val language = Locale(code.lowercase()).getDisplayLanguage(locale)
    if (language.isNotBlank() && !language.equals(code, ignoreCase = true)) {
        return language.replaceFirstChar { it.titlecase(locale) }
    }
    return code
}
