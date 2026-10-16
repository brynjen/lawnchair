package app.lawnchair.nexus.news

/** A question under an article's Discuss button: the [label] on the chip, the [question] it asks. */
data class QuickQuestion(val label: String, val question: String)

/**
 * The questions offered for [article], each a tap from being asked in the Discuss chat (which has the
 * article's text, so none of them quotes it).
 */
fun quickQuestions(article: NewsItem): List<QuickQuestion> =
    if (article.kind == NewsKind.Paper) {
        listOf(
            QuickQuestion("Main claim", "What is the main claim of this paper, and what evidence does it give for it?"),
            QuickQuestion("Is there code?", "Is there code for this paper, and what would I need to run it?"),
            QuickQuestion("How to test it", "How could I test the paper's main claim myself, on a small scale?"),
        )
    } else {
        listOf(
            QuickQuestion("In three lines", "Tell me what this is about in three lines."),
            QuickQuestion("Who is affected?", "Who is affected by this, and how?"),
            QuickQuestion("Background", "What background do I need to understand this?"),
        )
    }
