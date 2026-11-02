package app.parabol.data

import com.google.gson.annotations.SerializedName

// ---------- Login ----------

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    val username: String? = null,
    val password: String? = null,
    val status: String? = null,
    val auth: Int? = null,
    @SerializedName("exp_date") val expDate: String? = null,
    @SerializedName("max_connections") val maxConnections: String? = null
)

data class ServerInfo(
    val url: String? = null,
    val port: String? = null,
    @SerializedName("server_protocol") val protocol: String? = null
)

// ---------- Categories ----------

data class Category(
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("category_name") val categoryName: String = ""
)

// ---------- Live ----------

data class LiveStream(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("stream_id") val streamId: Int = 0,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("epg_channel_id") val epgChannelId: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("tv_archive") val tvArchive: Int? = null,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: String? = null,
    /** Full stream link (M3U accounts; Xtream builds the link from the id). */
    @SerializedName("direct_source") val directSource: String? = null
) {
    val hasCatchup: Boolean get() = tvArchive == 1
    val catchupDurationDays: Int get() = tvArchiveDuration?.toIntOrNull() ?: 0
}

// ---------- VOD (movies) ----------

data class VodStream(
    @SerializedName("name") val name: String = "",
    @SerializedName("stream_id") val streamId: Int = 0,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null,
    @SerializedName("rating") val rating: String? = null,
    /** Full stream link (M3U accounts). */
    @SerializedName("direct_source") val directSource: String? = null
)

// ---------- Series ----------

data class SeriesStream(
    @SerializedName("name") val name: String = "",
    @SerializedName("series_id") val seriesId: Int = 0,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("rating") val rating: String? = null
)

data class SeriesInfoResponse(
    @SerializedName("info") val info: SeriesInfoDetail? = null,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>? = null
)

data class SeriesInfoDetail(
    @SerializedName("name") val name: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("cover") val cover: String? = null
)

data class Episode(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null,
    @SerializedName("episode_num") val episodeNum: Any? = null,
    @SerializedName("season") val season: Any? = null,
    /** Full stream link (M3U accounts). */
    @SerializedName("direct_source") val directSource: String? = null
) {
    val episodeNumber: String
        get() = when (episodeNum) {
            is Number -> episodeNum.toInt().toString()
            is String -> episodeNum
            else -> ""
        }
}

// ---------- EPG ----------

data class EpgResponse(
    @SerializedName("epg_listings") val listings: List<EpgListing>? = null
)

data class EpgListing(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val titleB64: String? = null,
    @SerializedName("description") val descriptionB64: String? = null,
    @SerializedName("start") val start: String? = null,
    @SerializedName("end") val end: String? = null,
    @SerializedName("start_timestamp") val startTimestamp: String? = null,
    @SerializedName("stop_timestamp") val stopTimestamp: String? = null
) {
    /** Start time in epoch seconds, corrected by the user's guide time offset. */
    fun startSeconds(offsetHours: Int): Long =
        (startTimestamp?.toLongOrNull() ?: 0L) + offsetHours * 3600L

    /** End time in epoch seconds, corrected by the user's guide time offset. */
    fun stopSeconds(offsetHours: Int): Long =
        (stopTimestamp?.toLongOrNull() ?: 0L) + offsetHours * 3600L

    val title: String get() = decode(titleB64)
    val description: String get() = decode(descriptionB64)

    private fun decode(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            value
        }
    }
}

/**
 * Guide data from providers often has duplicates and programmes that overlap (two sources
 * merged, or a programme listed twice with slightly different times). Shown as they are,
 * blocks and titles in the guide fall on top of each other. This keeps one programme per
 * time slot: sorted by start, without empty or duplicate entries, and a programme that
 * starts before the previous one ends begins where that one ends (or is left out when it
 * lies completely inside it).
 */
fun List<EpgListing>.withoutOverlaps(): List<EpgListing> {
    val result = ArrayList<EpgListing>(size)
    var lastEnd = Long.MIN_VALUE
    for (p in sortedWith(compareBy<EpgListing>({ it.startSeconds(0) }, { -it.stopSeconds(0) }))) {
        val start = p.startSeconds(0)
        val stop = p.stopSeconds(0)
        if (stop <= start || stop <= lastEnd) continue // empty, or inside the previous programme
        result += if (start < lastEnd) p.copy(startTimestamp = lastEnd.toString()) else p
        lastEnd = stop
    }
    return result
}
