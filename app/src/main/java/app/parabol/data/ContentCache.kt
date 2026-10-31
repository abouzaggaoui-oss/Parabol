package app.parabol.data

import com.google.gson.Gson
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

/** One JSON file per account in the app's private storage. */
class ContentCache(private val dir: File) {
    private val gson = Gson()

    private fun file(profileId: String) = File(dir, "${profileId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun load(profileId: String): ContentSnapshot? = runCatching {
        file(profileId).takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, ContentSnapshot::class.java) }
    }.getOrNull()

    fun save(profileId: String, snapshot: ContentSnapshot) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file(profileId).name + ".tmp")
            tmp.writer().use { gson.toJson(snapshot, it) }
            tmp.renameTo(file(profileId)) // replace in one step, so a crash never leaves half a file
        }
    }

    fun delete(profileId: String) {
        runCatching { file(profileId).delete() }
    }
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

    data class Outcome<T>(val groups: Map<String, List<T>>, val failed: Boolean)

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
        val visible = categories.map { it.categoryId }.filter { !isHidden(it) }
        val needed = if (force) visible else visible.filter { it !in kept }
        if (needed.isEmpty()) return Outcome(kept, failed = false)

        if (kept.isEmpty() || needed.size > BULK_THRESHOLD) {
            val all = fetchAll() ?: return Outcome(kept, failed = true)
            // Everything was fetched, so every known group is now complete (also empty ones).
            val grouped = all.groupBy { categoryOf(it) ?: "" }
            val result = kept.toMutableMap()
            known.forEach { id -> result[id] = grouped[id].orEmpty() }
            grouped.forEach { (id, items) -> if (id !in known) result[id] = items }
            return Outcome(result, failed = false)
        }

        val result = kept.toMutableMap()
        var failed = false

        // Ask for the first group alone: if the provider ignores category_id, the answer is
        // simply everything, and the remaining requests are not needed.
        val first = needed.first()
        val firstItems = fetchGroup(first)
        if (firstItems == null) {
            failed = true
        } else if (firstItems.any { categoryOf(it) != first }) {
            val grouped = firstItems.groupBy { categoryOf(it) ?: "" }
            known.forEach { id -> result[id] = grouped[id].orEmpty() }
            return Outcome(result, failed = false)
        } else {
            result[first] = firstItems
        }

        val limiter = Semaphore(PARALLEL)
        val rest = coroutineScope {
            needed.drop(1).map { id -> async { id to limiter.withPermit { fetchGroup(id) } } }.map { it.await() }
        }
        rest.forEach { (id, items) ->
            if (items == null) failed = true else result[id] = items.filter { categoryOf(it) == id }
        }
        return Outcome(result, failed)
    }
}
