package app.parabol

import app.parabol.data.Category
import app.parabol.data.ContentLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What gets downloaded for which situation (plain JVM test, no Android needed). */
class ContentLoaderTest {

    private data class Item(val cat: String, val id: Int)

    /** A fake provider with [groups] groups of 3 items each; counts the requests. */
    private class Provider(groups: Int, val ignoresCategory: Boolean = false, val failing: Boolean = false) {
        val categories = (1..groups).map { Category("$it", "Groep $it") }
        val items = categories.flatMap { c -> (1..3).map { Item(c.categoryId, it) } }
        var allCalls = 0
        val groupCalls = mutableListOf<String>()
        suspend fun all(): List<Item>? { allCalls++; return if (failing) null else items }
        suspend fun group(id: String): List<Item>? {
            groupCalls += id
            return when {
                failing -> null
                ignoresCategory -> items
                else -> items.filter { it.cat == id }
            }
        }
    }

    private fun run(p: Provider, cached: Map<String, List<Item>>, hidden: Set<String> = emptySet(), force: Boolean = false) =
        runBlocking {
            ContentLoader.refresh(p.categories, cached, { it in hidden }, force, { it.cat }, { p.all() }, { p.group(it) })
        }

    private fun cacheOf(p: Provider, vararg ids: String) = ids.associateWith { id -> p.items.filter { it.cat == id } }

    @Test fun firstLoadFetchesEverythingInOneRequest() {
        val p = Provider(40)
        val out = run(p, emptyMap())
        assertEquals(1, p.allCalls)
        assertTrue(p.groupCalls.isEmpty())
        assertEquals(40, out.groups.size)
    }

    @Test fun cachedGroupsAreNotFetchedAgain() {
        val p = Provider(5)
        val out = run(p, cacheOf(p, "1", "2", "3", "4", "5"))
        assertEquals(0, p.allCalls)
        assertTrue(p.groupCalls.isEmpty())
        assertEquals(5, out.groups.size)
    }

    @Test fun onlyNewVisibleGroupsAreFetched() {
        val p = Provider(6)
        val out = run(p, cacheOf(p, "1", "2", "3", "4"), hidden = setOf("6"))
        assertEquals(0, p.allCalls)
        assertEquals(listOf("5"), p.groupCalls)
        assertEquals(3, out.groups["5"]!!.size)
        assertFalse(out.groups.containsKey("6"))
    }

    @Test fun refreshFetchesVisibleGroupsOnly() {
        val p = Provider(6)
        run(p, cacheOf(p, "1", "2", "3", "4", "5", "6"), hidden = setOf("4", "5", "6"), force = true)
        assertEquals(0, p.allCalls)
        assertEquals(setOf("1", "2", "3"), p.groupCalls.toSet())
    }

    @Test fun manyMissingGroupsUseOneRequest() {
        val p = Provider(60)
        run(p, cacheOf(p, "1"))
        assertEquals(1, p.allCalls)
        assertTrue(p.groupCalls.isEmpty())
    }

    @Test fun providerIgnoringCategoryIsAskedOnce() {
        val p = Provider(6, ignoresCategory = true)
        val out = run(p, cacheOf(p, "1"))
        assertEquals(1, p.groupCalls.size)
        assertEquals(6, out.groups.size)
        assertEquals(3, out.groups["4"]!!.size)
    }

    @Test fun failureKeepsWhatWasCached() {
        val p = Provider(4, failing = true)
        val out = run(p, cacheOf(p, "1", "2"), force = true)
        assertTrue(out.failed)
        assertEquals(setOf("1", "2"), out.groups.keys)
    }

    @Test fun groupsGoneFromProviderAreDropped() {
        val p = Provider(3)
        val out = run(p, cacheOf(p, "1", "2", "3") + mapOf("99" to listOf(Item("99", 1))))
        assertFalse(out.groups.containsKey("99"))
    }
}
