package app.lawnchair.nexus.power

import org.json.JSONObject

/**
 * What the phone says of itself at one moment, for a power recording: the two ends of a stretch
 * are one of these each. [rails] are the phone's energy meters by name, in µWs counted since boot
 * (empty on a phone that has none); [chargeUah] is the fuel gauge's counter.
 */
data class PowerSnapshot(
    val atMs: Long,
    val wifi: Boolean,
    val charging: Boolean,
    val level: Int,
    val chargeUah: Long?,
    val rails: Map<String, Long>,
    val pushOpens: Int,
    val pushFailures: Int,
    val pushMessages: Int,
    val pushConnectedSeconds: Long,
) {
    fun toJson(): String = JSONObject()
        .put("atMs", atMs)
        .put("wifi", wifi)
        .put("charging", charging)
        .put("level", level)
        .put("chargeUah", chargeUah ?: JSONObject.NULL)
        .put("rails", JSONObject(rails))
        .put("pushOpens", pushOpens)
        .put("pushFailures", pushFailures)
        .put("pushMessages", pushMessages)
        .put("pushConnectedSeconds", pushConnectedSeconds)
        .toString()

    companion object {
        /** The snapshot [text] holds, or null when it is not one (a start that was never saved). */
        fun parse(text: String?): PowerSnapshot? {
            val json = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
            if (!json.has("atMs")) return null
            val rails = json.optJSONObject("rails") ?: JSONObject()
            return PowerSnapshot(
                atMs = json.getLong("atMs"),
                wifi = json.optBoolean("wifi"),
                charging = json.optBoolean("charging"),
                level = json.optInt("level"),
                chargeUah = if (json.isNull("chargeUah")) null else json.optLong("chargeUah"),
                rails = rails.keys().asSequence().associateWith { rails.getLong(it) },
                pushOpens = json.optInt("pushOpens"),
                pushFailures = json.optInt("pushFailures"),
                pushMessages = json.optInt("pushMessages"),
                pushConnectedSeconds = json.optLong("pushConnectedSeconds"),
            )
        }
    }
}

/**
 * The recording between [start] and [end] as the server's `power.record` takes it. A meter counts
 * as the difference of its two readings; one that is missing at either end, or went backwards (the
 * phone restarted), is left out. The push connection's counts are differences too, and say
 * `restarted` when the launcher's process came up anew in between.
 */
fun powerRecordingJson(
    start: PowerSnapshot,
    end: PowerSnapshot,
    device: String,
    pushEnabled: Boolean,
    keepaliveSeconds: Int?,
    iso: (Long) -> String,
): JSONObject {
    val rails = JSONObject()
    for ((name, before) in start.rails) {
        val after = end.rails[name] ?: continue
        if (after >= before) rails.put(name, after - before)
    }
    val restarted = end.pushOpens < start.pushOpens || end.pushConnectedSeconds < start.pushConnectedSeconds
    val push = JSONObject()
        .put("opens", if (restarted) end.pushOpens else end.pushOpens - start.pushOpens)
        .put("failures", if (restarted) end.pushFailures else end.pushFailures - start.pushFailures)
        .put("messages", if (restarted) end.pushMessages else end.pushMessages - start.pushMessages)
        .put(
            "connectedSeconds",
            if (restarted) end.pushConnectedSeconds else end.pushConnectedSeconds - start.pushConnectedSeconds,
        )
    if (restarted) push.put("restarted", true)
    return JSONObject()
        .put("device", device)
        .put("startedAt", iso(start.atMs))
        .put("endedAt", iso(end.atMs))
        .put("pushEnabled", pushEnabled)
        .put("keepaliveSeconds", keepaliveSeconds ?: JSONObject.NULL)
        .put("wifiAtStart", start.wifi)
        .put("wifiAtEnd", end.wifi)
        .put("chargingAtStart", start.charging)
        .put("chargingAtEnd", end.charging)
        .put("levelStart", start.level)
        .put("levelEnd", end.level)
        .put("chargeUahStart", start.chargeUah ?: JSONObject.NULL)
        .put("chargeUahEnd", end.chargeUah ?: JSONObject.NULL)
        .put("railsJson", rails.toString())
        .put("pushJson", push.toString())
}
