package app.lawnchair.nexus

import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.box.SubstrateService
import app.lawnchair.nexus.box.parseSubstrateFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
}
