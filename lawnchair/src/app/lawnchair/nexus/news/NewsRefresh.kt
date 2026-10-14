package app.lawnchair.nexus.news

/** What a pull-to-refresh ended in, so the page can say so. */
sealed interface RefreshResult {
    /** The server's feed, [count] of whose cards were not on the page before. */
    data class New(val feed: NewsFeed, val count: Int) : RefreshResult

    /** The server's feed, with nothing the page did not already show. */
    data class UpToDate(val feed: NewsFeed) : RefreshResult

    /** Neither the sweep nor the load got an answer: the page keeps what it had. */
    data object Unreachable : RefreshResult
}

/**
 * Compares the [fresh] feed (null when none could be had) with what was [shown]: a card whose id was
 * not on the page is new, wherever it sits in the feed.
 */
fun refreshResult(shown: NewsFeed?, fresh: NewsFeed?): RefreshResult {
    if (fresh == null) return RefreshResult.Unreachable
    val known = shown?.items.orEmpty().mapTo(HashSet()) { it.id }
    val added = fresh.items.count { it.id !in known }
    return if (added > 0) RefreshResult.New(fresh, added) else RefreshResult.UpToDate(fresh)
}

/** The short line the page shows for a refresh. */
fun RefreshResult.message(): String = when (this) {
    is RefreshResult.New -> if (count == 1) "1 new article" else "$count new articles"
    is RefreshResult.UpToDate -> "Up to date"
    RefreshResult.Unreachable -> "Couldn't reach Nexus. Showing saved articles."
}
