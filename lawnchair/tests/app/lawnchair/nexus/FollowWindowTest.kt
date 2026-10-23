package app.lawnchair.nexus

import app.lawnchair.nexus.buds.FollowWindow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long the report watcher keeps its connection open: from the last time the person was using Nexus. */
class FollowWindowTest {

    private var clock = 0L
    private val waits = ArrayList<Long>()

    private fun window(touchDuring: (FollowWindow) -> Unit = {}): FollowWindow {
        lateinit var window: FollowWindow
        window = FollowWindow(
            windowMs = 1_000,
            now = { clock },
            wait = {
                waits += it
                clock += it
                touchDuring(window)
            },
        )
        return window
    }

    /** Runs the window over a job that holds until it is cancelled; true when the job was cancelled. */
    private fun cancelled(window: FollowWindow): Boolean = runBlocking {
        var started = false
        var cancelled = false
        window.run {
            started = true
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertTrue(started)
        cancelled
    }

    @Test
    fun `the work is cancelled when the window is over`() {
        val window = window()
        window.touch()
        assertTrue(cancelled(window))
        assertEquals(listOf(1_000L), waits)
    }

    @Test
    fun `a touch while it runs extends the window`() {
        var touches = 0
        val window = window { w ->
            // The person is seen again just as the first second ends, once.
            if (touches++ == 0) w.touch()
        }
        window.touch()
        assertTrue(cancelled(window))
        // Waited the first second out, found the window renewed from there, waited that out too.
        assertEquals(listOf(1_000L, 1_000L), waits)
    }

    @Test
    fun `a window never touched is over at once`() {
        val window = window()
        assertTrue(cancelled(window))
        assertEquals(emptyList<Long>(), waits)
    }
}
