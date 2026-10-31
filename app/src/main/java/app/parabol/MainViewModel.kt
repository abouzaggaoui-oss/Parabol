package app.parabol

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.parabol.data.*
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val store = Store(app)
    private var api: XtreamApi? = null

    /** Loaded content per account, kept on the device so the next start shows it at once. */
    private val contentCache = ContentCache(java.io.File(app.filesDir, "content"))
    private var snapshot: ContentSnapshot? = null
    private var loadToken = 0
    private val saveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val saveLock = kotlinx.coroutines.sync.Mutex()

    // ---- UI state ----
    var isLoggedIn by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var loginError by mutableStateOf(false)
    /** Goes up by one after every successful login / added account (the login screen watches it). */
    var loginSuccessTick by mutableStateOf(0); private set
    /** True when the active provider could not be reached; the UI shows a retry banner. */
    var contentError by mutableStateOf(false); private set
    var userInfo by mutableStateOf<UserInfo?>(null); private set

    var liveCategories by mutableStateOf<List<Category>>(emptyList()); private set
    var vodCategories by mutableStateOf<List<Category>>(emptyList()); private set
    var seriesCategories by mutableStateOf<List<Category>>(emptyList()); private set

    var liveStreams by mutableStateOf<List<LiveStream>>(emptyList()); private set
    var vodStreams by mutableStateOf<List<VodStream>>(emptyList()); private set
    var seriesList by mutableStateOf<List<SeriesStream>>(emptyList()); private set

    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    /** Guide time correction in hours for the active account (see Settings). */
    var epgOffsetHours by mutableStateOf(0); private set
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet()); private set

    // Short EPG per channel (current + upcoming programmes), loaded lazily per visible row.
    val epgCache = mutableStateMapOf<Int, ShortEpg>()
    private val epgInFlight = mutableSetOf<Int>()
    // Many rows become visible at once; cap parallel guide requests so the provider isn't flooded.
    private val epgLimiter = Semaphore(6)

    /** Last group opened in Live TV, so re-entering the screen returns to it. */
    var lastLiveGroupId: String? = null
    /** Live session to resume in split view when coming back from the full TV guide. */
    var resumeLiveSession: LiveSession? = null
    var fullEpg by mutableStateOf<List<EpgListing>?>(null); private set
    var epgLoading by mutableStateOf(false); private set

    var seriesInfo by mutableStateOf<SeriesInfoResponse?>(null); private set
    var seriesInfoLoading by mutableStateOf(false); private set

    var profiles by mutableStateOf<List<Profile>>(emptyList()); private set
    var activeProfile by mutableStateOf<Profile?>(null); private set

    var scheduledRecordings by mutableStateOf<List<ScheduledRecording>>(emptyList()); private set
    var completedRecordings by mutableStateOf<List<CompletedRecording>>(emptyList()); private set

    // Running jobs, so a newer request can cancel an older one instead of being
    // overwritten by its (late) result, e.g. when switching accounts or channels quickly.
    private var loadAllJob: Job? = null
    private var epgJob: Job? = null
    private var seriesInfoJob: Job? = null

    // RecordingWorker writes to the same SharedPreferences from the background.
    // Listening here keeps the Recordings tab up to date without reopening the app.
    private val recordingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "scheduledRecordings" || key == "completedRecordings") refreshRecordings()
    }

    init {
        store.registerListener(recordingsListener)
        refreshProfiles()
        refreshRecordings()
        // The app always starts on the account screen; the chosen account is opened from there.
    }

    override fun onCleared() {
        store.unregisterListener(recordingsListener)
        super.onCleared()
    }

    private fun refreshProfiles() {
        profiles = store.getProfiles()
        activeProfile = store.activeProfile()
    }

    private fun refreshRecordings() {
        val id = store.activeProfileId
        scheduledRecordings = store.getScheduledRecordings().filter { it.profileId == id }
        completedRecordings = store.getCompletedRecordings().filter { it.profileId == id }
    }

    // ---- Connection & accounts ----

    /** Used both for the very first login and for adding a further account in Settings. */
    fun connect(baseUrl: String, username: String, password: String, profileName: String = "") {
        loading = true
        loginError = false
        viewModelScope.launch {
            try {
                val client = XtreamApi.create(baseUrl)
                val response = client.login(username, password)
                val ok = response.userInfo?.auth == 1 ||
                        response.userInfo?.status.equals("Active", ignoreCase = true)
                if (ok) {
                    // Re-entering an existing login updates that account instead of
                    // creating a duplicate (with its own empty favorites).
                    val profile = store.upsertProfile(
                        profileName.ifBlank { username }, baseUrl.trim(), username.trim(), password.trim()
                    )
                    store.activeProfileId = profile.id
                    clearContent()
                    api = client
                    userInfo = response.userInfo
                    favorites = store.getFavorites()
                    hiddenGroups = store.getHiddenGroups()
                    epgOffsetHours = 0 // the guide time correction setting was removed
                    contentError = false
                    isLoggedIn = true
                    refreshProfiles()
                    refreshRecordings()
                    loadAll()
                    loginSuccessTick++
                } else {
                    loginError = true
                }
            } catch (e: Exception) {
                loginError = true
            } finally {
                loading = false
            }
        }
    }

    /** Switches the active account and reloads everything scoped to it. */
    fun switchProfile(id: String) {
        val profile = store.getProfiles().firstOrNull { it.id == id } ?: return
        val switchingAccount = activeProfile?.id != id || liveCategories.isEmpty()
        store.activeProfileId = id
        loading = true
        loginError = false
        if (switchingAccount) clearContent() // never show account A's lists under account B
        // Favorites/groups/recordings are local, so show them even if the server is unreachable.
        favorites = store.getFavorites()
        hiddenGroups = store.getHiddenGroups()
        epgOffsetHours = 0 // the guide time correction setting was removed
        refreshProfiles()
        refreshRecordings()
        // Keep a client even if login fails, so "retry" and playback URLs still work.
        // create() throws on a malformed URL, so guard it rather than crash at startup.
        val client = runCatching { XtreamApi.create(profile.baseUrl) }.getOrNull()
        api = client
        isLoggedIn = true
        if (client == null) {
            contentError = true
            loading = false
            return
        }
        viewModelScope.launch {
            try {
                val response = client.login(profile.username, profile.password)
                userInfo = response.userInfo
                contentError = false
                loadAll()
            } catch (e: Exception) {
                contentError = true
                loading = false
            }
        }
    }

    /**
     * Runs a network call and returns null on failure — but lets cancellation through,
     * so a cancelled (superseded) request never writes "failed"/"empty" state.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Called from the error banner. */
    fun retryConnection() {
        val profile = store.activeProfile() ?: return
        switchProfile(profile.id)
    }

    private fun clearContent() {
        loadAllJob?.cancel()
        snapshot = null
        liveCategories = emptyList(); vodCategories = emptyList(); seriesCategories = emptyList()
        liveStreams = emptyList(); vodStreams = emptyList(); seriesList = emptyList()
        epgCache.clear()
        epgInFlight.clear()
        lastLiveGroupId = null
        resumeLiveSession = null
        fullEpg = null
        seriesInfo = null
    }

    /** Removes an account. If it was the active one, switches to another if available. */
    fun removeProfile(id: String) {
        // Don't leave background recordings running for an account that no longer exists.
        store.getScheduledRecordings().filter { it.profileId == id }.forEach {
            app.parabol.recording.RecordingScheduler.cancel(getApplication<Application>(), it.id)
            store.removeScheduledRecording(it.id)
        }
        store.removeProfile(id)
        saveScope.launch { saveLock.withLock { contentCache.delete(id) } }
        refreshProfiles()
        val next = store.activeProfile()
        if (next != null) {
            switchProfile(next.id)
        } else {
            api = null
            isLoggedIn = false
            userInfo = null
            contentError = false
            clearContent()
            loading = false
            refreshRecordings()
        }
    }

    /** Removes the currently active account (used by the Settings "logout" button). */
    fun logout() {
        val id = store.activeProfileId
        if (id.isNotBlank()) removeProfile(id)
    }

    // ---- Content ----

    /**
     * Shows the content kept on the device right away, then fetches only what is missing:
     * visible groups that were never loaded (new groups, or a group switched on in
     * "Manage groups"). [force] ("Refresh content") fetches all visible groups again.
     */
    fun loadAll(force: Boolean = false) {
        val a = api ?: return
        val profile = store.activeProfile() ?: return
        val u = profile.username
        val p = profile.password
        loadAllJob?.cancel()
        val token = ++loadToken
        loadAllJob = viewModelScope.launch {
            if (snapshot == null) {
                // Reading a large stored list takes a moment: show "loading" meanwhile.
                loading = true
                snapshot = withContext(Dispatchers.IO) { contentCache.load(profile.id) }
                snapshot?.let { applySnapshot(it) }
            }
            val old = snapshot ?: ContentSnapshot()
            // Only show "loading" when there is nothing to show yet.
            loading = old.isEmpty
            val hidden = hiddenGroups
            try {
                coroutineScope {
                    // The group lists are small; fetch them first (in parallel).
                    val lc = async { attempt { a.getLiveCategories(u, p) } }
                    val vc = async { attempt { a.getVodCategories(u, p) } }
                    val sc = async { attempt { a.getSeriesCategories(u, p) } }
                    val liveCats = lc.await() ?: old.liveCategories.orEmpty()
                    val vodCats = vc.await() ?: old.vodCategories.orEmpty()
                    val seriesCats = sc.await() ?: old.seriesCategories.orEmpty()
                    liveCategories = liveCats; vodCategories = vodCats; seriesCategories = seriesCats
                    if (liveCats != old.liveCategories.orEmpty() || vodCats != old.vodCategories.orEmpty() ||
                        seriesCats != old.seriesCategories.orEmpty()
                    ) persist(profile.id) { contentCache.saveGroups(it, liveCats, vodCats, seriesCats) }

                    // Each type is shown and saved as soon as it is done, without waiting for the others.
                    val live = async {
                        ContentLoader.refresh(liveCats, old.live.orEmpty(), { "live:$it" in hidden }, force,
                            { it.categoryId }, { attempt { a.getLiveStreams(u, p) } },
                            { id -> attempt { a.getLiveStreams(u, p, categoryId = id) } }
                        ).also { o ->
                            liveStreams = o.groups.values.flatten()
                            if (o.changed) persist(profile.id) { contentCache.saveContent(it, "live", o.groups) }
                        }
                    }
                    val vod = async {
                        ContentLoader.refresh(vodCats, old.vod.orEmpty(), { "vod:$it" in hidden }, force,
                            { it.categoryId }, { attempt { a.getVodStreams(u, p) } },
                            { id -> attempt { a.getVodStreams(u, p, categoryId = id) } }
                        ).also { o ->
                            vodStreams = o.groups.values.flatten()
                            if (o.changed) persist(profile.id) { contentCache.saveContent(it, "vod", o.groups) }
                        }
                    }
                    val series = async {
                        ContentLoader.refresh(seriesCats, old.series.orEmpty(), { "series:$it" in hidden }, force,
                            { it.categoryId }, { attempt { a.getSeries(u, p) } },
                            { id -> attempt { a.getSeries(u, p, categoryId = id) } }
                        ).also { o ->
                            seriesList = o.groups.values.flatten()
                            if (o.changed) persist(profile.id) { contentCache.saveContent(it, "series", o.groups) }
                        }
                    }
                    val l = live.await(); val v = vod.await(); val s = series.await()
                    val next = ContentSnapshot(liveCats, vodCats, seriesCats, l.groups, v.groups, s.groups)
                    snapshot = next
                    val nothingLoaded = lc.await() == null && vc.await() == null && sc.await() == null
                    contentError = (nothingLoaded || (l.failed && v.failed && s.failed)) && next.isEmpty
                }
            } finally {
                if (token == loadToken) loading = false // a newer load manages the flag itself
            }
        }
    }

    /**
     * Saves in the background, outside the loading job: the screen never waits for it,
     * and a newer load (or leaving the screen) doesn't cut a save off halfway.
     * Saves run one at a time.
     */
    private fun persist(profileId: String, block: (String) -> Unit) {
        saveScope.launch { saveLock.withLock { block(profileId) } }
    }

    private fun applySnapshot(s: ContentSnapshot) {
        if (!s.liveCategories.isNullOrEmpty()) liveCategories = s.liveCategories
        if (!s.vodCategories.isNullOrEmpty()) vodCategories = s.vodCategories
        if (!s.seriesCategories.isNullOrEmpty()) seriesCategories = s.seriesCategories
        liveStreams = s.live.orEmpty().values.flatten()
        vodStreams = s.vod.orEmpty().values.flatten()
        seriesList = s.series.orEmpty().values.flatten()
    }

    fun channelsInCategory(categoryId: String): List<LiveStream> =
        liveStreams.filter { it.categoryId == categoryId }

    fun moviesInCategory(categoryId: String): List<VodStream> =
        vodStreams.filter { it.categoryId == categoryId }

    fun seriesInCategory(categoryId: String): List<SeriesStream> =
        seriesList.filter { it.categoryId == categoryId }

    // ---- Favorites ----

    fun isFavorite(key: String) = favorites.contains(key)

    fun toggleFavorite(key: String) {
        val next = favorites.toMutableSet()
        if (!next.add(key)) next.remove(key)
        favorites = next
        store.setFavorites(next)
    }


    // ---- Hidden groups ----

    fun isGroupHidden(type: String, categoryId: String) =
        hiddenGroups.contains("$type:$categoryId")

    fun toggleGroupHidden(type: String, categoryId: String) {
        val key = "$type:$categoryId"
        val next = hiddenGroups.toMutableSet()
        if (!next.add(key)) next.remove(key)
        hiddenGroups = next
        store.setHiddenGroups(next)
        if (key !in next) loadAll() // now visible: fetched if it was never loaded
    }

    fun visibleCategories(type: String, all: List<Category>): List<Category> =
        all.filter { !hiddenGroups.contains("$type:${it.categoryId}") }

    // ---- EPG ----

    /** Loads current + upcoming programmes for a channel; refreshed after 15 minutes. */
    fun loadShortEpg(streamId: Int) {
        val a = api ?: return
        val cached = epgCache[streamId]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 15 * 60_000L) return
        if (!epgInFlight.add(streamId)) return
        viewModelScope.launch {
            val r = epgLimiter.withPermit {
                attempt { a.getShortEpg(profileUsername, profilePassword, streamId, 10) }
            }
            epgInFlight.remove(streamId)
            if (r != null) {
                epgCache[streamId] = ShortEpg(r.listings ?: emptyList(), System.currentTimeMillis())
            }
        }
    }

    /** The programme on now and the ones after it, using the account's guide time correction. */
    fun nowAndNext(streamId: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): NowNext {
        val listings = epgCache[streamId]?.listings ?: return NowNext(null, emptyList())
        val offset = epgOffsetHours
        val upcoming = listings
            .filter { it.stopSeconds(offset) > nowSeconds }
            .sortedBy { it.startSeconds(offset) }
        val current = upcoming.firstOrNull { it.startSeconds(offset) <= nowSeconds }
        val next = if (current != null) upcoming.drop(1) else upcoming
        return NowNext(current, next)
    }

    /**
     * Fills the screens with fictional content without contacting a server.
     * Only used by the screenshot tests that render the README images.
     */
    @androidx.annotation.VisibleForTesting
    internal fun showDemoContent(
        profile: Profile,
        user: UserInfo,
        liveGroups: List<Category>,
        channels: List<LiveStream>,
        movieGroups: List<Category>,
        movies: List<VodStream>,
        seriesGroups: List<Category>,
        series: List<SeriesStream>,
        favoriteKeys: Set<String>,
        guide: Map<Int, List<EpgListing>>,
        hiddenKeys: Set<String> = emptySet(),
        allProfiles: List<Profile>? = null
    ) {
        loadAllJob?.cancel()
        activeProfile = profile
        profiles = allProfiles ?: listOf(profile)
        userInfo = user
        liveCategories = liveGroups
        liveStreams = channels
        vodCategories = movieGroups
        vodStreams = movies
        seriesCategories = seriesGroups
        seriesList = series
        favorites = favoriteKeys
        hiddenGroups = hiddenKeys
        epgOffsetHours = 0
        epgCache.clear()
        val now = System.currentTimeMillis()
        guide.forEach { (id, listings) -> epgCache[id] = ShortEpg(listings, now) }
        contentError = false
        loading = false
        isLoggedIn = true
    }

    val favoriteChannels: List<LiveStream>
        get() = liveStreams.filter { favorites.contains("live:${it.streamId}") }

    val favoriteMovies: List<VodStream>
        get() = vodStreams.filter { favorites.contains("vod:${it.streamId}") }

    val favoriteSeries: List<SeriesStream>
        get() = seriesList.filter { favorites.contains("series:${it.seriesId}") }

    fun loadFullEpg(streamId: Int) {
        val a = api ?: return
        fullEpg = null
        epgLoading = true
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            fullEpg = attempt { a.getFullEpg(profileUsername, profilePassword, streamId) }
                ?.listings ?: emptyList()
            epgLoading = false
        }
    }

    // ---- Series detail ----

    fun loadSeriesInfo(seriesId: Int) {
        val a = api ?: return
        seriesInfo = null
        seriesInfoLoading = true
        seriesInfoJob?.cancel()
        seriesInfoJob = viewModelScope.launch {
            seriesInfo = attempt { a.getSeriesInfo(profileUsername, profilePassword, seriesId) }
            seriesInfoLoading = false
        }
    }

    // ---- Groups: search, visibility and countries (operate on GROUP names) ----

    private val codeRegex = Regex("^[^A-Za-z0-9]*([A-Za-z]{2,3})\\s*[|:\\-–—•]")

    // Prefixes that providers put in front of group names but that are not a country/language.
    private val notACountry = setOf(
        "HD", "FHD", "UHD", "SD", "VIP", "PPV", "VOD", "XXX", "TOP", "NEW", "ALL", "TV", "THE", "LIVE"
    )

    /** "NL | Sport" -> "NL"; null when the name doesn't start with a country/language code. */
    fun extractCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val match = codeRegex.find(name.trim()) ?: return null
        return match.groupValues[1].uppercase().takeUnless { it in notACountry }
    }

    /**
     * Groups of one type bundled per country code, largest first, with "other" (code null) last.
     * A code needs at least two groups to count as a country; the rest goes to "other".
     */
    fun countryGroups(type: String, categories: List<Category>): List<CountryGroup> {
        val byCode = categories.groupBy { extractCode(it.categoryName) }
        val (countries, rest) = byCode.entries.partition { it.key != null && it.value.size >= 2 }
        val result = countries
            .sortedWith(compareByDescending<Map.Entry<String?, List<Category>>> { it.value.size }.thenBy { it.key })
            .map { CountryGroup(it.key, it.value, visibleCount(type, it.value)) }
            .toMutableList()
        val other = rest.flatMap { it.value }
        if (other.isNotEmpty()) result += CountryGroup(null, other, visibleCount(type, other))
        return result
    }

    private fun visibleCount(type: String, categories: List<Category>) =
        categories.count { !hiddenGroups.contains("$type:${it.categoryId}") }

    /** Whole country on or off: fully visible -> hide all, otherwise -> show all. */
    fun toggleCountry(type: String, country: CountryGroup) {
        setAllGroupsHidden(type, country.categories, hidden = country.allVisible)
    }

    /** Visible groups matching [query]. Content search still happens inside a group. */
    fun filterLiveCategories(query: String): List<Category> = visibleGroups("live", liveCategories, query)
    fun filterVodCategories(query: String): List<Category> = visibleGroups("vod", vodCategories, query)
    fun filterSeriesCategories(query: String): List<Category> = visibleGroups("series", seriesCategories, query)

    private fun visibleGroups(type: String, categories: List<Category>, query: String): List<Category> =
        categories.filter {
            !hiddenGroups.contains("$type:${it.categoryId}") &&
                (query.isBlank() || it.categoryName.contains(query, ignoreCase = true))
        }

    fun setAllGroupsHidden(type: String, categories: List<Category>, hidden: Boolean) {
        val next = hiddenGroups.toMutableSet()
        categories.forEach { cat ->
            val key = "$type:${cat.categoryId}"
            if (hidden) next.add(key) else next.remove(key)
        }
        hiddenGroups = next
        store.setHiddenGroups(next)
        if (!hidden) loadAll() // fetch groups that are now visible but were never loaded
    }


    // ---- Stream URL builders ----

    private val base get() = store.activeProfile()?.baseUrl?.trimEnd('/') ?: ""
    private val profileUsername get() = store.activeProfile()?.username ?: ""
    private val profilePassword get() = store.activeProfile()?.password ?: ""

    private val isDemo get() = app.parabol.data.DemoServer.isDemo(base)

    fun liveUrl(streamId: Int): String {
        if (isDemo) return app.parabol.data.DemoServer.liveUrl(streamId)
        val ext = if (store.useHls) "m3u8" else "ts"
        return "$base/live/$profileUsername/$profilePassword/$streamId.$ext"
    }

    fun movieUrl(streamId: Int, containerExtension: String?): String {
        if (isDemo) return app.parabol.data.DemoServer.movieUrl(streamId)
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/movie/$profileUsername/$profilePassword/$streamId.$ext"
    }

    fun episodeUrl(episodeId: String, containerExtension: String?): String {
        if (isDemo) return app.parabol.data.DemoServer.episodeUrl(episodeId)
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/series/$profileUsername/$profilePassword/$episodeId.$ext"
    }

    /**
     * Server-side catch-up ("watch back") URL for a past programme.
     *
     * The timeshift start time must be in the SERVER's local time. The guide's `start`
     * field ("yyyy-MM-dd HH:mm:ss") is already in server time, so it is used as-is;
     * the previous version converted the epoch timestamp to UTC, which starts playback
     * hours off on any server not running in UTC. The epoch is only a fallback.
     * Timeshift is requested as .ts: many panels don't serve timeshift over HLS.
     */
    fun catchupUrl(streamId: Int, serverStart: String?, startEpochSeconds: Long, durationMinutes: Int): String {
        if (isDemo) return app.parabol.data.DemoServer.liveUrl(streamId)
        val match = serverStart?.let {
            Regex("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(it)
        }
        val startStr = if (match != null) {
            val (date, hour, minute) = match.destructured
            if (epgOffsetHours == 0) {
                "$date:$hour-$minute"
            } else {
                // Shift the server-local start by the same correction the guide shows.
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val shifted = (fmt.parse("$date $hour:$minute")?.time ?: 0L) + epgOffsetHours * 3_600_000L
                val out = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
                out.timeZone = java.util.TimeZone.getTimeZone("UTC")
                out.format(java.util.Date(shifted))
            }
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(startEpochSeconds * 1000))
        }
        return "$base/timeshift/$profileUsername/$profilePassword/$durationMinutes/$startStr/$streamId.ts"
    }

    // ---- Recordings ----

    fun scheduleRecording(channel: LiveStream, programTitle: String, startEpochSeconds: Long, endEpochSeconds: Long) {
        val profile = store.activeProfile() ?: return
        val nowSeconds = System.currentTimeMillis() / 1000
        val recording = ScheduledRecording(
            id = java.util.UUID.randomUUID().toString(),
            profileId = profile.id,
            streamId = channel.streamId,
            channelName = channel.name,
            programTitle = programTitle,
            // "Record now" for a programme that is already on: start immediately.
            startEpochSeconds = maxOf(startEpochSeconds, nowSeconds),
            endEpochSeconds = endEpochSeconds
        )
        store.addScheduledRecording(recording)
        app.parabol.recording.RecordingScheduler.schedule(getApplication<Application>(), recording, profile)
        refreshRecordings()
    }

    fun cancelScheduledRecording(recording: ScheduledRecording) {
        app.parabol.recording.RecordingScheduler.cancel(getApplication<Application>(), recording.id)
        store.removeScheduledRecording(recording.id)
        refreshRecordings()
    }

    fun deleteCompletedRecording(recording: CompletedRecording) {
        try {
            getApplication<Application>().contentResolver.delete(
                android.net.Uri.parse(recording.contentUri), null, null
            )
        } catch (_: Exception) {
        }
        store.removeCompletedRecording(recording.id)
        refreshRecordings()
    }
}
