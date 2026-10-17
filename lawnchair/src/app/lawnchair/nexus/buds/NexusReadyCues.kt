package app.lawnchair.nexus.buds

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The spoken "I'm listening" that opens a turn started from the earbuds, kept ready: the launcher's
 * counterpart of the app's `ReadyCues`. The server speaks the line in the person's voice; one is fetched
 * ahead and the next as soon as it is used, a line that is not there in time is not waited for (the beep
 * plays instead), and a server that has no such line is asked once and then left alone for a while.
 */
class NexusReadyCues(
    private val fetch: suspend (language: String?) -> ByteArray?,
    private val language: () -> String?,
    private val retryAfterMs: Long = 5 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var ready: ByteArray? = null
    private var loading: Deferred<Unit>? = null
    private var failedAt: Long? = null

    /** Starts fetching the next line unless one is ready or on its way. */
    @Synchronized
    fun prefetch(): Deferred<Unit> = loading ?: scope.async {
        val failed = failedAt
        if (failed != null && now() - failed < retryAfterMs) return@async
        try {
            ready = fetch(language())
            failedAt = null
        } catch (_: Exception) {
            failedAt = now()
        }
    }.also { job ->
        loading = job
        job.invokeOnCompletion { synchronized(this) { if (loading === job) loading = null } }
    }

    /** The line to play now, or null: one that is ready, or arrives within [waitMs]. Starts the next one behind it. */
    suspend fun take(waitMs: Long = 1_500): ByteArray? {
        if (ready == null) withTimeoutOrNull(waitMs) { prefetch().await() }
        val audio = ready
        ready = null
        if (audio != null) prefetch()
        return audio
    }
}
