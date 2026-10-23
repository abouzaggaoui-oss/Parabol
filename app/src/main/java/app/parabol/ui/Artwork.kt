package app.parabol.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

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

/** Poster, cover or channel logo loaded from the provider. */
@Composable
fun Artwork(url: String?, contentScale: ContentScale, modifier: Modifier = Modifier) {
    val override = LocalArtworkOverride.current?.invoke(url)
    if (override != null) {
        Image(painter = override, contentDescription = null, contentScale = contentScale, modifier = modifier)
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = modifier)
    }
}
