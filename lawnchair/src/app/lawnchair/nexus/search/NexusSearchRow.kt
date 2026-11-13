package app.lawnchair.nexus.search

import android.content.Context
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.NexusStatus
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusTransport
import com.android.launcher3.allapps.BaseAllAppsAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Nexus row above the phone's apps in the app drawer's search (docs/features/launcher-search.md):
 * an Ask Nexus card for [query], then what the person's notes, chats and the News have of it. While
 * [searching] the row shows ghost cards after the Ask card; once the server has answered (or could not)
 * it shows [hits], which may be none. The row is there from the first letter typed to the last, so the
 * apps under it never move when the server's answer comes.
 */
data class NexusSearchRow(
    val query: String,
    val hits: NexusSearchHits,
    val searching: Boolean,
)

/** The drawer's search list item that is the [NexusSearchRow] (LawnchairSearchAdapterProvider draws it). */
data class NexusRowAdapterItem(val row: NexusSearchRow) : BaseAllAppsAdapter.AdapterItem(VIEW_TYPE) {
    override fun isSameAs(other: BaseAllAppsAdapter.AdapterItem): Boolean = other is NexusRowAdapterItem

    override fun isContentSame(other: BaseAllAppsAdapter.AdapterItem): Boolean =
        other is NexusRowAdapterItem && other.row == row

    companion object {
        /** Beside LawnchairSearchAdapterProvider's own view types, which go up to `1 shl 22`. */
        const val VIEW_TYPE = 1 shl 23
    }
}

/** The fewest letters (spaces aside) the server is asked about; the Ask card needs only one. */
const val NEXUS_SEARCH_MIN_CHARS = 2

/** How long typing must pause before the server is asked: each new letter cancels the question before. */
const val NEXUS_SEARCH_DEBOUNCE_MS = 200L

/** How long the server gets to answer before the row settles on what it has: nothing. */
const val NEXUS_SEARCH_TIMEOUT_MS = 4_000L

/** How many of each (notes, chats, News) the row asks for. */
const val NEXUS_SEARCH_LIMIT = 4

/**
 * The Nexus row for [query] as it develops: at once the Ask card (with ghosts when the server will be
 * asked), then, after [NEXUS_SEARCH_DEBOUNCE_MS], the server's hits. Null (no row) for an empty query or
 * when the Nexus app is not signed in and set up, since there is no one to ask then. A failed or slow
 * search settles on no hits: the row keeps its Ask card and its height.
 */
fun nexusSearchRow(context: Context, query: String): Flow<NexusSearchRow?> =
    nexusSearchRow(query, { NexusConfig.read(context) }, sharedTransport)

/** [nexusSearchRow] with what it reads ([status], the app's sign-in) and asks ([transport]) handed in. */
internal fun nexusSearchRow(
    query: String,
    status: () -> NexusStatus,
    transport: NexusTransport,
): Flow<NexusSearchRow?> = flow {
    val words = query.trim()
    val config = if (words.isEmpty()) null else status()
    if (config == null || !config.enabled) {
        emit(null)
        return@flow
    }
    val searching = words.count { !it.isWhitespace() } >= NEXUS_SEARCH_MIN_CHARS
    emit(NexusSearchRow(words, NexusSearchHits.EMPTY, searching))
    if (!searching) return@flow
    delay(NEXUS_SEARCH_DEBOUNCE_MS)
    val hits = withTimeoutOrNull(NEXUS_SEARCH_TIMEOUT_MS) {
        try {
            transport.quickSearch(config.serverUrl, config.authKey, words, NEXUS_SEARCH_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    emit(NexusSearchRow(words, hits ?: NexusSearchHits.EMPTY, searching = false))
}.flowOn(Dispatchers.IO)

/** One client for every keystroke: OkHttp's pool keeps the connection to the box open between them. */
private val sharedTransport: NexusTransport by lazy { NexusClient() }
