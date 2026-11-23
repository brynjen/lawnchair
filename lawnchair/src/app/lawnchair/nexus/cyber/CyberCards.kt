package app.lawnchair.nexus.cyber

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * A card that waits on the household, as nexus lists it (`/cyber/host/waiting`, nexus-cyber's
 * `ImpactCard`): something a sensor raised that a person has to decide on, in the Cyber page.
 */
data class WaitingCard(
    val id: String,
    val title: String,
    val device: String,
    val severity: String,
    val summary: String,
    val raisedAtMs: Long,
) {
    /** The line under the title: where it is, and how bad. */
    val text: String get() = "$device · ${severity.replaceFirstChar { it.uppercase() }}"
}

/** The host's list; a card it cannot read is left out. */
fun parseWaitingCards(value: Any?): List<WaitingCard> {
    val list = value as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { index ->
        val json = list.optJSONObject(index) ?: return@mapNotNull null
        runCatching { waitingCardOf(json) }.getOrNull()
    }
}

private fun waitingCardOf(json: JSONObject) = WaitingCard(
    id = json.getString("id"),
    title = json.getString("title"),
    device = json.getString("device"),
    severity = json.getString("severity"),
    summary = json.getString("summary"),
    raisedAtMs = Instant.parse(json.getString("raisedAt")).toEpochMilli(),
)

/** Where a tap opens the app: the Cyber page on its own, so back leaves the app again. */
const val CYBER_ROUTE = "/cyber?alone=1"
