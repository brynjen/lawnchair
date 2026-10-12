package app.lawnchair.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login gate: the launcher does nothing for a phone whose Nexus app is not installed, has no
 * server, has nobody signed in or has not finished onboarding for that person, and says which.
 */
class NexusStatusTest {

    private fun status(
        installed: Boolean = true,
        configured: Boolean = true,
        serverUrl: String = "https://nexus.example",
        signedIn: Boolean = true,
        onboarded: Boolean = true,
        authKey: String = "key",
    ) = NexusStatus(
        installed = installed,
        configured = configured,
        serverUrl = serverUrl,
        presenceOnline = true,
        presenceEpochMs = 0L,
        whisperReady = true,
        ttsReady = true,
        llmReady = true,
        signedIn = signedIn,
        onboarded = onboarded,
        authKey = authKey,
    )

    @Test
    fun `a signed-in, onboarded person on a configured app is let in`() {
        val s = status()
        assertEquals(NexusLock.None, s.lock)
        assertTrue(s.enabled)
    }

    @Test
    fun `the app not being installed comes first`() {
        val s = status(installed = false, configured = false, signedIn = false)
        assertEquals(NexusLock.NotInstalled, s.lock)
        assertFalse(s.enabled)
    }

    @Test
    fun `no server is the next missing step`() {
        assertEquals(NexusLock.NeedsServer, status(configured = false).lock)
        assertEquals(NexusLock.NeedsServer, status(serverUrl = "").lock)
    }

    @Test
    fun `a server alone does not enable anything`() {
        // The old gate: installed, configured and a URL. Nobody is signed in.
        val s = status(signedIn = false, authKey = "", onboarded = false)
        assertEquals(NexusLock.NeedsSignIn, s.lock)
        assertFalse(s.enabled)
    }

    @Test
    fun `a signed-in flag without the key is not signed in`() {
        val s = status(signedIn = true, authKey = "")
        assertEquals(NexusLock.NeedsSignIn, s.lock)
        assertFalse(s.enabled)
    }

    @Test
    fun `signed in but not onboarded asks to finish setting up`() {
        val s = status(onboarded = false)
        assertEquals(NexusLock.NeedsOnboarding, s.lock)
        assertFalse(s.enabled)
    }

    @Test
    fun `a status that could not be read is locked`() {
        val s = NexusStatus.NOT_INSTALLED
        assertEquals(NexusLock.NotInstalled, s.lock)
        assertFalse(s.enabled)
    }
}
