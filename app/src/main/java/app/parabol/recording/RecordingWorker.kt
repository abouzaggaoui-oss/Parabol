package app.parabol.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.parabol.data.CompletedRecording
import app.parabol.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Records a live channel to a local file for a scheduled time window.
 *
 * Always downloads the raw ".ts" (MPEG-TS) stream — a straight byte copy is far simpler
 * and more robust than stitching HLS segments, and concatenated MPEG-TS stays playable.
 *
 * Reliability:
 *  - IPTV streams regularly drop or stall. Instead of ending the recording at the first
 *    hiccup, the worker reconnects and keeps appending until the end time.
 *  - A read timeout makes a stalled connection fail fast (the old version used no
 *    timeout, so a stall could hang forever and overrun the end time).
 *  - Recordings that captured nothing are removed instead of listed as empty files.
 *  - [END_PADDING_SECONDS] adds a few minutes after the guide's end time, because
 *    programmes often overrun and the guide is rarely exact.
 */
class RecordingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "parabol_recording"
        private const val NOTIFICATION_ID_BASE = 9000
        private const val END_PADDING_SECONDS = 3 * 60L
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Voorbereiden...")

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Opnames", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun createForegroundInfo(status: String): ForegroundInfo {
        ensureChannel()
        val title = inputData.getString("programTitle") ?: "Opname"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Bezig met opnemen: $title")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (inputData.getString("recordingId")?.hashCode() ?: 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo("Verbinden met stream..."))

        val recordingId = inputData.getString("recordingId") ?: return@withContext Result.failure()
        val profileId = inputData.getString("profileId") ?: ""
        val baseUrl = inputData.getString("baseUrl") ?: return@withContext Result.failure()
        val username = inputData.getString("username") ?: return@withContext Result.failure()
        val password = inputData.getString("password") ?: return@withContext Result.failure()
        val streamId = inputData.getInt("streamId", 0)
        val channelName = inputData.getString("channelName") ?: "Kanaal"
        val programTitle = inputData.getString("programTitle") ?: "Opname"
        val endEpochSeconds = inputData.getLong("endEpochSeconds", 0L) + END_PADDING_SECONDS

        val store = Store(applicationContext)
        // Demo account: record its sample video once (an mp4 file, not an endless stream).
        val demo = app.parabol.data.DemoServer.isDemo(baseUrl)
        val url = if (demo) app.parabol.data.DemoServer.liveUrl(streamId)
            else "${baseUrl.trimEnd('/')}/live/$username/$password/$streamId.ts"

        val safeTitle = programTitle.replace(Regex("[^A-Za-z0-9 _\\-]"), "").ifBlank { "Opname" }
        val fileName = "Parabol - $safeTitle - ${System.currentTimeMillis()}." + (if (demo) "mp4" else "ts")

        var contentUri: Uri? = null
        var bytesWritten = 0L

        try {
            contentUri = createOutputUri(fileName, if (demo) "video/mp4" else "video/mp2t") ?: return@withContext Result.failure()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS) // a stalled stream fails fast and reconnects
                .build()
            val request = Request.Builder().url(url).build()

            val out = applicationContext.contentResolver.openOutputStream(contentUri)
                ?: throw IOException("Kan opnamebestand niet openen")
            out.use { output ->
                // Reconnect loop: keep appending until the (padded) end time.
                while (System.currentTimeMillis() / 1000 < endEpochSeconds) {
                    coroutineContext.ensureActive() // stop when the recording is cancelled
                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Lege respons")
                            body.byteStream().use { input ->
                                // The demo video is copied whole, so the file stays playable.
                                bytesWritten += copyUntil(input, output, if (demo) Long.MAX_VALUE else endEpochSeconds)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        setForeground(createForegroundInfo("Verbinding weggevallen, opnieuw verbinden..."))
                    }
                    if (demo && bytesWritten > 0) break // the demo video is a file: one copy is enough
                    if (System.currentTimeMillis() / 1000 < endEpochSeconds) delay(RECONNECT_DELAY_MS)
                }
            }
        } catch (e: CancellationException) {
            // Cancelled by the user (or the account was removed): keep what was recorded so far.
        } catch (e: Exception) {
            // Fall through: whatever was captured is kept below.
        } finally {
            contentUri?.let { uri -> runCatching { finalizeOutputUri(uri) } }
        }

        store.removeScheduledRecording(recordingId)
        val uri = contentUri
        if (uri != null && bytesWritten > 0) {
            store.addCompletedRecording(
                CompletedRecording(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    channelName = channelName,
                    programTitle = programTitle,
                    contentUri = uri.toString(),
                    recordedAtEpochSeconds = System.currentTimeMillis() / 1000
                )
            )
            Result.success()
        } else {
            // Nothing captured: remove the empty file instead of listing a broken recording.
            if (uri != null) runCatching { deleteOutput(uri) }
            Result.failure()
        }
    }

    /** Copies bytes until [endEpochSeconds] or the stream ends; returns the number of bytes copied. */
    private suspend fun copyUntil(input: InputStream, output: OutputStream, endEpochSeconds: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastNotify = 0L
        while (true) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() / 1000 >= endEpochSeconds) break
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            copied += read

            if (System.currentTimeMillis() - lastNotify > 30_000) {
                lastNotify = System.currentTimeMillis()
                setForeground(createForegroundInfo("Bezig met opnemen..."))
            }
        }
        return copied
    }

    private fun createOutputUri(fileName: String, mime: String): Uri? {
        val resolver = applicationContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Parabol")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } else {
            // Legacy path for pre-Android 10 devices (requires WRITE_EXTERNAL_STORAGE, maxSdk 28).
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Parabol")
            if (!dir.exists()) dir.mkdirs()
            Uri.fromFile(File(dir, fileName))
        }
    }

    private fun finalizeOutputUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            applicationContext.contentResolver.update(uri, values, null, null)
        }
    }

    private fun deleteOutput(uri: Uri) {
        if (uri.scheme == "content") {
            applicationContext.contentResolver.delete(uri, null, null)
        } else {
            uri.path?.let { File(it).delete() }
        }
    }
}
