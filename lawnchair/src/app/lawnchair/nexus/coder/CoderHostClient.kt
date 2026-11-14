package app.lawnchair.nexus.coder

import java.io.IOException
import java.net.URL
import java.util.Base64
import no.nordli.nexus.health.sync.net.HttpReply
import no.nordli.nexus.health.sync.net.HttpTransport
import no.nordli.nexus.health.sync.net.UrlConnectionTransport
import no.nordli.nexus.health.sync.seal.SealedPayload
import no.nordli.nexus.health.sync.seal.SealedPayloadException
import no.nordli.nexus.health.sync.seal.SealedReply
import org.json.JSONObject

/**
 * A call on nexus's sealed coder host route, `POST /coder/host/<call>`, as nexus_mobile's
 * `CoderHostClient` makes it: the request sealed to the relay's key, the reply `{"value": …}`
 * sealed back, and a reply that is not sealed refused. It seals with `health_sync`'s Kotlin port
 * (`seal/SealedPayload.kt`), which the app's AAR already puts in this APK (nexus's
 * docs/client-sealing.md: build on that port, never a second one).
 */
class CoderHostClient(
    baseUrl: String,
    private val authKey: String,
    private val sealPublicKey: ByteArray,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    private val base = baseUrl.trimEnd('/')

    init {
        require(sealPublicKey.size == SealedPayload.KEY_LENGTH) { "sealPublicKey is not ${SealedPayload.KEY_LENGTH} bytes" }
    }

    /** The call's `value`, which is JSON of any kind. */
    fun call(name: String, arguments: JSONObject = JSONObject()): Any? {
        val url = URL("$base/coder/host/$name")
        val sealed = SealedPayload.seal(arguments.toString().toByteArray(), sealPublicKey, "POST", url.file, JSON)
        val reply = transport.post(
            url,
            mapOf(
                "authorization" to "Bearer $authKey",
                "content-type" to "application/nexus-sealed",
                INNER_TYPE to JSON,
            ),
            sealed.body,
        )
        val innerType = reply.headers[INNER_TYPE]
            ?: throw if (reply.status < 400) {
                SealedPayloadException("the host answered ${reply.status} unsealed; a plaintext reply is refused")
            } else {
                IOException("the host refused the call with HTTP ${reply.status}")
            }
        val inner = SealedReply.open(reply.body, sealed.replyOpener(reply.status, innerType, salt(reply)))
        if (reply.status >= 400) throw IOException("the host answered HTTP ${reply.status}")
        return JSONObject(String(inner, Charsets.UTF_8)).opt("value")
    }

    private fun salt(reply: HttpReply): ByteArray? =
        reply.headers["x-nexus-sealed-salt"]?.let { Base64.getDecoder().decode(it.trim()) }

    private companion object {
        const val JSON = "application/json"
        const val INNER_TYPE = "x-nexus-sealed-inner-type"
    }
}
