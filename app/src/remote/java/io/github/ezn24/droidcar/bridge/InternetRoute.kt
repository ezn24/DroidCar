package io.github.ezn24.droidcar.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Internet channel selector for the debug bridge: tracks an available cellular network as a **fallback**.
 *
 * Why it exists (settled in the 2026-10-21 DiPlay real-car session): the head unit hotspot is joined with
 * `WifiNetworkSpecifier` + a process-bound network, so during a session **all default sockets of this process are routed to
 * the head unit hotspot**, which has no internet, and the debug bridge WebSocket drops the very millisecond the hotspot is joined.
 * Multi-network sockets are the standard Android solution: build sockets from the cellular `Network.socketFactory` and
 * resolve DNS through that network too, decoupled from the process default route, so logs keep flowing during a car session.
 *
 * Selection policy, see [current]: default network validated (normal WiFi/cellular everyday state) -> use default;
 * not validated (head unit hotspot) -> cellular. Cellular is tracked but WiFi is not because specifier networks such as the
 * head unit hotspot may also report NET_CAPABILITY_INTERNET (without validation), so
 * picking "WiFi with INTERNET" would select exactly the head unit hotspot.
 */
class InternetRoute(
    context: Context,
) {
    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var cellular: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellular = network
            }

            override fun onLost(network: Network) {
                if (cellular == network) cellular = null
            }
        }

    fun start() {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        cellular = null
    }

    /** The currently preferred internet channel; null = the process default network is usable, use the system default route directly.
     *
     *  A default network with VALIDATED (really reaches the internet, e.g. home/office WiFi) is preferred: going cellular
     *  unconditionally is worse, since indoors the cellular signal is weak and a 15MB APK dropped mid-way in testing
     *  (unexpected end of stream, 2026-10-21). During a car session the process default network is the
     *  head unit hotspot (no internet, not VALIDATED), and only then do we fall back to cellular.
     *  **But VALIDATED is a cached verdict, not current reachability** (2026-10-22 observed: the bench hotspot has no uplink
     *  internet yet is still marked VALIDATED, every reconnect round hit ENETUNREACH and never switched route): if the default
     *  network failed recently ([noteFailure]) go cellular instead; the verdict resets on success ([noteSuccess]) or when the
     *  default network changes (netId changes, e.g. unbinding when a session ends). */
    fun current(): Network? {
        val active = cm.activeNetwork ?: return cellular
        // toString() is the decimal netId string (a public way to get the network identity): a default network change
        // (unbinding at session end / network switch) counts as a new network and resets the failure verdict
        val netKey = active.toString()
        if (netKey != lastActiveNetKey) {
            lastActiveNetKey = netKey
            defaultFailStreak = 0
        }
        val caps = cm.getNetworkCapabilities(active) ?: return cellular
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return cellular
        if (defaultFailStreak > 0) return cellular
        return null
    }

    /** One connection failure on the default network: consecutive failures switch to cellular until the network changes or a success resets it. */
    fun noteFailure(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak++
    }

    /** A connection on the default network succeeded: clear the failure verdict and restore the fast path that prefers the default. */
    fun noteSuccess(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak = 0
    }

    @Volatile
    private var defaultFailStreak = 0

    private var lastActiveNetKey: String? = null
}
