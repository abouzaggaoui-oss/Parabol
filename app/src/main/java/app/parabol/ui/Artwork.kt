package app.parabol.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Lets the README screenshot tests show generated, copyright-free artwork instead of loading
 * posters and channel logos from a provider. Always null in the app itself.
 */
val LocalArtworkOverride = staticCompositionLocalOf<((String?) -> Painter?)?> { null }

/**
 * Screenshot tests draw a still picture in the video area instead of starting a real player.
 * Always null in the app itself.
 */
val LocalLiveVideoPlaceholder = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * Poster, cover or channel logo loaded from the provider. When there is no image, or it
 * can't be loaded, [fallbackText] (e.g. the movie title) is shown instead of an empty tile.
 */
@Composable
fun Artwork(url: String?, contentScale: ContentScale, modifier: Modifier = Modifier, fallbackText: String? = null) {
    val override = LocalArtworkOverride.current?.invoke(url)
    if (override != null) {
        Image(painter = override, contentDescription = null, contentScale = contentScale, modifier = modifier)
        return
    }
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier) {
        if (failed && fallbackText != null) {
            Text(
                fallbackText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp)
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                onState = { failed = it is AsyncImagePainter.State.Error },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
