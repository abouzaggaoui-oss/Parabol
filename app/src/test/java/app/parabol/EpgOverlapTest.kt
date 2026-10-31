package app.parabol

import app.parabol.data.EpgListing
import app.parabol.data.withoutOverlaps
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgOverlapTest {
    private fun p(id: String, start: Long, stop: Long) =
        EpgListing(id = id, startTimestamp = "$start", stopTimestamp = "$stop")

    @Test fun overlapsAndDuplicatesAreRemoved() {
        val out = listOf(
            p("C", 300, 400), p("A", 100, 200), p("A-dup", 100, 200),
            p("B", 150, 300), p("inside", 160, 180), p("empty", 500, 500)
        ).withoutOverlaps()
        assertEquals(listOf("A", "B", "C"), out.map { it.id })
        assertEquals(listOf(100L, 200L, 300L), out.map { it.startSeconds(0) })
    }
}
