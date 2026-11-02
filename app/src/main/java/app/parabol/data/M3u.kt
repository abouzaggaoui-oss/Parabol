package app.parabol.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * M3U playlists: a list of "#EXTINF" entries with a name, group, logo and stream link.
 * They are turned into the same groups, channels, movies and series the app uses for
 * Xtream accounts, so every screen works the same way.
 *
 * What is what: a link containing /movie/ or ending in a video file extension is a movie,
 * a link containing /series/ (or a movie whose name has S01E02) is an episode of a series,
 * everything else is a live channel.
 */
object M3uParser {

    data class Entry(
        val name: String,
        val group: String,
        val logo: String?,
        val tvgId: String?,
        val tvgName: String?,
        val url: String,
    )

    class Playlist(
        val guideUrl: String?,
        val liveCategories: List<Category>,
        val vodCategories: List<Category>,
        val seriesCategories: List<Category>,
        val live: List<LiveStream>,
        val vod: List<VodStream>,
        val series: List<SeriesStream>,
        val episodes: Map<Int, Map<String, List<Episode>>>,
    ) {
        val isEmpty get() = live.isEmpty() && vod.isEmpty() && series.isEmpty()
    }

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val episodeCode = Regex("""(?i)\bS(\d{1,3})\s*[ ._-]?\s*E(\d{1,4})\b""")
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    fun parseEntries(text: Sequence<String>): Pair<String?, List<Entry>> {
        var guide: String? = null
        val entries = ArrayList<Entry>()
        var info: String? = null
        var groupLine: String? = null
        for (raw in text) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    guide = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; groupLine = null }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> groupLine = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT, #KODIPROP and the like
                else -> {
                    val inf = info ?: "#EXTINF:-1,"
                    val a = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // The display name follows the last comma outside quotes.
                    val name = displayName(inf).ifBlank { a["tvg-name"].orEmpty() }.ifBlank { line.substringAfterLast('/') }
                    entries += Entry(
                        name = name,
                        group = (a["group-title"] ?: groupLine).orEmpty().trim(),
                        logo = a["tvg-logo"]?.ifBlank { null },
                        tvgId = a["tvg-id"]?.ifBlank { null },
                        tvgName = a["tvg-name"]?.ifBlank { null },
                        url = line,
                    )
                    info = null; groupLine = null
                }
            }
        }
        return guide to entries
    }

    private fun displayName(inf: String): String {
        var inQuotes = false
        var last = -1
        inf.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) last = i
        }
        return if (last >= 0) inf.substring(last + 1).trim() else ""
    }

    private fun extension(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Stable number for a text, so ids stay the same every time the list is read. */
    private fun stableId(text: String, used: MutableSet<Int>): Int {
        var id = abs(text.hashCode() % 1_000_000_000).coerceAtLeast(1)
        while (!used.add(id)) id++
        return id
    }

    private fun groupId(prefix: String, group: String) = prefix + abs(group.lowercase().hashCode()).toString(36)

    fun build(guideUrl: String?, entries: List<Entry>): Playlist {
        val usedIds = HashSet<Int>()
        val liveCats = LinkedHashMap<String, Category>()
        val vodCats = LinkedHashMap<String, Category>()
        val seriesCats = LinkedHashMap<String, Category>()
        val live = ArrayList<LiveStream>()
        val vod = ArrayList<VodStream>()
        data class Ep(val e: Entry, val title: String, val season: Int, val number: Int)
        val seriesEps = LinkedHashMap<Pair<String, String>, MutableList<Ep>>() // (group, show) -> episodes

        fun cat(map: LinkedHashMap<String, Category>, prefix: String, group: String): String {
            val name = group.ifBlank { "Overig" }
            val id = groupId(prefix, name)
            map.getOrPut(id) { Category(id, name) }
            return id
        }

        for (e in entries) {
            val path = e.url.substringBefore('?').lowercase()
            val code = episodeCode.find(e.name)
            val isSeries = "/series/" in path || (code != null && ("/movie/" in path || extension(e.url) in videoExt))
            val isMovie = !isSeries && ("/movie/" in path || extension(e.url) in videoExt)
            when {
                isSeries -> {
                    val show = (if (code != null) e.name.substring(0, code.range.first) else e.name)
                        .trim().trimEnd('-', '|', ':', '.', ' ').ifBlank { e.name }
                    val title = if (code != null) e.name.substring(code.range.last + 1).trim().trimStart('-', '|', ':', '.', ' ') else ""
                    seriesEps.getOrPut(e.group to show) { ArrayList() } +=
                        Ep(e, title, code?.groupValues?.get(1)?.toIntOrNull() ?: 1, code?.groupValues?.get(2)?.toIntOrNull() ?: 0)
                }
                isMovie -> vod += VodStream(
                    name = e.name, streamId = stableId(e.url, usedIds), streamIcon = e.logo,
                    categoryId = cat(vodCats, "v", e.group), containerExtension = extension(e.url).ifBlank { "mp4" },
                    directSource = e.url
                )
                else -> live += LiveStream(
                    num = live.size + 1, name = e.name, streamId = stableId(e.url, usedIds), streamIcon = e.logo,
                    epgChannelId = e.tvgId ?: e.tvgName ?: e.name, categoryId = cat(liveCats, "l", e.group),
                    tvArchive = 0, directSource = e.url
                )
            }
        }

        val series = ArrayList<SeriesStream>()
        val episodes = HashMap<Int, Map<String, List<Episode>>>()
        for ((key, eps) in seriesEps) {
            val (group, show) = key
            val id = stableId("series:$group:$show", usedIds)
            series += SeriesStream(name = show, seriesId = id, cover = eps.firstNotNullOfOrNull { it.e.logo }, categoryId = cat(seriesCats, "s", group))
            episodes[id] = eps.sortedWith(compareBy({ it.season }, { it.number })).groupBy { it.season.toString() }
                .mapValues { (_, list) ->
                    list.mapIndexed { i, ep ->
                        Episode(
                            id = stableId(ep.e.url, usedIds).toString(),
                            title = ep.title.ifBlank { ep.e.name },
                            containerExtension = extension(ep.e.url).ifBlank { "mp4" },
                            episodeNum = if (ep.number > 0) ep.number else i + 1,
                            season = ep.season,
                            directSource = ep.e.url
                        )
                    }
                }
        }
        return Playlist(guideUrl, liveCats.values.toList(), vodCats.values.toList(), seriesCats.values.toList(),
            live, vod, series, episodes)
    }

    fun parse(text: Sequence<String>): Playlist {
        val (guide, entries) = parseEntries(text)
        return build(guide, entries)
    }
}

/**
 * Tv guide in XMLTV format (often .xml.gz). Only the channels in the playlist and the
 * programmes from 12 hours ago to 2 days ahead are kept, so a large guide stays small.
 */
object Xmltv {

    data class Programme(val start: Long, val stop: Long, val title: String, val description: String)

    /** Channel key (lower case id or display name) -> programmes, sorted by start. */
    fun parse(input: InputStream, wanted: Set<String>, nowSeconds: Long = System.currentTimeMillis() / 1000): Map<String, List<Programme>> {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val from = nowSeconds - 12 * 3600
        val until = nowSeconds + 48 * 3600
        val aliases = HashMap<String, MutableSet<String>>() // channel id -> keys it is known by
        val result = HashMap<String, MutableList<Programme>>()
        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title = ""
        var desc = ""
        var field: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        channelId?.let { aliases.getOrPut(it) { HashSet() } += it.lowercase() }
                    }
                    "display-name" -> if (channelId != null) { field = "display"; text.setLength(0) }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = time(parser.getAttributeValue(null, "start"))
                        progStop = time(parser.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                    "title" -> if (progChannel != null && title.isEmpty()) { field = "title"; text.setLength(0) }
                    "desc" -> if (progChannel != null && desc.isEmpty()) { field = "desc"; text.setLength(0) }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (field != null) text.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> { if (field == "display") channelId?.let { aliases[it]?.add(text.toString().trim().lowercase()) }; field = null }
                    "channel" -> channelId = null
                    "title" -> { if (field == "title") title = text.toString().trim(); field = null }
                    "desc" -> { if (field == "desc") desc = text.toString().trim(); field = null }
                    "programme" -> {
                        val ch = progChannel
                        if (ch != null && progStop > from && progStart < until && progStop > progStart) {
                            val keys = aliases[ch] ?: setOf(ch.lowercase())
                            val key = keys.firstOrNull { it in wanted }
                            if (key != null) result.getOrPut(key) { ArrayList() } += Programme(progStart, progStop, title, desc)
                        }
                        progChannel = null
                    }
                }
            }
            event = parser.next()
        }
        // Every key a channel is known by points to the same programmes.
        val out = HashMap<String, List<Programme>>()
        for ((_, keys) in aliases) {
            val list = keys.firstNotNullOfOrNull { result[it] } ?: continue
            val sorted = list.sortedBy { it.start }
            keys.forEach { out[it] = sorted }
        }
        result.forEach { (k, v) -> if (k !in out) out[k] = v.sortedBy { it.start } }
        return out
    }

    /** "20261102183000 +0100" -> epoch seconds. */
    fun time(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        val m = Regex("""(\d{14})\s*([+-]\d{4})?""").find(value) ?: return 0
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val utc = runCatching { fmt.parse(m.groupValues[1])!!.time / 1000 }.getOrDefault(0)
        val tz = m.groupValues[2]
        if (tz.isEmpty()) return utc
        val sign = if (tz[0] == '-') -1 else 1
        val offset = sign * (tz.substring(1, 3).toInt() * 3600 + tz.substring(3, 5).toInt() * 60)
        return utc - offset
    }
}

/**
 * An M3U account behind the same interface as an Xtream server. The playlist is read once
 * per app session and then answered from memory (also per group); the guide is read the
 * first time a channel's programmes are asked for.
 */
class M3uApi(
    private val playlistUrl: String,
    private val guideUrl: String?,
    private val open: suspend (String) -> InputStream = ::download,
) : XtreamApi {

    private val lock = Mutex()
    private var playlist: M3uParser.Playlist? = null
    private val guideLock = Mutex()
    private var guide: Map<String, List<Xmltv.Programme>>? = null

    suspend fun playlist(): M3uParser.Playlist = lock.withLock {
        playlist ?: withContext(Dispatchers.IO) {
            open(playlistUrl).use { stream ->
                M3uParser.parse(stream.bufferedReader().lineSequence())
            }
        }.also { playlist = it }
    }

    private suspend fun guide(): Map<String, List<Xmltv.Programme>> = guideLock.withLock {
        guide?.let { return it }
        val p = playlist()
        val url = guideUrl?.ifBlank { null } ?: p.guideUrl
        val wanted = p.live.flatMap { listOfNotNull(it.epgChannelId?.lowercase(), it.name.lowercase()) }.toSet()
        val parsed = if (url == null) emptyMap() else runCatching {
            withContext(Dispatchers.IO) { open(url).use { Xmltv.parse(it, wanted) } }
        }.getOrDefault(emptyMap())
        // A failed guide is not cached, so it is tried again later.
        if (parsed.isNotEmpty() || url == null) guide = parsed
        parsed
    }

    override suspend fun login(username: String, password: String): LoginResponse {
        val p = playlist()
        val ok = !p.isEmpty
        return LoginResponse(UserInfo(status = if (ok) "Active" else "Disabled", auth = if (ok) 1 else 0), ServerInfo())
    }

    override suspend fun getLiveCategories(username: String, password: String, action: String) = playlist().liveCategories
    override suspend fun getVodCategories(username: String, password: String, action: String) = playlist().vodCategories
    override suspend fun getSeriesCategories(username: String, password: String, action: String) = playlist().seriesCategories
    override suspend fun getLiveStreams(username: String, password: String, action: String, categoryId: String?) =
        playlist().live.filter { categoryId == null || it.categoryId == categoryId }
    override suspend fun getVodStreams(username: String, password: String, action: String, categoryId: String?) =
        playlist().vod.filter { categoryId == null || it.categoryId == categoryId }
    override suspend fun getSeries(username: String, password: String, action: String, categoryId: String?) =
        playlist().series.filter { categoryId == null || it.categoryId == categoryId }

    override suspend fun getSeriesInfo(username: String, password: String, seriesId: Int, action: String): SeriesInfoResponse {
        val p = playlist()
        val s = p.series.firstOrNull { it.seriesId == seriesId }
        return SeriesInfoResponse(SeriesInfoDetail(s?.name, s?.plot, s?.cover), p.episodes[seriesId] ?: emptyMap())
    }

    private suspend fun listings(streamId: Int): List<EpgListing> {
        val channel = playlist().live.firstOrNull { it.streamId == streamId } ?: return emptyList()
        val g = guide()
        val programmes = listOfNotNull(channel.epgChannelId, channel.name)
            .firstNotNullOfOrNull { g[it.lowercase()] } ?: return emptyList()
        return programmes.mapIndexed { i, p ->
            EpgListing(
                id = "$streamId-$i",
                titleB64 = b64(p.title),
                descriptionB64 = b64(p.description),
                startTimestamp = p.start.toString(),
                stopTimestamp = p.stop.toString()
            )
        }
    }

    override suspend fun getShortEpg(username: String, password: String, streamId: Int, limit: Int, action: String): EpgResponse {
        val now = System.currentTimeMillis() / 1000
        return EpgResponse(listings(streamId).filter { (it.stopTimestamp?.toLongOrNull() ?: 0) > now }.take(limit))
    }

    override suspend fun getFullEpg(username: String, password: String, streamId: Int, action: String) =
        EpgResponse(listings(streamId))

    companion object {
        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }
        private val instances = ConcurrentHashMap<String, M3uApi>()

        /** One instance per playlist, so switching accounts doesn't read the list again. */
        fun get(playlistUrl: String, guideUrl: String?): M3uApi =
            instances.getOrPut("$playlistUrl|${guideUrl.orEmpty()}") { M3uApi(playlistUrl, guideUrl) }

        /** Forget a playlist (after "Refresh content"), so it is read again. */
        fun reset(playlistUrl: String) {
            instances.keys.filter { it.startsWith("$playlistUrl|") }.forEach { instances.remove(it) }
        }

        private fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

        /** Downloads a playlist or guide; .gz (also without the extension) is unpacked. */
        suspend fun download(url: String): InputStream = withContext(Dispatchers.IO) {
            val response = client.newCall(
                Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Parabol").build()
            ).execute()
            if (!response.isSuccessful) { response.close(); throw IOException("HTTP ${response.code}") }
            val body = BufferedInputStream(response.body!!.byteStream(), 64 * 1024)
            body.mark(2)
            val gz = body.read() == 0x1f && body.read() == 0x8b
            body.reset()
            if (gz) GZIPInputStream(body, 64 * 1024) else body
        }
    }
}
