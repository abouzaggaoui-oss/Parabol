package app.parabol.recording

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.parabol.data.Profile
import app.parabol.data.ScheduledRecording
import java.util.concurrent.TimeUnit

/**
 * Schedules a [RecordingWorker] to run at (or shortly after) the recording's start time.
 *
 * IMPORTANT LIMITATION: this uses WorkManager's delay-based scheduling, not an exact alarm.
 * On modern Android, battery optimization / Doze mode can delay the actual start by anywhere
 * from a few seconds up to several minutes, especially if the phone has been idle for a while.
 * For an exact start (e.g. within a few seconds), AlarmManager.setExactAndAllowWhileIdle() with
 * the SCHEDULE_EXACT_ALARM permission would be needed instead — a possible future improvement,
 * but it requires the user to grant a special "Alarms & reminders" permission on Android 12+.
 */
object RecordingScheduler {

    private const val START_PADDING_MS = 60_000L

    private fun workName(recordingId: String) = "recording_$recordingId"

    fun schedule(context: Context, recording: ScheduledRecording, profile: Profile) {
        // Start a minute early: programmes rarely begin exactly on the guide time, and this
        // also absorbs some of the start delay Android's battery saving can add.
        val delayMs = (recording.startEpochSeconds * 1000 - START_PADDING_MS - System.currentTimeMillis())
            .coerceAtLeast(0)

        val data = Data.Builder()
            .putString("recordingId", recording.id)
            .putString("profileId", profile.id)
            .putString("baseUrl", profile.baseUrl)
            .putString("username", profile.username)
            .putString("password", profile.password)
            .putInt("streamId", recording.streamId)
            .putString("channelName", recording.channelName)
            .putString("programTitle", recording.programTitle)
            .putLong("startEpochSeconds", recording.startEpochSeconds)
            .putLong("endEpochSeconds", recording.endEpochSeconds)
            .build()

        val request = OneTimeWorkRequestBuilder<RecordingWorker>()
            .setInputData(data)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(recording.id)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(recording.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, recordingId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(recordingId))
    }
}
