package app.lawnchair.nexus.coach

import android.content.Context
import android.util.Base64
import app.lawnchair.nexus.NexusConfig
import com.android.launcher3.BuildConfig
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nordli.nexus.health.sync.net.CoachClient

/**
 * What the launcher's Eir card shows: the coach's word for today and how many of its check-ins are
 * waiting. Read over the sealed coach route with `health_sync`'s Kotlin client, which the Nexus
 * app's AAR already puts in this APK (`seal/SealedPayload.kt`, `net/CoachClient.kt` under
 * nexus-health/packages/health_sync/android, linked, not copied).
 *
 * Health data is Art. 9 data: it is shown, never logged, and a failure is `null` with no reason.
 */
object EirCoach {

    data class Today(
        /** `normal`, `watch` or `strain`; null while there is not enough data for a readiness light. */
        val light: String?,
        val suggestion: String?,
        val pendingCheckIns: Int,
    )

    /** The relay's public key the coach is sealed to; empty when this build was made without one. */
    private val sealKey: ByteArray
        get() = Base64.decode(BuildConfig.NEXUS_RELAY_PUBLIC_KEY.trim(), Base64.DEFAULT)

    val configured: Boolean get() = BuildConfig.NEXUS_RELAY_PUBLIC_KEY.isNotBlank()

    /** Today and the pending check-ins, or null when the coach cannot be reached. */
    suspend fun load(context: Context): Today? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val status = NexusConfig.read(context)
        if (!status.enabled) return@withContext null
        try {
            val client = CoachClient(status.serverUrl, NexusConfig.accessToken, sealKey)
            val day = """{"day":"${LocalDate.now()}"}""".toByteArray()
            val today = client.post("today", day)
            val pending = client.post("checkins/pending", "{}".toByteArray())
            Today(
                light = today.optJSONObject("readiness")?.optString("light")?.takeIf { it.isNotEmpty() },
                suggestion = today.optString("suggestion").takeIf { it.isNotEmpty() },
                pendingCheckIns = pending.optJSONArray("checkIns")?.length() ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }
}
