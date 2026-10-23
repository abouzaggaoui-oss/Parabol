package app.parabol

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.parabol.data.Category
import app.parabol.data.EpgListing
import app.parabol.data.LiveSession
import app.parabol.data.LiveStream
import app.parabol.data.Profile
import app.parabol.data.SeriesStream
import app.parabol.data.UserInfo
import app.parabol.data.VodStream
import app.parabol.ui.BrowseScreen
import app.parabol.ui.AccountsScreen
import app.parabol.ui.BrowseType
import app.parabol.ui.GroupsManagerScreen
import app.parabol.ui.HomeScreen
import app.parabol.ui.LiveTvScreen
import app.parabol.ui.SettingsScreen
import app.parabol.ui.LocalArtworkOverride
import app.parabol.ui.LocalLiveVideoPlaceholder
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * Renders the screenshots shown in the README (docs/screenshots).
 *
 * Everything on screen is made up for these images: channel names, programmes, titles, logos
 * and posters are fictional and generated here, so the images contain no third-party content.
 *
 * Run with: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReadmeScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun demoViewModel(): MainViewModel {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        Demo.apply(vm)
        return vm
    }

    internal fun shoot(name: String, dir: String = "../docs/screenshots", content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = ParabolColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = NightBg) {
                    CompositionLocalProvider(
                        LocalArtworkOverride provides Demo::artwork,
                        LocalLiveVideoPlaceholder provides { DemoVideo() }
                    ) {
                        TvScale { content() }
                    }
                }
            }
        }
        // Let effects run (group selection, resumed channel, scroll) without real waiting.
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    // ---- TV that reports a small dp screen (e.g. 4K set at 640x360 dp) ----
    // Not part of the README: rendered to build/tvscale to check nothing is cut off.

    @Test
    @Config(qualifiers = TV_SMALL)
    fun tvSmallHome() = shoot("home", "build/tvscale") { HomeScreen(demoViewModel()) {} }

    @Test
    @Config(qualifiers = TV_SMALL)
    fun tvSmallGuide() {
        val vm = demoViewModel()
        vm.lastLiveGroupId = "1"
        shoot("live-guide", "build/tvscale") { LiveTvScreen(vm, onBack = {}, onOpenGuide = {}) }
    }

    @Test
    @Config(qualifiers = TV_SMALL)
    fun tvSmallSettings() = shoot("settings", "build/tvscale") {
        SettingsScreen(demoViewModel(), onLogout = {}, onManageGroups = {})
    }

    // ---- Phone ----

    @Test
    @Config(qualifiers = PHONE)
    fun phoneHome() = shoot("phone-home") { HomeScreen(demoViewModel()) {} }

    @Test
    @Config(qualifiers = PHONE)
    fun phoneLiveTv() {
        val vm = demoViewModel()
        vm.resumeLiveSession = Demo.session(groupId = "1", index = 0)
        shoot("phone-live") { LiveTvScreen(vm, onBack = {}, onOpenGuide = {}) }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phoneMovies() {
        val vm = demoViewModel()
        shoot("phone-movies") {
            BrowseScreen(vm, BrowseType.MOVIES, onBack = {}, onOpenSeries = {}, initialGroupId = "m1")
        }
    }

    // ---- TV ----

    @Test
    @Config(qualifiers = TV)
    fun tvHome() = shoot("tv-home") { HomeScreen(demoViewModel()) {} }

    @Test
    @Config(qualifiers = TV)
    fun tvGuide() {
        val vm = demoViewModel()
        vm.lastLiveGroupId = "1"
        shoot("tv-live-guide") { LiveTvScreen(vm, onBack = {}, onOpenGuide = {}) }
    }

    @Test
    @Config(qualifiers = TV)
    fun tvLivePreview() {
        val vm = demoViewModel()
        vm.resumeLiveSession = Demo.session(groupId = "1", index = 2)
        shoot("tv-live-preview") { LiveTvScreen(vm, onBack = {}, onOpenGuide = {}) }
    }

    @Test
    @Config(qualifiers = TV)
    fun tvSeries() {
        val vm = demoViewModel()
        shoot("tv-series") {
            BrowseScreen(vm, BrowseType.SERIES, onBack = {}, onOpenSeries = {}, initialGroupId = "s1")
        }
    }

    @Test
    @Config(qualifiers = TV)
    fun tvGroups() {
        val vm = demoViewModel()
        Demo.applyCountries(vm)
        shoot("tv-groups") { GroupsManagerScreen(vm, onBack = {}) }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phoneGroups() {
        val vm = demoViewModel()
        Demo.applyCountries(vm)
        shoot("phone-groups") { GroupsManagerScreen(vm, onBack = {}) }
    }

    @Test
    @Config(qualifiers = TV)
    fun tvAccounts() {
        val vm = demoViewModel()
        Demo.applyAccounts(vm)
        shoot("tv-accounts") { AccountsScreen(vm, onOpen = {}, onAdd = {}) }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phoneAccounts() {
        val vm = demoViewModel()
        Demo.applyAccounts(vm)
        shoot("phone-accounts") { AccountsScreen(vm, onOpen = {}, onAdd = {}) }
    }

    companion object {
        const val PHONE = "nl-w411dp-h891dp-port-xhdpi"
        const val TV = "nl-w960dp-h540dp-land-television-xhdpi"
        const val TV_SMALL = "nl-w640dp-h360dp-land-television-xxhdpi"
    }
}

/** Fictional demo content for the README screenshots. */
private object Demo {

    private val palette = listOf(
        0xFFF5B841, 0xFF9C8CFF, 0xFF4FD1C5, 0xFFFF7A6B, 0xFF6BB8FF, 0xFF8BD17C, 0xFFE58BD8, 0xFFFFA45C
    ).map { it.toInt() }

    private val liveGroups = listOf(
        "Algemeen", "Nieuws", "Sport", "Natuur & Docu", "Kids", "Muziek", "Film", "Lifestyle", "Regio"
    ).mapIndexed { i, name -> Category("${i + 1}", name) }

    private data class Ch(val name: String, val group: String, val shows: List<Pair<String, Int>>)

    private val channelSpecs = listOf(
        Ch("Lumen 1", "1", listOf("Ochtendkoffie" to 60, "Het Weerpraatje" to 15, "Lumen Journaal" to 30, "Wie Weet Het?" to 45, "Polderpraat" to 60, "De Avondshow" to 90)),
        Ch("Lumen 2", "1", listOf("Tuinieren met Toon" to 45, "Keukengeheimen" to 30, "Reizen op Rails" to 60, "Het Grote Bouwen" to 60, "Quizavond" to 45)),
        Ch("Kade TV", "1", listOf("Havenverhalen" to 30, "Stadsgezichten" to 60, "Kade Live" to 90, "Late Kade" to 60)),
        Ch("Horizon", "1", listOf("Wereldkeuken" to 45, "Verre Kusten" to 60, "Horizon Journaal" to 30, "Sterrenkijkers" to 60, "Nachtvlucht" to 90)),
        Ch("Studio Noord", "1", listOf("Noorderlicht" to 60, "Kunst & Kraam" to 30, "Muziek in de Molen" to 60, "Gesprek van de Dag" to 45)),
        Ch("Lumen 3", "1", listOf("Jong Geleerd" to 30, "Muziekquiz" to 45, "De Werkplaats" to 60, "Filmhuis" to 90)),
        Ch("Polder TV", "1", listOf("Boer zoekt Bouwer" to 60, "Fietsroutes" to 30, "Polderpraat Extra" to 45, "Laat op de Dijk" to 60)),
        Ch("Ster 8", "1", listOf("Kookduel" to 45, "Huizenjacht" to 60, "Ster Journaal" to 20, "Comedyavond" to 70)),
        Ch("Delta 24", "2", listOf("Nieuws van het Uur" to 30, "Delta Vandaag" to 60, "Het Debat" to 45, "Weer & Verkeer" to 15, "Nieuws van het Uur" to 30)),
        Ch("Kompas Nieuws", "2", listOf("Kompas Ochtend" to 90, "Economie Kort" to 30, "Kompas Middag" to 60, "Achtergrond" to 45)),
        Ch("Atlas Sport", "3", listOf("Voetbal Vooraf" to 30, "Live: Bekerfinale" to 120, "Nabeschouwing" to 45, "Sportjournaal" to 30)),
        Ch("Sprint TV", "3", listOf("Wielrennen: Etappe 6" to 150, "Sprint Studio" to 45, "Tennis Samenvatting" to 60)),
        Ch("Wildland", "4", listOf("Wadden in de Winter" to 60, "Bergen van IJs" to 60, "Het Verborgen Rif" to 45, "Nachtdieren" to 60)),
        Ch("Ontdek!", "4", listOf("Hoe Werkt Het?" to 30, "Machines van Morgen" to 60, "Oude Beschavingen" to 60)),
        Ch("Pixel Kids", "5", listOf("Robbie Robot" to 25, "De Ruimtemuizen" to 25, "Knutselklub" to 30, "Avonturen van Fien" to 25)),
        Ch("Ritme TV", "6", listOf("Top 30 Countdown" to 60, "Live Sessies" to 60, "Nachtklanken" to 120)),
        Ch("Cinema Noord", "7", listOf("Middagfilm: De Brug" to 105, "Filmpraat" to 30, "Avondfilm: Stille Wateren" to 120)),
        Ch("Smaak", "8", listOf("Snel & Simpel" to 30, "Bakwedstrijd" to 60, "Wonen & Zo" to 45)),
        Ch("Regio West", "9", listOf("West Vandaag" to 30, "Marktpraat" to 30, "Regio Journaal" to 30, "Weekendtips" to 30))
    )

    val channels: List<LiveStream> = channelSpecs.mapIndexed { i, ch ->
        LiveStream(
            num = i + 1,
            name = ch.name,
            streamId = 1000 + i,
            streamIcon = "demo://logo/$i/${ch.name}",
            categoryId = ch.group,
            tvArchive = if (i % 3 == 0) 1 else 0,
            tvArchiveDuration = "7"
        )
    }

    private val movieGroups = listOf("Uitgelicht", "Actie", "Drama", "Komedie", "Familie", "Thriller", "Documentaire")
        .mapIndexed { i, name -> Category("m${i + 1}", name) }

    private val movieTitles = listOf(
        "De Laatste Vuurtoren", "Stad van Glas", "Noorderzon", "Echo's uit de Diepte", "Het Zilveren Uur",
        "Koers Zuid", "Papieren Vleugels", "De Stille Haven", "Nachtrit", "Zeven Zomers",
        "Het Oranje Veld", "Onder de Brug", "Ijzeren Hart", "De Kaartmaker", "Wolkenkrabbers",
        "Tegen de Wind", "Lichtjaren", "De Verloren Sleutel"
    )

    private val seriesGroups = listOf("Nieuw", "Drama", "Misdaad", "Komedie", "Sci-fi", "Docuseries")
        .mapIndexed { i, name -> Category("s${i + 1}", name) }

    private val seriesTitles = listOf(
        "Het Kanaal", "Polderblues", "Dossier Delta", "Station Mars", "De Buren", "Grensland",
        "Kroniek van Kade", "Zout", "Hoge Bomen", "Recherche Noord", "Ruis", "Wildwater",
        "De Erfenis", "Code Oranje", "Nachtdienst", "Tussen Twee Steden"
    )

    val movies: List<VodStream> = movieTitles.mapIndexed { i, t ->
        VodStream(t, 2000 + i, "demo://poster/$i/$t", "m1", "mp4", "7.${i % 9}")
    }

    val series: List<SeriesStream> = seriesTitles.mapIndexed { i, t ->
        SeriesStream(t, 3000 + i, "demo://poster/${i + 5}/$t", "s1")
    }

    fun session(groupId: String, index: Int): LiveSession {
        val list = channels.filter { it.categoryId == groupId }
        return LiveSession(groupId, list, index)
    }

    /** Many provider-style groups ("NL | Sport"), some countries hidden, for the groups screen. */
    fun applyCountries(vm: MainViewModel) {
        val kinds = listOf("Algemeen", "Nieuws", "Sport", "Kids", "Film", "Muziek", "Docu", "Entertainment",
            "Regionaal", "Lifestyle", "Comedy", "Series")
        // code, number of groups, number visible
        val spec = listOf("NL" to (12 to 12), "BE" to (6 to 6), "UK" to (9 to 3), "US" to (10 to 0),
            "DE" to (8 to 0), "AR" to (9 to 0), "FR" to (7 to 0), "TR" to (6 to 0), "ES" to (5 to 0),
            "IT" to (5 to 0), "PT" to (4 to 0))
        val groups = mutableListOf<Category>()
        val hidden = mutableSetOf<String>()
        spec.forEach { (code, counts) ->
            val (total, visible) = counts
            repeat(total) { i ->
                val id = "g$code$i"
                groups += Category(id, "$code | ${kinds[i % kinds.size]}")
                if (i >= visible) hidden += "live:$id"
            }
        }
        listOf("Radio", "Evenementen", "Demo", "Wereldnieuws").forEachIndexed { i, name ->
            groups += Category("gx$i", name)
            hidden += "live:gx$i"
        }
        apply(vm, groups, hidden)
    }

    /** Three fictional accounts for the account screen. */
    fun applyAccounts(vm: MainViewModel) {
        apply(vm, profiles = listOf(
            Profile("demo", "Thuis", "https://tv.voorbeeld.invalid", "anouar", "x"),
            Profile("demo2", "Vakantiehuis", "https://iptv.voorbeeld.invalid", "zomer", "x"),
            Profile("demo3", "Sport", "https://sport.voorbeeld.invalid", "sportfan", "x")
        ))
    }

    fun apply(vm: MainViewModel, liveGroupsOverride: List<Category>? = null, hidden: Set<String> = emptySet(), profiles: List<Profile>? = null) {
        val nowSec = System.currentTimeMillis() / 1000
        val slot = nowSec - nowSec % 1800 // start of the current half hour
        val guide = channels.mapIndexed { i, ch ->
            // Start each channel's schedule a little before "now", at varying offsets.
            var t = slot - (20 + (i * 17) % 50) * 60L
            val shows = channelSpecs[i].shows
            val listings = mutableListOf<EpgListing>()
            var k = 0
            while (t < nowSec + 6 * 3600) {
                val (title, minutes) = shows[k % shows.size]
                listings += EpgListing(
                    id = "$i-$k",
                    titleB64 = b64(title),
                    descriptionB64 = b64("Verzonnen programma: alle zenders en titels in deze demo zijn fictief."),
                    startTimestamp = t.toString(),
                    stopTimestamp = (t + minutes * 60L).toString()
                )
                t += minutes * 60L
                k++
            }
            ch.streamId to listings
        }.toMap()

        vm.showDemoContent(
            profile = Profile("demo", "Thuis", "https://demo.invalid", "demo", "demo"),
            user = UserInfo(username = "demo", status = "Active", expDate = (nowSec + 200L * 86400).toString()),
            liveGroups = liveGroupsOverride ?: liveGroups,
            channels = channels,
            movieGroups = movieGroups,
            movies = movies,
            seriesGroups = seriesGroups,
            series = series,
            favoriteKeys = setOf("live:1000", "live:1005", "live:1007", "vod:2001", "vod:2004", "series:3002"),
            guide = guide,
            hiddenKeys = hidden,
            allProfiles = profiles
        )
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // ---- Generated artwork ----

    private val cache = HashMap<String, Painter>()

    fun artwork(url: String?): Painter? {
        if (url == null || !url.startsWith("demo://")) return null
        return cache.getOrPut(url) {
            val parts = url.removePrefix("demo://").split("/", limit = 3)
            val index = parts[1].toInt()
            val label = parts[2]
            BitmapPainter(
                (if (parts[0] == "logo") logo(index, label) else poster(index, label)).asImageBitmap()
            )
        }
    }

    /** Channel logo: initials on a coloured rounded square. */
    private fun logo(index: Int, name: String): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = palette[index % palette.size]
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28f, 28f, bg)
        val initials = name.split(" ", "!").filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF10141A.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (initials.length > 1) 54f else 64f
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(initials, size / 2f, y, text)
        return bmp
    }

    /** Poster: two-tone gradient, a simple shape and the title. */
    private fun poster(index: Int, title: String): Bitmap {
        val w = 300
        val h = 450
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val a = palette[index % palette.size]
        val b = palette[(index + 3) % palette.size]
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), darken(a, 0.55f), darken(b, 0.18f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a; alpha = 210 }
        when (index % 3) {
            0 -> c.drawCircle(w * 0.62f, h * 0.34f, 78f, shape)
            1 -> c.drawRect(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.24f, shape).also {
                c.drawRect(w * 0.18f, h * 0.30f, w * 0.60f, h * 0.36f, shape)
            }
            else -> {
                val p = android.graphics.Path().apply {
                    moveTo(0f, h * 0.62f); lineTo(w * 0.42f, h * 0.30f); lineTo(w * 0.70f, h * 0.50f)
                    lineTo(w.toFloat(), h * 0.36f); lineTo(w.toFloat(), h * 0.62f); close()
                }
                c.drawPath(p, shape)
            }
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F4F7.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 34f
        }
        // Simple word wrap at the bottom of the poster.
        val lines = mutableListOf<String>()
        var line = ""
        title.uppercase().split(" ").forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(next) > w - 40) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        var y = h - 30f - (lines.size - 1) * 40f
        lines.forEach { c.drawText(it, 22f, y, text); y += 40f }
        return bmp
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = ((color shr 16 and 0xFF) * factor).toInt()
        val g = ((color shr 8 and 0xFF) * factor).toInt()
        val bl = ((color and 0xFF) * factor).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

/** Still picture in place of the live video: a dusk landscape, drawn here. */
@Composable
private fun DemoVideo() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF1B2A4A), Color(0xFF6B4A7A), Color(0xFFF2A65A)),
                startY = 0f, endY = size.height * 0.75f
            )
        )
        drawCircle(Color(0xFFFFD27A), radius = size.minDimension * 0.11f, center = Offset(size.width * 0.68f, size.height * 0.58f))
        fun hills(base: Float, amp: Float, color: Color, phase: Float) {
            val p = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, size.height * base)
                val steps = 6
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    val y = size.height * (base - amp * kotlin.math.sin(i * 1.3f + phase))
                    lineTo(x, y)
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(p, color)
        }
        hills(0.70f, 0.06f, Color(0xFF3A2D4F), 0.4f)
        hills(0.80f, 0.05f, Color(0xFF221B33), 1.9f)
        hills(0.90f, 0.03f, Color(0xFF120F1C), 3.1f)
    }
}
