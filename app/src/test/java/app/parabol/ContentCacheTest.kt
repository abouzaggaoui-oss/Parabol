package app.parabol

import app.parabol.data.Category
import app.parabol.data.ContentCache
import app.parabol.data.LiveStream
import app.parabol.data.VodStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Storing and reading back a large provider (plain JVM test). */
class ContentCacheTest {

    @Test fun largeListsAreStoredAndReadBackQuickly() {
        val dir = Files.createTempDirectory("content").toFile()
        val cache = ContentCache(dir)
        val cats = (1..400).map { Category("$it", "NL | Groep $it") }
        val vod = cats.associate { c ->
            c.categoryId to (1..400).map { i ->
                VodStream("Film ${c.categoryId}-$i", c.categoryId.toInt() * 1000 + i, "http://img/$i.jpg", c.categoryId, "mkv", "7.1")
            }
        }
        val live = cats.take(100).associate { c ->
            c.categoryId to (1..50).map { i -> LiveStream(i, "Zender $i", c.categoryId.toInt() * 100 + i, null, null, c.categoryId, 0, null) }
        }

        val t0 = System.nanoTime()
        cache.saveGroups("p1", cats, cats, emptyList())
        cache.saveContent("p1", "vod", vod)
        cache.saveContent("p1", "live", live)
        val saveMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val back = cache.load("p1")!!
        val loadMs = (System.nanoTime() - t1) / 1_000_000

        assertEquals(160_000, back.vod!!.values.sumOf { it.size })
        assertEquals(vod["7"], back.vod!!["7"])
        assertEquals(live["3"], back.live!!["3"])
        assertEquals(cats, back.liveCategories)
        assertTrue("saving 165,000 items took $saveMs ms", saveMs < 15_000)
        assertTrue("reading 165,000 items took $loadMs ms", loadMs < 15_000)

        cache.delete("p1")
        assertNull(cache.load("p1"))
    }
}
