package app.lawnchair.nexus.news

/**
 * The page's sections, as the server cuts them (`feed`'s `section`): [Now] is the news and the blogs,
 * [Ai] whatever is tagged `ai` (blogs and papers), [Papers] the research papers the box reads.
 */
enum class NewsSection(val wire: String, val label: String) {
    Now("now", "Now"),
    Ai("ai", "AI"),
    Papers("papers", "Papers"),
}

/** Whether [item] is in what a search for [query] means: its title, summary, key points, text, source or authors. */
fun NewsItem.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, true) ||
        synopsis.contains(q, true) ||
        keyPoints.any { it.contains(q, true) } ||
        body?.contains(q, true) == true ||
        sourceName.contains(q, true) ||
        authors.any { it.contains(q, true) }
}

/** [more] (the page after this one) added to this feed: its cards after ours, none twice, and its cursor. */
fun NewsFeed.plus(more: NewsFeed): NewsFeed {
    val known = items.mapTo(HashSet()) { it.id }
    return copy(items = items + more.items.filter { it.id !in known }, nextCursor = more.nextCursor)
}

/** A paper's authors for a card: the first three, "et al." after them. */
fun NewsItem.authorLine(): String = when {
    authors.isEmpty() -> ""
    authors.size <= 3 -> authors.joinToString(", ")
    else -> authors.take(3).joinToString(", ") + " et al."
}

/** "1.2k" for 1,200 stars; the number itself under a thousand. */
fun compactCount(n: Int): String = when {
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000.0)
    else -> n.toString()
}
