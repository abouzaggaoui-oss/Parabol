package app.parabol

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.parabol.player.PlayerFactory

/**
 * Full-screen player for movies, series episodes, catch-up programmes and recordings:
 * seek bar, title bar with subtitle upload, resume-where-you-left-off, and episodes queued
 * as a playlist. Live TV is played inside the Live TV screen instead.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var titleView: TextView? = null

    private val positions by lazy { getSharedPreferences("parabol_positions", MODE_PRIVATE) }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) applyExternalSubtitle(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val queue = PlayerQueue.items
        if (queue.isEmpty()) {
            finish()
            return
        }

        // Fullscreen, keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val exo = PlayerFactory.create(this)
        player = exo
        val startIndex = PlayerQueue.startIndex.coerceIn(queue.indices)

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    PlayerFactory.describe(this@PlayerActivity, error),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView?.text = mediaItem?.mediaMetadata?.title ?: ""
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Moving to another episode: remember where the previous one stopped,
                // or forget it when it simply played to the end.
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                    savePosition(oldPosition.mediaItemIndex, oldPosition.positionMs, finished)
                }
            }
        })

        val root = FrameLayout(this)
        val view = PlayerView(this)
        view.player = exo
        view.setShowSubtitleButton(true) // built-in subtitle/audio track selection
        val hasQueue = queue.size > 1
        view.setShowNextButton(hasQueue)
        view.setShowPreviousButton(hasQueue)
        playerView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val mediaItems = queue.map { item ->
            MediaItem.Builder()
                .setUri(item.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                .build()
        }
        // Resume where the user left off.
        val startPosition = positions.getLong(resumeKey(queue[startIndex].url), C.TIME_UNSET)
        exo.setMediaItems(mediaItems, startIndex, startPosition)
        exo.prepare()
        exo.playWhenReady = true

        // Top bar: title + subtitle upload, shown together with the player controls.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#66000000"))
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val title = TextView(this).apply {
            text = queue[startIndex].title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
        }
        titleView = title
        topBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(Button(this).apply {
            text = getString(R.string.load_subtitles)
            setOnClickListener { subtitlePicker.launch(arrayOf("*/*")) }
        })
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )

        setContentView(root)
    }

    /** Stable key for resume positions that doesn't contain the account password. */
    private fun resumeKey(url: String): String {
        val parsed = Uri.parse(url)
        val segments = parsed.pathSegments
        return if (parsed.scheme?.startsWith("http") == true && segments.size >= 4) {
            // /movie/{user}/{pass}/{id}.{ext} -> "movie/{id}.{ext}"
            "${segments.first()}/${segments.last()}"
        } else {
            url
        }
    }

    private fun savePosition(index: Int, positionMs: Long, finished: Boolean = false) {
        val exo = player ?: return
        val url = PlayerQueue.items.getOrNull(index)?.url ?: return
        val key = resumeKey(url)
        val duration = if (index == exo.currentMediaItemIndex) exo.duration else C.TIME_UNSET
        val nearEnd = duration != C.TIME_UNSET && positionMs > duration - 120_000
        if (finished || positionMs < 30_000 || nearEnd) {
            positions.edit().remove(key).apply() // barely started, or finished: start fresh next time
        } else {
            positions.edit().putLong(key, positionMs).apply()
        }
    }

    /** Attaches a user-picked SRT/VTT file to the current item, keeping position. */
    private fun applyExternalSubtitle(uri: Uri) {
        val exo = player ?: return
        val current = exo.currentMediaItem ?: return
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition

        val name = queryDisplayName(uri).lowercase()
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ssa") || name.endsWith(".ass") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP // .srt and unknown
        }

        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLabel("Extern")
            .build()

        val updated = current.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()

        exo.replaceMediaItem(index, updated)
        exo.seekTo(index, position)
        exo.play()
        Toast.makeText(this, getString(R.string.subtitles_loaded), Toast.LENGTH_SHORT).show()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
                } ?: ""
        } catch (e: Exception) {
            uri.lastPathSegment ?: ""
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                player?.seekToNextMediaItem(); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                player?.seekToPreviousMediaItem(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.let { exo ->
            savePosition(exo.currentMediaItemIndex, exo.currentPosition)
            exo.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerView?.player = null
        player?.release()
        player = null
    }
}
