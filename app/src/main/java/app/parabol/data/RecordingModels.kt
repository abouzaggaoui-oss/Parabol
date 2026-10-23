package app.parabol.data

/** One Xtream account. Favorites and hidden groups are scoped per profile. */
data class Profile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String
)

/** A recording the user has scheduled for a future (or currently airing) programme. */
data class ScheduledRecording(
    val id: String,
    val profileId: String,
    val streamId: Int,
    val channelName: String,
    val programTitle: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long
) {
    val durationMinutes: Int
        get() = ((endEpochSeconds - startEpochSeconds) / 60).toInt().coerceAtLeast(1)
}

/** A finished recording, saved to the device's Movies/Parabol folder via MediaStore. */
data class CompletedRecording(
    val id: String,
    val profileId: String,
    val channelName: String,
    val programTitle: String,
    val contentUri: String,
    val recordedAtEpochSeconds: Long
)

/** Current + upcoming programmes for one channel, with the time they were fetched. */
data class ShortEpg(val listings: List<EpgListing>, val fetchedAt: Long)

/** The programme on now (if any) and the programmes after it. */
data class NowNext(val current: EpgListing?, val next: List<EpgListing>)

/** A live TV session: the channel list being zapped through and the position in it. */
data class LiveSession(val groupId: String?, val channels: List<LiveStream>, val index: Int)

/** All groups of one type whose names start with the same country code; [code] null = other. */
data class CountryGroup(val code: String?, val categories: List<Category>, val visibleCount: Int) {
    val total: Int get() = categories.size
    val allVisible: Boolean get() = visibleCount == total
    val noneVisible: Boolean get() = visibleCount == 0
}
