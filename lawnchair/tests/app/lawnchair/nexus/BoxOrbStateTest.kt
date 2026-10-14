package app.lawnchair.nexus

import app.lawnchair.nexus.box.BoxOrbState
import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.box.SubstrateService
import app.lawnchair.nexus.box.SubstrateSlot
import app.lawnchair.nexus.box.boxOrbStates
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduction of a frame to what the orb needs (a mirror of the phone's `SubstrateRepositoryImpl`
 * `_reduce`): the salience ladder, the counts, shut versus down versus unknown, and the reconnect loop.
 */
class BoxOrbStateTest {

    private fun slot(i: Int, mode: SubstrateMode, actor: String? = null) =
        SubstrateSlot(i, mode, SubstrateService.None, label = null, actor = actor)

    private fun frame(vararg slots: SubstrateSlot, capacity: Int = 8, dropped: Int = 0) =
        SubstrateFrame(sequence = 1, slots = slots.toList(), capacity = capacity, dropped = dropped)

    private fun reduce(vararg slots: SubstrateSlot, dropped: Int = 0) =
        BoxOrbState.from(frame(*slots, dropped = dropped))

    @Test
    fun `the most salient slot follows the server's ladder`() {
        // Highest first: unauthorized, error, listening, speaking, toolCall, thinking, processing, loading, offline, idle.
        val ladder = listOf(
            SubstrateMode.Unauthorized, SubstrateMode.Error, SubstrateMode.Listening, SubstrateMode.Speaking,
            SubstrateMode.ToolCall, SubstrateMode.Thinking, SubstrateMode.Processing, SubstrateMode.Loading,
            SubstrateMode.Offline, SubstrateMode.Idle,
        )
        for (i in ladder.indices) {
            // Every rung beats everything below it, wherever it sits in the table.
            val slots = ladder.drop(i).reversed().mapIndexed { n, m -> slot(n, m) }
            assertEquals(ladder[i], reduce(*slots.toTypedArray()).mode)
        }
    }

    @Test
    fun `unauthorized outranks error`() {
        val state = reduce(slot(0, SubstrateMode.Error), slot(1, SubstrateMode.Unauthorized), slot(2, SubstrateMode.Listening))
        assertEquals(SubstrateMode.Unauthorized, state.mode)
        assertTrue(state.unauthorized)
        assertTrue(state.trouble)
    }

    @Test
    fun `error outranks listening and counts as trouble`() {
        val state = reduce(slot(0, SubstrateMode.Listening), slot(1, SubstrateMode.Error))
        assertEquals(SubstrateMode.Error, state.mode)
        assertFalse(state.unauthorized)
        assertTrue(state.trouble)
    }

    @Test
    fun `equals keep the first slot`() {
        val state = reduce(slot(0, SubstrateMode.Thinking, "conv:1"), slot(1, SubstrateMode.Thinking, "subagent:2"))
        assertEquals("conv:1", state.salient?.actor)
    }

    @Test
    fun `an offline box is offline, not unauthorized and not idle`() {
        val state = reduce(slot(0, SubstrateMode.Offline))
        assertTrue(state.offline)
        assertFalse(state.unauthorized)
        assertFalse(state.trouble)
        assertEquals(0, state.busy)
        assertTrue(state.isKnown)
    }

    @Test
    fun `a resting box is known and quiet`() {
        val state = reduce(slot(0, SubstrateMode.Idle))
        assertEquals(SubstrateMode.Idle, state.mode)
        assertEquals(0, state.busy)
        assertFalse(state.offline)
        assertTrue(state.isKnown)
        assertEquals(8, state.capacity)
    }

    @Test
    fun `busy counts everything but idle and offline`() {
        val state = reduce(
            slot(0, SubstrateMode.Thinking),
            slot(1, SubstrateMode.Idle),
            slot(2, SubstrateMode.Offline),
            slot(3, SubstrateMode.Error),
            slot(4, SubstrateMode.Unauthorized),
            slot(5, SubstrateMode.Unknown),
        )
        assertEquals(4, state.busy)
    }

    @Test
    fun `an unknown mode is busy and outranks the resting modes`() {
        val state = reduce(slot(0, SubstrateMode.Idle), slot(1, SubstrateMode.Unknown), slot(2, SubstrateMode.Offline))
        assertEquals(SubstrateMode.Unknown, state.mode)
        assertEquals(1, state.busy)
        assertFalse(state.offline)
    }

    @Test
    fun `dropped means busy is an under-count and is carried`() {
        val state = reduce(slot(0, SubstrateMode.Thinking), dropped = 2)
        assertEquals(2, state.dropped)
        assertEquals(1, state.busy)
    }

    @Test
    fun `empty slots are a known idle box`() {
        val state = reduce()
        assertTrue(state.isKnown)
        assertNull(state.salient)
        assertEquals(SubstrateMode.Idle, state.mode)
        assertEquals(0, state.busy)
        assertFalse(state.offline)
    }

    @Test
    fun `before any frame is unknown, not idle and not shut`() {
        val state = BoxOrbState.UNKNOWN
        assertFalse(state.isKnown)
        assertFalse(state.offline)
        assertFalse(state.unauthorized)
        assertFalse(state.trouble)
        assertEquals(0, state.capacity)
    }

    @Test
    fun `only slots the orb has lanes for are drawn, one per lane`() {
        val state = reduce(
            slot(0, SubstrateMode.Thinking),
            slot(0, SubstrateMode.Speaking), // a repeated lane would be refused by the renderer
            slot(7, SubstrateMode.ToolCall),
            slot(8, SubstrateMode.Thinking), // no lane
            slot(-1, SubstrateMode.Thinking), // no lane
        )
        assertEquals(listOf(0, 7), state.drawable.map { it.slot })
        assertEquals(SubstrateMode.Thinking, state.drawable.first().mode)
    }

    // region the reconnect loop

    private val frameA = frame(slot(0, SubstrateMode.Thinking))
    private val frameB = frame(slot(0, SubstrateMode.Speaking))

    /** Runs the loop over [sources] (one per connection, then a stream that stays up), taking [count] states. */
    private fun collectStates(count: Int, vararg sources: Flow<SubstrateFrame>): Pair<List<BoxOrbState>, List<Long>> = runBlocking {
        val queue = ArrayDeque(sources.toList())
        val waits = ArrayList<Long>()
        val states = boxOrbStates(minRetryMs = 2_000, maxRetryMs = 30_000, wait = { waits += it }) {
            queue.removeFirstOrNull() ?: flow { awaitCancellation() }
        }.take(count).toList()
        states to waits
    }

    @Test
    fun `each frame is a state, and a lost stream reads as unknown before it reconnects`() {
        val (states, _) = collectStates(
            4,
            flowOf(frameA), // a clean close
            flow { throw IOException("socket failure") }, // a failure before any frame
            flow { emit(frameB); awaitCancellation() }, // reconnected, and stays up
        )
        assertEquals(
            listOf(BoxOrbState.from(frameA), BoxOrbState.UNKNOWN, BoxOrbState.UNKNOWN, BoxOrbState.from(frameB)),
            states,
        )
    }

    @Test
    fun `a stream that ends at once, as after a refused session key, reads unknown and is retried`() {
        val (states, waits) = collectStates(3, flowOf(), flowOf(), flowOf())
        assertEquals(listOf(BoxOrbState.UNKNOWN, BoxOrbState.UNKNOWN, BoxOrbState.UNKNOWN), states)
        // The wait follows each unknown; the third is taken before its own wait begins.
        assertEquals(listOf(2_000L, 4_000L), waits)
    }

    @Test
    fun `the wait doubles from two seconds to thirty and stays there`() {
        val (_, waits) = collectStates(8, flowOf(), flowOf(), flowOf(), flowOf(), flowOf(), flowOf(), flowOf(), flowOf())
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test
    fun `a frame resets the wait to the minimum`() {
        val (states, waits) = collectStates(6, flowOf(), flowOf(), flowOf(), flowOf(frameA), flowOf(), flowOf())
        // Three fruitless attempts back off 2, 4, 8 s; the fourth delivers a frame, so its close waits 2 s again.
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 2_000L), waits)
        assertEquals(BoxOrbState.from(frameA), states[3])
    }

    // endregion
}
