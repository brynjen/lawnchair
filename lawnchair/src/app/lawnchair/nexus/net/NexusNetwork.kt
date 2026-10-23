package app.lawnchair.nexus.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the phone has a network, as the system says it changes: the answer now, and another each
 * time the default network comes or goes (Wi-Fi to mobile data is no change; airplane mode is).
 * Nothing is sent to the server: the system calls back, so a change costs no radio time and a quiet
 * network costs nothing. A network that is up may still have no way to the server; that is the
 * server's own word to give (see the News header), not a probe from here.
 *
 * Cancelling the collection unregisters the callback, so collect it only while someone is looking.
 */
fun networkUp(context: Context): Flow<Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return networkUp(
        now = { manager.getNetworkCapabilities(manager.activeNetwork).hasInternet() },
        watch = { changed ->
            // The network the callback is following. A hand-over from Wi-Fi to mobile data can tell of the
            // new one before the old one is lost: the old one's loss is then not the phone's.
            var current: Network? = null
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    current = network
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    current = network
                    changed(capabilities.hasInternet())
                }

                override fun onLost(network: Network) {
                    if (network == current) {
                        current = null
                        changed(false)
                    }
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            val stop: () -> Unit = { manager.unregisterNetworkCallback(callback) }
            stop
        },
    )
}

private fun NetworkCapabilities?.hasInternet(): Boolean =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

/**
 * The flow behind [networkUp], with the system left out: [now] is the answer at this moment,
 * [watch] starts telling [changed] of each new answer and returns the call that stops it. One value
 * per change; the same answer twice is one.
 */
internal fun networkUp(
    now: () -> Boolean,
    watch: (changed: (Boolean) -> Unit) -> () -> Unit,
): Flow<Boolean> = callbackFlow {
    trySend(now())
    val stop = watch { trySend(it) }
    awaitClose(stop)
}.distinctUntilChanged()
