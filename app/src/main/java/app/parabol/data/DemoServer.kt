package app.parabol.data

import android.util.Base64

/**
 * Built-in demo account, so anyone (including app reviewers) can try Parabol without
 * a subscription: server "demo", username "demo", password "demo".
 *
 * All content is fictional, except the videos: the open movies of the Blender
 * Foundation (CC BY), served from Google's public sample bucket.
 */
object DemoServer {

    const val USERNAME = "demo"
    const val PASSWORD = "demo"

    /** True for the demo server, whether typed as "demo", "http://demo" or "http://demo/". */
    fun isDemo(baseUrl: String?): Boolean {
        val host = (baseUrl ?: return false).trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
        return host == "demo"
    }

    private const val SAMPLES = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/"

    private data class Film(val id: Int, val title: String, val file: String)

    private val films = listOf(
        Film(1001, "Big Buck Bunny", "BigBuckBunny.mp4"),
        Film(1002, "Elephants Dream", "ElephantsDream.mp4"),
        Film(1003, "Sintel", "Sintel.mp4"),
        Film(1004, "Tears of Steel", "TearsOfSteel.mp4"),
    )

    private data class Channel(val id: Int, val name: String, val film: Film, val shows: List<String>)

    private val channels = listOf(
        Channel(1, "Parabol Demo 1", films[0], listOf("Ochtendfilm", "Korte animaties", "Achter de schermen")),
        Channel(2, "Parabol Demo 2", films[1], listOf("Middagfilm", "Kunst en techniek", "Studiogesprek")),
        Channel(3, "Parabol Demo 3", films[2], listOf("Avondfilm", "Maak je eigen animatie", "Filmpraat")),
        Channel(4, "Parabol Demo 4", films[3], listOf("Nachtfilm", "Effecten uitgelegd", "Herhaling")),
    )

    fun liveUrl(streamId: Int): String =
        SAMPLES + (channels.firstOrNull { it.id == streamId }?.film ?: films[0]).file

    fun movieUrl(streamId: Int): String =
        SAMPLES + (films.firstOrNull { it.id == streamId } ?: films[0]).file

    fun episodeUrl(episodeId: String): String =
        movieUrl((episodeId.toIntOrNull() ?: 0) - 2000)

    // ---- Fake server responses ----

    fun login(username: String, password: String): LoginResponse {
        val ok = username.trim() == USERNAME && password.trim() == PASSWORD
        return LoginResponse(
            userInfo = UserInfo(
                username = username,
                status = if (ok) "Active" else "Disabled",
                auth = if (ok) 1 else 0
            ),
            serverInfo = ServerInfo(url = "demo")
        )
    }

    val liveCategories = listOf(Category("1", "Demo"))
    val vodCategories = listOf(Category("11", "Open films (Blender Foundation, CC BY)"))
    val seriesCategories = listOf(Category("21", "Demo-series"))

    val liveStreams get() = channels.mapIndexed { i, c ->
        LiveStream(num = i + 1, name = c.name, streamId = c.id, categoryId = "1", tvArchive = 0)
    }

    val vodStreams get() = films.map {
        VodStream(name = it.title, streamId = it.id, categoryId = "11", containerExtension = "mp4")
    }

    val series get() = listOf(
        SeriesStream(
            name = "Open films", seriesId = 2001, categoryId = "21",
            plot = "De open films van de Blender Foundation als demo-serie (CC BY)."
        )
    )

    fun seriesInfo(seriesId: Int): SeriesInfoResponse = SeriesInfoResponse(
        info = SeriesInfoDetail(name = "Open films", plot = "Demo-serie met de open films van de Blender Foundation (CC BY)."),
        episodes = mapOf("1" to films.mapIndexed { i, f ->
            Episode(id = "${f.id + 2000}", title = f.title, containerExtension = "mp4", episodeNum = i + 1, season = 1)
        })
    )

    /** A guide with 30-minute programmes from 3 hours ago to 9 hours ahead. */
    fun epg(streamId: Int, limit: Int? = null): EpgResponse {
        val channel = channels.firstOrNull { it.id == streamId } ?: return EpgResponse(emptyList())
        val slot = 30 * 60L
        val now = System.currentTimeMillis() / 1000
        val first = (now / slot) * slot - 6 * slot
        val all = (0 until 24).map { i ->
            val start = first + i * slot
            val title = if (i % 3 == 0) channel.film.title else channel.shows[i % channel.shows.size]
            val description = if (i % 3 == 0)
                "Open film van de Blender Foundation (CC BY). Voorbeeldprogramma in de demo van Parabol."
            else "Verzonnen programma in de demo van Parabol."
            EpgListing(
                id = "$streamId-$i",
                titleB64 = b64(title),
                descriptionB64 = b64(description),
                start = null,
                end = null,
                startTimestamp = start.toString(),
                stopTimestamp = (start + slot).toString()
            )
        }
        val listings = if (limit != null) all.filter { it.stopTimestamp!!.toLong() > now }.take(limit) else all
        return EpgResponse(listings)
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

/** [XtreamApi] answered entirely from [DemoServer], without any network calls. */
class DemoXtreamApi : XtreamApi {
    override suspend fun login(username: String, password: String) = DemoServer.login(username, password)
    override suspend fun getLiveCategories(username: String, password: String, action: String) = DemoServer.liveCategories
    override suspend fun getVodCategories(username: String, password: String, action: String) = DemoServer.vodCategories
    override suspend fun getSeriesCategories(username: String, password: String, action: String) = DemoServer.seriesCategories
    override suspend fun getLiveStreams(username: String, password: String, action: String) = DemoServer.liveStreams
    override suspend fun getVodStreams(username: String, password: String, action: String) = DemoServer.vodStreams
    override suspend fun getSeries(username: String, password: String, action: String) = DemoServer.series
    override suspend fun getSeriesInfo(username: String, password: String, seriesId: Int, action: String) =
        DemoServer.seriesInfo(seriesId)
    override suspend fun getShortEpg(username: String, password: String, streamId: Int, limit: Int, action: String) =
        DemoServer.epg(streamId, limit)
    override suspend fun getFullEpg(username: String, password: String, streamId: Int, action: String) =
        DemoServer.epg(streamId)
}
