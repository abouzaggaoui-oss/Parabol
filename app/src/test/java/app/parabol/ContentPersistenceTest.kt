package app.parabol

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.parabol.data.DemoXtreamApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The whole app flow with the demo account: first start fetches the lists and stores them;
 * a new start (new ViewModel, as after closing the app) must show them without fetching again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentPersistenceTest {

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test fun storedContentIsUsedOnNextStart() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.filesDir, "content")

        // First start: log in with the demo account.
        val first = MainViewModel(app)
        first.connect("http://demo", "demo", "demo", "Demo")
        waitFor("first load") { first.liveStreams.isNotEmpty() && first.vodStreams.isNotEmpty() && !first.loading }
        assertFalse("loading must end", first.loading)
        waitFor("files saved: ${dir.list()?.toList()}") {
            listOf("live", "vod", "series", "groups").all { part -> dir.listFiles()?.any { it.name.endsWith("-$part.json") } == true }
        }
        val requestsAfterFirst = DemoXtreamApi.listRequests
        assertTrue("first start fetched lists", requestsAfterFirst > 0)

        // Next start: a new ViewModel opens the account from the account screen.
        val second = MainViewModel(app)
        val id = second.profiles.first().id
        second.switchProfile(id)
        waitFor("second load") { second.liveStreams.isNotEmpty() && !second.loading }
        // Let the background refresh run to the end.
        repeat(40) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25) }
        assertEquals(4, second.liveStreams.size)
        assertEquals(4, second.vodStreams.size)
        assertEquals("no lists fetched again on the next start", requestsAfterFirst, DemoXtreamApi.listRequests)
        assertFalse(second.loading)
    }
}
