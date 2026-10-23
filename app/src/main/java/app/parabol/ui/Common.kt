package app.parabol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parabol.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Time ----------------

/** Current time in epoch seconds, refreshed every 30 seconds (drives clocks and progress bars). */
@Composable
fun rememberNowSeconds(): Long {
    val now by produceState(initialValue = System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }
    return now
}

@Composable
fun ClockText(nowSeconds: Long, modifier: Modifier = Modifier) {
    Text(
        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowSeconds * 1000)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

// ---------------- Screen chrome ----------------

/** Top bar with a back arrow, a title and optional actions, used by secondary screens. */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvIconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
        actions()
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = title, onBack = onBack)
        content()
    }
}

// ---------------- Panels ----------------

/**
 * Header for a browse panel: a title (and subtitle) with a search icon that expands into a
 * search field. The field only takes focus when the user opens it, so moving through the
 * list with a remote never pops up the keyboard by accident.
 */
@Composable
fun PanelHeader(
    title: String,
    subtitle: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    searchHint: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                TvIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TvIconButton(onClick = {
                if (searchOpen) {
                    onQueryChange("")
                    searchOpen = false
                } else {
                    searchOpen = true
                }
            }) {
                Icon(
                    if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = searchHint
                )
            }
        }
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(searchHint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(fieldFocus)
            )
            LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
        }
    }
}

/** A row in a side menu (groups). Highlighted when selected; one focus target. */
@Composable
fun MenuRow(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .tvClickable(focusRequester = focusRequester, onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(if (selected) accent else Color.Transparent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(
                count.toString(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------- States ----------------

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading))
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ErrorBox(text: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}


@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

