package app.lawnchair.nexus

import app.lawnchair.nexus.power.PowerSnapshot
import app.lawnchair.nexus.power.powerRecordingJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A power recording with no phone under it: the two ends kept and read back, and what is sent of them. */
class NexusPowerTest {

    private val start = PowerSnapshot(
        atMs = 1_000,
        wifi = true,
        charging = false,
        level = 80,
        chargeUah = 3_800_000,
        rails = mapOf("VSYS_PWR_MODEM" to 100L, "VSYS_PWR_WLAN_BT" to 500L, "GONE_AT_END" to 7L),
        pushOpens = 2,
        pushFailures = 1,
        pushMessages = 10,
        pushConnectedSeconds = 600,
    )
    private val end = start.copy(
        atMs = 5_000,
        wifi = false,
        level = 74,
        chargeUah = 3_525_000,
        rails = mapOf("VSYS_PWR_MODEM" to 160L, "VSYS_PWR_WLAN_BT" to 400L, "NEW_AT_END" to 9L),
        pushOpens = 3,
        pushFailures = 1,
        pushMessages = 13,
        pushConnectedSeconds = 4_000,
    )

    @Test
    fun `a snapshot is kept as text and read back the same`() {
        assertEquals(start, PowerSnapshot.parse(start.toJson()))
        assertEquals(start.copy(chargeUah = null, rails = emptyMap()), PowerSnapshot.parse(start.copy(chargeUah = null, rails = emptyMap()).toJson()))
        assertNull(PowerSnapshot.parse(null))
        assertNull(PowerSnapshot.parse("{}"))
        assertNull(PowerSnapshot.parse("not json"))
    }

    @Test
    fun `sends what each meter counted between the ends, and only the meters both ends read`() {
        val sent = powerRecordingJson(start, end, "Pixel 8 Pro", pushEnabled = true, keepaliveSeconds = 90) { "t$it" }
        assertEquals("t1000", sent.getString("startedAt"))
        assertEquals("t5000", sent.getString("endedAt"))
        assertTrue(sent.getBoolean("wifiAtStart"))
        assertFalse(sent.getBoolean("wifiAtEnd"))
        assertEquals(90, sent.getInt("keepaliveSeconds"))
        assertEquals(3_800_000L, sent.getLong("chargeUahStart"))
        // The Wi-Fi meter went backwards (a restart) and two were read at one end only: all three are left out.
        assertEquals("""{"VSYS_PWR_MODEM":60}""", sent.getString("railsJson"))
        val push = JSONObject(sent.getString("pushJson"))
        assertEquals(1, push.getInt("opens"))
        assertEquals(0, push.getInt("failures"))
        assertEquals(3, push.getInt("messages"))
        assertEquals(3_400L, push.getLong("connectedSeconds"))
        assertFalse(push.has("restarted"))
    }

    @Test
    fun `a launcher that restarted in between sends the counts since it came up, and says so`() {
        val after = end.copy(pushOpens = 1, pushFailures = 0, pushMessages = 2, pushConnectedSeconds = 300)
        val push = JSONObject(
            powerRecordingJson(start, after, "Pixel 8 Pro", pushEnabled = false, keepaliveSeconds = null) { "$it" }
                .getString("pushJson"),
        )
        assertEquals(1, push.getInt("opens"))
        assertEquals(2, push.getInt("messages"))
        assertTrue(push.getBoolean("restarted"))
    }
}
