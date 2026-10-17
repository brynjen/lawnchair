package app.lawnchair.nexus.buds

import app.lawnchair.nexus.net.AudioSegment
import app.lawnchair.nexus.net.NexusTurnEvent

/** What a report of a finished background run does for the person. */
enum class ReportAction {
    /** Said aloud, in the earbuds if they are in, else on the phone. */
    Speak,

    /** Left to the session, which is open in front of them. */
    ShowInSession,

    /** A notification: it is only text and they are not in the app. */
    Notify,
}

/**
 * Earbuds in: the report is for them, whatever was asked. No earbuds: it is said if it was asked
 * aloud. A request typed in is answered in the session, and in a notification when they are not looking
 * at the app.
 */
fun reportAction(requestWasSpoken: Boolean, headsetConnected: Boolean, appInFront: Boolean): ReportAction = when {
    headsetConnected || requestWasSpoken -> ReportAction.Speak
    appInFront -> ReportAction.ShowInSession
    else -> ReportAction.Notify
}

/**
 * The lead's report of a finished background run, as it streams in on the conversation (a turn the
 * server starts itself, `srv-…`): it says first whether what it answers was asked aloud (`announce`),
 * then the words and their audio. [onEvent] says it aloud or leaves a notification, by [reportAction].
 */
class NexusReportAnnouncer(
    private val headsetConnected: () -> Boolean,
    private val appInFront: () -> Boolean,
    private val speak: (AudioSegment) -> Unit,
    private val endSpeech: () -> Unit,
    private val notify: (String) -> Unit,
) {
    private var turn: String? = null
    private var action = ReportAction.ShowInSession
    private val words = StringBuilder()

    fun onEvent(event: NexusTurnEvent) {
        val id = event.turnId ?: return
        if (!id.startsWith(SERVER_TURN)) return
        if (id != turn) {
            turn = id
            action = ReportAction.ShowInSession
            words.setLength(0)
        }
        when (event) {
            is NexusTurnEvent.Narrator -> if (event.narratorKind == "announce") {
                action = reportAction(
                    requestWasSpoken = event.text == "voice",
                    headsetConnected = headsetConnected(),
                    appInFront = appInFront(),
                )
            }

            is NexusTurnEvent.TextDelta -> words.append(event.text)

            is NexusTurnEvent.Audio -> if (action == ReportAction.Speak) {
                speak(AudioSegment(event.opusBytes, event.segmentIndex, event.durationMs, event.isFinal))
            }

            is NexusTurnEvent.Completed -> {
                when (action) {
                    ReportAction.Speak -> endSpeech()
                    ReportAction.Notify -> words.toString().trim().takeIf { it.isNotEmpty() }?.let(notify)
                    ReportAction.ShowInSession -> Unit
                }
                turn = null
            }

            is NexusTurnEvent.Failed -> turn = null

            else -> Unit
        }
    }

    private companion object {
        /** Prefix of the id of a turn the server starts itself (`ConversationRuntimeService.serverTurnPrefix`). */
        const val SERVER_TURN = "srv-"
    }
}
