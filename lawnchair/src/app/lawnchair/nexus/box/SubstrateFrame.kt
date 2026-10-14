package app.lawnchair.nexus.box

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a substrate slot is doing, by the server's `SubstrateMode` names (nexus_server
 * `substrate_mode.spy.yaml`; Serverpod sends enums by name).
 *
 * [priority] is the salience ladder, a deliberate mirror of `_modePriority` in the server's
 * `substrate_slots.dart` and of the phone's `SubstrateRepositoryImpl._priority`: it picks which of
 * the eight slots a one-orb view would draw. If the server's order changes, change this with it.
 *
 * The server may add names later. A name this build does not know is [Unknown], never [Idle]: the
 * box says it is doing something, so it is busy, and it outranks the resting modes.
 */
enum class SubstrateMode(val wire: String, val priority: Int) {
    Idle("idle", 0),
    Listening("listening", 60),
    Thinking("thinking", 30),
    Processing("processing", 20),
    Speaking("speaking", 50),
    ToolCall("toolCall", 40),
    Loading("loading", 10),
    Offline("offline", 5),
    Error("error", 70),

    /** The door is shut: a caller's token was refused. Outranks [Error]. */
    Unauthorized("unauthorized", 80),
    Unknown("", 20),
    ;

    companion object {
        fun fromWire(name: String?): SubstrateMode =
            entries.firstOrNull { it != Unknown && it.wire == name } ?: Unknown
    }
}

/** Which backend service occupies a slot, by the server's `SubstrateService` names; [Unknown] for a new one. */
enum class SubstrateService(val wire: String) {
    None("none"),
    VllmChat("vllmChat"),
    VllmEmbed("vllmEmbed"),
    VllmImage("vllmImage"),
    VllmEdit("vllmEdit"),
    Removal("removal"),
    Whisper("whisper"),
    Tts("tts"),
    Voiceprint("voiceprint"),
    Search("search"),
    Subagent("subagent"),
    Unknown(""),
    ;

    companion object {
        fun fromWire(name: String?): SubstrateService =
            entries.firstOrNull { it != Unknown && it.wire == name } ?: Unknown
    }
}

/**
 * One of the box's process slots. [actor] is the raw key of whoever owns it (`conv:<id>`,
 * `subagent:<id>`, `coder:<sessionId>`, `relay:...`, `memory:...`), null for unattributed work; the
 * orb does not print it. The slot's `tokPerSec` is left out: nothing here draws it.
 */
data class SubstrateSlot(
    val slot: Int,
    val mode: SubstrateMode,
    val service: SubstrateService,
    val label: String?,
    val actor: String?,
)

/** The whole table at one instant (`SubstrateFrame`): a snapshot, never a delta. */
data class SubstrateFrame(
    val sequence: Long,
    val slots: List<SubstrateSlot>,
    val capacity: Int,
    val dropped: Int,
)

/**
 * Reads a frame's JSON (the `data.o.data` of an `msm` message) defensively: unknown enum names become
 * `Unknown`, a slot with no index is skipped, absent numbers are zero. Null when [text] is not an
 * object with a `slots` array, so one bad frame is dropped rather than ending the stream.
 */
fun parseSubstrateFrame(text: String): SubstrateFrame? {
    val frame = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val slots = (frame["slots"] as? JsonArray ?: return null).mapNotNull { parseSlot(it) }
    return SubstrateFrame(
        sequence = frame.primitive("sequence")?.longOrNull ?: 0L,
        slots = slots,
        capacity = frame.primitive("capacity")?.intOrNull ?: slots.size,
        dropped = frame.primitive("dropped")?.intOrNull ?: 0,
    )
}

private fun parseSlot(element: JsonElement): SubstrateSlot? {
    val o = element as? JsonObject ?: return null
    val index = o.primitive("slot")?.intOrNull ?: return null
    return SubstrateSlot(
        slot = index,
        mode = SubstrateMode.fromWire(o.primitive("mode")?.contentOrNull),
        service = SubstrateService.fromWire(o.primitive("service")?.contentOrNull),
        label = o.primitive("label")?.contentOrNull,
        actor = o.primitive("actor")?.contentOrNull,
    )
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
