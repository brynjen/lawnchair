package app.lawnchair.nexus

import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.net.ChatSubject
import app.lawnchair.nexus.net.NexusTransport
import app.lawnchair.nexus.net.NexusTurnEvent
import app.lawnchair.nexus.net.TranscriptionEvent
import kotlinx.coroutines.flow.Flow

/** A [NexusTransport] for tests that need one or two calls: everything not overridden fails loudly. */
open class StubNexusTransport : NexusTransport {
    override suspend fun createConversation(base: String, token: String, subject: ChatSubject?): Int = unused()

    override suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
        voice: Boolean?,
    ) = unused()

    override suspend fun answerUserPrompt(
        base: String,
        token: String,
        conversationId: Int,
        promptId: String,
        answer: String,
    ) = unused()

    override suspend fun cancelTurn(
        base: String,
        token: String,
        conversationId: Int,
        heardThroughSegmentIndex: Int?,
    ) = unused()

    override fun streamTurn(
        base: String,
        token: String,
        conversationId: Int,
        onReady: () -> Unit,
    ): Flow<NexusTurnEvent> = unused()

    override fun streamTranscription(
        base: String,
        token: String,
        recordingId: String,
        sessionId: String,
        pcm16Chunks: Flow<ByteArray>,
        language: String?,
    ): Flow<TranscriptionEvent> = unused()

    override fun watchBox(base: String, token: String): Flow<SubstrateFrame> = unused()

    override suspend fun newsFeed(base: String, token: String, limit: Int, cursor: String?, section: String?, query: String?): NewsFeed = unused()

    override suspend fun newsRefresh(base: String, token: String): NewsFeed = unused()

    override suspend fun newsImage(base: String, token: String, path: String): ByteArray = unused()

    override suspend fun newsFavorite(base: String, token: String, id: Long, favorite: Boolean): Boolean = unused()

    private fun unused(): Nothing = error("not used by this test")
}
