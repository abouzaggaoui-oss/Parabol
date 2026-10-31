package app.parabol.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Everything loaded for one account: the groups and, per group id, its items.
 * Kept on the device, so the app shows it immediately on the next start.
 * Fields are nullable because Gson can leave them null when reading an older file.
 */
data class ContentSnapshot(
    val liveCategories: List<Category>? = null,
    val vodCategories: List<Category>? = null,
    val seriesCategories: List<Category>? = null,
    val live: Map<String, List<LiveStream>>? = null,
    val vod: Map<String, List<VodStream>>? = null,
    val series: Map<String, List<SeriesStream>>? = null,
) {
    val isEmpty get() = live.isNullOrEmpty() && vod.isNullOrEmpty() && series.isNullOrEmpty()
}

private data class CategoryLists(
    val live: List<Category>? = null,
    val vod: List<Category>? = null,
    val series: List<Category>? = null,
)

/**
 * Files per account in the app's private storage: one for the group lists and one per
 * content type, so live TV is saved (and read) without waiting for a large movie list.
 * Reads and writes are buffered; a file is replaced in one step, so a crash or the app
 * being closed while saving never leaves half a file.
 */
class ContentCache(private val dir: File) {
    private val gson = Gson()

    /** Last read/write problem, shown under "About Parabol" to help diagnose. */
    @Volatile var lastError: String? = null

    private fun file(profileId: String, part: String) =
        File(dir, "${profileId.replace(Regex("[^A-Za-z0-9_-]"), "_")}-$part.json")

    private fun <T> read(profileId: String, part: String, type: java.lang.reflect.Type): T? = runCatching {
        val f = file(profileId, part)
        if (!f.exists()) null
        else f.inputStream().buffered(BUFFER).reader().use { gson.fromJson<T>(it, type) }
    }.onFailure { lastError = "lezen $part: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}" }.getOrNull()

    /** Returns true when the file was written. */
    private fun write(profileId: String, part: String, value: Any): Boolean = runCatching {
        dir.mkdirs()
        val target = file(profileId, part)
        val tmp = File(dir, target.name + ".tmp")
        tmp.outputStream().buffered(BUFFER).writer().use { gson.toJson(value, it) }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed" }
        }
        true
    }.getOrElse {
        lastError = "opslaan $part: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}"
        false
    }

    fun load(profileId: String): ContentSnapshot? {
        val cats = loadGroups(profileId)
        val live = loadLive(profileId)
        val vod = loadVod(profileId)
        val series = loadSeries(profileId)
        if (cats == null && live == null && vod == null && series == null) return null
        return ContentSnapshot(cats?.first, cats?.second, cats?.third, live, vod, series)
    }

    /** The group lists (live, vod, series); null when nothing is stored. */
    fun loadGroups(profileId: String): Triple<List<Category>?, List<Category>?, List<Category>?>? {
        // Version 2.6.0 kept one big file per account (often never finished): remove it.
        val safe = profileId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        runCatching { File(dir, "$safe.json").delete(); File(dir, "$safe.json.tmp").delete() }
        val c: CategoryLists = read(profileId, "groups", CategoryLists::class.java) ?: return null
        return Triple(c.live, c.vod, c.series)
    }

    fun loadLive(profileId: String): Map<String, List<LiveStream>>? =
        read(profileId, "live", object : TypeToken<Map<String, List<LiveStream>>>() {}.type)

    fun loadVod(profileId: String): Map<String, List<VodStream>>? =
        read(profileId, "vod", object : TypeToken<Map<String, List<VodStream>>>() {}.type)

    fun loadSeries(profileId: String): Map<String, List<SeriesStream>>? =
        read(profileId, "series", object : TypeToken<Map<String, List<SeriesStream>>>() {}.type)

    fun saveGroups(profileId: String, live: List<Category>, vod: List<Category>, series: List<Category>) =
        write(profileId, "groups", CategoryLists(live, vod, series))

    /** [part] is "live", "vod" or "series". */
    fun saveContent(profileId: String, part: String, groups: Map<String, List<Any>>) = write(profileId, part, groups)

    fun delete(profileId: String) {
        listOf("groups", "live", "vod", "series").forEach { runCatching { file(profileId, it).delete() } }
    }

    private companion object { const val BUFFER = 256 * 1024 }
}

/**
 * Decides what to download for one content type (live, movies or series).
 *
 * - Groups already on the device are kept as they are; only visible groups that are
 *   missing are fetched ([force] fetches all visible groups again: "Refresh content").
 * - Few groups to fetch: one request per group (`category_id`), a few at a time.
 *   Many groups, or nothing on the device yet: one request for everything, which is
 *   faster than hundreds of small ones.
 * - Providers that ignore `category_id` answer with everything; that answer is used as is.
 */
object ContentLoader {

    /** Above this many groups to fetch, one request for everything is faster. */
    const val BULK_THRESHOLD = 25
    private const val PARALLEL = 6

    /** [changed]: something was fetched or dropped, so the stored copy needs saving. */
    data class Outcome<T>(
        val groups: Map<String, List<T>>,
        val failed: Boolean,
        val changed: Boolean,
        /** What was downloaded: "" nothing, "all" everything, or the number of groups. */
        val fetched: String = "",
    )

    suspend fun <T> refresh(
        categories: List<Category>,
        cached: Map<String, List<T>>,
        isHidden: (String) -> Boolean,
        force: Boolean,
        categoryOf: (T) -> String?,
        fetchAll: suspend () -> List<T>?,
        fetchGroup: suspend (String) -> List<T>?,
    ): Outcome<T> {
        val known = categories.map { it.categoryId }.toSet()
        // Groups the provider no longer has are dropped (only when the group list itself loaded).
        val kept = if (categories.isEmpty()) cached else cached.filterKeys { it in known }
        val pruned = kept.size != cached.size
        val visible = categories.map { it.categoryId }.filter { !isHidden(it) }
        val needed = if (force) visible else visible.filter { it !in kept }
        if (needed.isEmpty()) return Outcome(kept, failed = false, changed = pruned)

        if (kept.isEmpty() || needed.size > BULK_THRESHOLD) {
            val all = fetchAll() ?: return Outcome(kept, failed = true, changed = pruned)
            // Everything was fetched, so every known group is now complete (also empty ones).
            val grouped = all.groupBy { categoryOf(it) ?: "" }
            val result = kept.toMutableMap()
            known.forEach { id -> result[id] = grouped[id].orEmpty() }
            grouped.forEach { (id, items) -> if (id !in known) result[id] = items }
            return Outcome(result, failed = false, changed = true, fetched = "all")
        }

        val result = kept.toMutableMap()
        var failed = false
        var fetched = false

        // Ask for the first group alone: if the provider ignores category_id, the answer is
        // simply everything, and the remaining requests are not needed.
        val first = needed.first()
        val firstItems = fetchGroup(first)
        if (firstItems == null) {
            failed = true
        } else if (firstItems.any { categoryOf(it) != first }) {
            val grouped = firstItems.groupBy { categoryOf(it) ?: "" }
            known.forEach { id -> result[id] = grouped[id].orEmpty() }
            return Outcome(result, failed = false, changed = true, fetched = "all")
        } else {
            result[first] = firstItems
            fetched = true
        }

        val limiter = Semaphore(PARALLEL)
        val rest = coroutineScope {
            needed.drop(1).map { id -> async { id to limiter.withPermit { fetchGroup(id) } } }.map { it.await() }
        }
        rest.forEach { (id, items) ->
            if (items == null) failed = true
            else { result[id] = items.filter { categoryOf(it) == id }; fetched = true }
        }
        return Outcome(result, failed, changed = fetched || pruned, fetched = needed.size.toString())
    }
}
