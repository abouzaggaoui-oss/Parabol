package app.parabol

import app.parabol.data.M3uApi
import app.parabol.data.Xmltv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3U playlists and XMLTV guides (Robolectric for Android's XML parser and Base64). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class M3uTest {

    private val now = System.currentTimeMillis() / 1000
    private fun xmltvTime(epoch: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT+01:00")
        return f.format(java.util.Date(epoch * 1000)) + " +0100"
    }

    private val playlist = """
        #EXTM3U url-tvg="http://guide.example/epg.xml"
        #EXTINF:-1 tvg-id="een.nl" tvg-logo="http://logo/1.png" group-title="NL | Algemeen",Kanaal Een
        http://server.example/live/u/p/101.ts
        #EXTINF:-1 tvg-name="Kanaal Twee" group-title="NL | Algemeen",Kanaal Twee
        #EXTVLCOPT:http-user-agent=Test
        http://server.example/live/u/p/102.ts
        #EXTINF:-1 group-title="Films, nieuw" tvg-logo="http://img/f.jpg",De Brug (2020)
        http://server.example/movie/u/p/2001.mkv
        #EXTINF:-1 group-title="Series",Polderblues S01E02 - De dijk
        http://server.example/series/u/p/3002.mp4
        #EXTINF:-1 group-title="Series",Polderblues S01E01 - Begin
        http://server.example/series/u/p/3001.mp4
        #EXTINF:-1,Zonder groep
        https://cdn.example/stream/radio.m3u8
    """.trimIndent()

    private val guide = """
        <tv>
          <channel id="een.nl"><display-name>Kanaal Een</display-name></channel>
          <channel id="twee"><display-name>Kanaal Twee</display-name></channel>
          <programme channel="een.nl" start="${xmltvTime(now - 1800)}" stop="${xmltvTime(now + 1800)}"><title>Journaal</title><desc>Nieuws</desc></programme>
          <programme channel="een.nl" start="${xmltvTime(now + 1800)}" stop="${xmltvTime(now + 5400)}"><title>Weer</title></programme>
          <programme channel="twee" start="${xmltvTime(now - 600)}" stop="${xmltvTime(now + 600)}"><title>Quiz</title></programme>
          <programme channel="onbekend" start="${xmltvTime(now)}" stop="${xmltvTime(now + 60)}"><title>X</title></programme>
        </tv>
    """.trimIndent()

    private fun api() = M3uApi("http://list.example/a.m3u", null) { url ->
        (if (url.endsWith(".xml")) guide else playlist).byteInputStream()
    }

    @Test fun playlistIsSplitIntoLiveMoviesAndSeries() = runBlocking {
        val a = api()
        assertEquals(1, a.login("", "").userInfo?.auth)
        val live = a.getLiveStreams("", "")
        val vod = a.getVodStreams("", "")
        val series = a.getSeries("", "")
        assertEquals(listOf("Kanaal Een", "Kanaal Twee", "Zonder groep"), live.map { it.name })
        assertEquals("http://server.example/live/u/p/101.ts", live[0].directSource)
        assertEquals("http://logo/1.png", live[0].streamIcon)
        assertEquals(listOf("De Brug (2020)"), vod.map { it.name })
        assertEquals("mkv", vod[0].containerExtension)
        assertEquals(listOf("Polderblues"), series.map { it.name })
        val eps = a.getSeriesInfo("", "", series[0].seriesId).episodes!!["1"]!!
        assertEquals(listOf("Begin", "De dijk"), eps.map { it.title })
        assertEquals("http://server.example/series/u/p/3001.mp4", eps[0].directSource)
        // Groups, with a stable id per name; per-group requests return only that group.
        val cats = a.getLiveCategories("", "")
        assertEquals(listOf("NL | Algemeen", "Overig"), cats.map { it.categoryName })
        assertEquals(2, a.getLiveStreams("", "", categoryId = cats[0].categoryId).size)
        assertEquals(cats, api().getLiveCategories("", ""))
    }

    @Test fun guideMatchesByIdAndByName() = runBlocking {
        val a = api()
        val live = a.getLiveStreams("", "")
        val een = a.getShortEpg("", "", live[0].streamId, 10).listings!!
        assertEquals(listOf("Journaal", "Weer"), een.map { it.title })
        assertEquals("Nieuws", een[0].description)
        assertTrue(kotlin.math.abs(een[0].startSeconds(0) - (now - 1800)) <= 1)
        val twee = a.getShortEpg("", "", live[1].streamId, 10).listings!!
        assertEquals(listOf("Quiz"), twee.map { it.title })
        assertTrue(a.getShortEpg("", "", live[2].streamId, 10).listings!!.isEmpty())
    }

    @Test fun xmltvTimeWithOffset() {
        assertEquals(1_798_772_400L, Xmltv.time("20270101040000 +0100")) // 04:00 +01:00 = 03:00 UTC
    }
}
