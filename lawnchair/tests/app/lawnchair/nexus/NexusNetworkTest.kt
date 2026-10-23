package app.lawnchair.nexus

import app.lawnchair.nexus.net.networkUp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The network flow behind the News header's dot, with the system left out. */
class NexusNetworkTest {

    @Test
    fun `says the answer now, then each change once`() {
        var stopped = false
        val seen = runBlocking {
            networkUp(
                now = { true },
                watch = { changed ->
                    changed(true) // the system repeats what it said
                    changed(false)
                    changed(false)
                    changed(true)
                    ({ stopped = true })
                },
            ).take(3).toList()
        }
        assertEquals(listOf(true, false, true), seen)
        assertTrue("the callback is unregistered when the collection ends", stopped)
    }

    @Test
    fun `with no network at the start the first answer is offline`() {
        val first = runBlocking { networkUp(now = { false }, watch = { { } }).first() }
        assertFalse(first)
    }

    @Test
    fun `nothing is registered until it is collected`() {
        var registered = false
        networkUp(now = { true }, watch = { registered = true; { } })
        assertFalse(registered)
    }
}
