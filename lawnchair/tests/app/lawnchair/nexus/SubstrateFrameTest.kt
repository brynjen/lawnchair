package app.lawnchair.nexus

import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.box.SubstrateService
import app.lawnchair.nexus.box.BoxOrbState
import app.lawnchair.nexus.box.parseSubstrateFrame
import app.lawnchair.nexus.box.parseSubstrateLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The substrate frame as the launcher reads it off the wire: Serverpod sends enums by name, the
 * server may add names later, and one bad frame must be dropped rather than end the stream.
 */
class SubstrateFrameTest {

    @Test
    fun `a frame with known names reads field for field`() {
        val frame = parseSubstrateFrame(
            """
            {"sequence":12,"timestamp":"2026-10-14T08:00:00.000Z","capacity":8,"dropped":0,"slots":[
              {"slot":0,"mode":"thinking","service":"vllmChat","label":"qwen","actor":"conv:7","tokPerSec":41.5},
              {"slot":3,"mode":"toolCall","service":"search","label":null,"actor":"subagent:42"}
            ]}
            """.trimIndent(),
        )!!
        assertEquals(12L, frame.sequence)
        assertEquals(8, frame.capacity)
        assertEquals(0, frame.dropped)
        assertEquals(2, frame.slots.size)
        val first = frame.slots[0]
        assertEquals(0, first.slot)
        assertEquals(SubstrateMode.Thinking, first.mode)
        assertEquals(SubstrateService.VllmChat, first.service)
        assertEquals("qwen", first.label)
        assertEquals("conv:7", first.actor)
        val second = frame.slots[1]
        assertEquals(3, second.slot)
        assertEquals(SubstrateMode.ToolCall, second.mode)
        assertEquals(SubstrateService.Search, second.service)
        assertNull(second.label)
        assertEquals("subagent:42", second.actor)
    }

    @Test
    fun `every mode and service name the server has today is known`() {
        val modes = listOf(
            "idle", "listening", "thinking", "processing", "speaking", "toolCall", "loading", "offline", "error",
            "unauthorized",
        )
        modes.forEach { assertNotEquals(it, SubstrateMode.Unknown, SubstrateMode.fromWire(it)) }
        assertEquals(modes, modes.map { SubstrateMode.fromWire(it).wire })
        val services = listOf(
            "none", "vllmChat", "vllmEmbed", "vllmImage", "vllmEdit", "removal", "whisper", "tts", "voiceprint",
            "search", "subagent",
        )
        services.forEach { assertNotEquals(it, SubstrateService.Unknown, SubstrateService.fromWire(it)) }
    }

    @Test
    fun `an unknown mode or service is unknown, never idle, and the frame survives`() {
        val frame = parseSubstrateFrame(
            """{"sequence":1,"capacity":8,"dropped":0,"slots":[{"slot":0,"mode":"paused","service":"quantum"}]}""",
        )!!
        val slot = frame.slots.single()
        assertEquals(SubstrateMode.Unknown, slot.mode)
        assertNotEquals(SubstrateMode.Idle, slot.mode)
        assertEquals(SubstrateService.Unknown, slot.service)
    }

    @Test
    fun `a missing or empty mode is unknown too`() {
        val frame = parseSubstrateFrame("""{"capacity":8,"slots":[{"slot":0},{"slot":1,"mode":""},{"slot":2,"mode":null}]}""")!!
        assertEquals(listOf(SubstrateMode.Unknown, SubstrateMode.Unknown, SubstrateMode.Unknown), frame.slots.map { it.mode })
    }

    @Test
    fun `empty slots are a frame with no slots`() {
        val frame = parseSubstrateFrame("""{"sequence":2,"capacity":8,"dropped":0,"slots":[]}""")!!
        assertEquals(emptyList<Any>(), frame.slots)
        assertEquals(8, frame.capacity)
    }

    @Test
    fun `dropped is carried`() {
        val frame = parseSubstrateFrame("""{"sequence":3,"capacity":8,"dropped":3,"slots":[{"slot":0,"mode":"thinking"}]}""")!!
        assertEquals(3, frame.dropped)
    }

    @Test
    fun `absent numbers are zero and capacity falls back to the slot count`() {
        val frame = parseSubstrateFrame("""{"slots":[{"slot":0,"mode":"idle"},{"slot":1,"mode":"idle"}]}""")!!
        assertEquals(0L, frame.sequence)
        assertEquals(0, frame.dropped)
        assertEquals(2, frame.capacity)
    }

    @Test
    fun `a slot without an index is skipped, not the frame`() {
        val frame = parseSubstrateFrame(
            """{"capacity":8,"slots":[{"mode":"thinking"},"junk",7,{"slot":2,"mode":"speaking"}]}""",
        )!!
        assertEquals(listOf(2), frame.slots.map { it.slot })
    }

    @Test
    fun `text that is not a frame is null`() {
        assertNull(parseSubstrateFrame(""))
        assertNull(parseSubstrateFrame("not json"))
        assertNull(parseSubstrateFrame("[]"))
        assertNull(parseSubstrateFrame("""{"capacity":8}"""))
        assertNull(parseSubstrateFrame("""{"slots":"idle"}"""))
    }
    @Test
    fun `a lanes packet is one digit per lane, an idle lane is no slot`() {
        val frame = parseSubstrateLanes("""{"lanes":"23526000","dropped":2}""")!!
        assertEquals(8, frame.capacity)
        assertEquals(2, frame.dropped)
        assertEquals(
            listOf(
                SubstrateMode.Thinking,
                SubstrateMode.Processing,
                SubstrateMode.ToolCall,
                SubstrateMode.Thinking,
                SubstrateMode.Loading,
            ),
            frame.slots.map { it.mode },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), frame.slots.map { it.slot })
    }

    @Test
    fun `a lane keeps its place when the lanes before it are quiet`() {
        val frame = parseSubstrateLanes("""{"lanes":"00020005","dropped":0}""")!!
        assertEquals(listOf(3, 7), frame.slots.map { it.slot })
        assertEquals(listOf(SubstrateMode.Thinking, SubstrateMode.ToolCall), frame.slots.map { it.mode })
    }

    @Test
    fun `a resting box has no slots but is known`() {
        val box = BoxOrbState.from(parseSubstrateLanes("""{"lanes":"00000000","dropped":0}""")!!)
        assertTrue(box.slots.isEmpty())
        assertTrue(box.isKnown)
        assertEquals(0, box.busy)
        assertEquals(SubstrateMode.Idle, box.mode)
    }

    @Test
    fun `an offline box is lane 0 offline, not busy`() {
        val box = BoxOrbState.from(parseSubstrateLanes("""{"lanes":"70000000","dropped":0}""")!!)
        assertEquals(SubstrateMode.Offline, box.mode)
        assertTrue(box.offline)
        assertEquals(0, box.busy)
    }

    @Test
    fun `a door shut in any lane outranks the rest`() {
        val box = BoxOrbState.from(parseSubstrateLanes("""{"lanes":"18900000","dropped":0}""")!!)
        assertTrue(box.unauthorized)
        assertTrue(box.trouble)
        assertEquals(3, box.busy)
    }

    @Test
    fun `the lane digits are the positions the server pins`() {
        // The server's substrate_lanes_test pins these same positions: idle 0 ... unauthorized 9.
        val modes = "0123456789".map { SubstrateMode.fromLane(it) }
        assertEquals(
            listOf(
                SubstrateMode.Idle, SubstrateMode.Listening, SubstrateMode.Thinking, SubstrateMode.Processing,
                SubstrateMode.Speaking, SubstrateMode.ToolCall, SubstrateMode.Loading, SubstrateMode.Offline,
                SubstrateMode.Error, SubstrateMode.Unauthorized,
            ),
            modes,
        )
    }

    @Test
    fun `a digit this build has no mode for is busy, never idle`() {
        assertEquals(SubstrateMode.Unknown, SubstrateMode.fromLane('a'))
        assertEquals(SubstrateMode.Unknown, SubstrateMode.fromLane('z'))
        assertEquals(SubstrateMode.Unknown, SubstrateMode.fromLane('-'))
        val frame = parseSubstrateLanes("""{"lanes":"a0000000","dropped":0}""")!!
        assertEquals(SubstrateMode.Unknown, frame.slots.single().mode)
    }

    @Test
    fun `a packet that is not lanes is dropped`() {
        assertNull(parseSubstrateLanes("""{"nothing":"like a packet"}"""))
        assertNull(parseSubstrateLanes("not json"))
        assertNull(parseSubstrateLanes("""{"lanes":7}"""))
    }
}
