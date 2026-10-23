package app.parabol.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.parabol.R

/** Creates ExoPlayer instances with the network settings Xtream providers need. */
@androidx.annotation.OptIn(UnstableApi::class)
object PlayerFactory {

    fun create(context: Context): ExoPlayer {
        // Many Xtream panels redirect http -> https (load balancers/CDNs). ExoPlayer does NOT
        // follow cross-protocol redirects by default, which made such streams silently fail.
        // Some providers also reject ExoPlayer's default user agent.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Parabol/2.2 (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // DefaultDataSource keeps content:// and file:// working (recordings).
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        // Movies from IPTV providers often have AC3 / E-AC3 / DTS / TrueHD audio, which many
        // phones and TV boxes can't decode. The FFmpeg extension decodes those in software when
        // the device can't. Decoder fallback tries another (e.g. software) video decoder when the
        // first one fails to start.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    /** A short Dutch/English explanation of why playback failed, for the error message. */
    fun describe(context: Context, error: PlaybackException): String {
        val cause = error.cause
        val res = when {
            cause is HttpDataSource.InvalidResponseCodeException ->
                return context.getString(R.string.player_error_http, cause.responseCode)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                R.string.player_error_network
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                R.string.player_error_codec
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                R.string.player_error_container
            else -> R.string.player_error_vod
        }
        return context.getString(res) + " (" + error.errorCodeName + ")"
    }
}
