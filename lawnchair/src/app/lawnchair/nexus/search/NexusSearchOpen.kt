package app.lawnchair.nexus.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.chat.NexusChatOverlay
import app.lawnchair.nexus.news.NewsItem
import com.android.launcher3.LauncherState

/**
 * Where a card of the Nexus row takes the person: Ask Nexus to the chat overlay with the words as the
 * first message, a chat to that chat in the Nexus app, a note to the notes page (nexus-notes'
 * `NoteActivity`, a page of its own that is no route of the app), an article to the News page with it
 * open.
 */
class NexusSearchOpener(private val launcher: LawnchairLauncher) : NexusSearchActions {
    override fun ask(query: String) {
        launcher.hideKeyboard()
        launcher.stateManager.goToState(LauncherState.NORMAL, false)
        NexusChatOverlay.openText(launcher, firstMessage = query)
    }

    override fun openNote(hit: NexusNoteHit) {
        val intent = Intent(ACTION_OPEN_NOTE)
            .setPackage(launcher.packageName)
            .putExtra(EXTRA_NOTE_PATH, hit.path)
        try {
            launcher.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // A build whose notes_widgets has no note page yet.
            Toast.makeText(launcher, "This build can't open notes yet", Toast.LENGTH_SHORT).show()
        }
    }

    override fun openChat(hit: NexusChatHit) {
        launcher.startActivity(NexusAppActivity.chatIntent(launcher, hit.conversationId, hit.title, hit.agentId))
    }

    override fun openNews(article: NewsItem) {
        launcher.hideKeyboard()
        launcher.openNewsArticle(article)
    }

    companion object {
        /** nexus-notes' note page (notes_widgets 0.2): its intent and the vault-relative path it opens. */
        const val ACTION_OPEN_NOTE = "no.nordli.nexus.notes.action.OPEN_NOTE"
        const val EXTRA_NOTE_PATH = "no.nordli.nexus.notes.extra.PATH"
    }
}
