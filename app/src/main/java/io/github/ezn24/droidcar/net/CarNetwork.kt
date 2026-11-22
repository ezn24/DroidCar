package io.github.ezn24.droidcar.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins every head-unit socket to the car hotspot [Network] explicitly, on top of bindProcessToNetwork.
 *
 * Why (chengshl/cardeck T47): with a VPN app running (Clash, corporate VPN, ad blockers) an unpinned socket follows the
 * uid's default network, which is the VPN. Traffic to the head unit's 10.x / 192.168.x address then enters the tunnel and
 * the VPN forwards it over cellular, so pairing / SETUP time out or the stream lags. `Network.bindSocket` on the hotspot
 * network keeps the AirPlay control, event, video, audio and timing sockets on the physical Wi-Fi.
 *
 * A VPN that does not allow bypass (VpnService.Builder.allowBypass not called, the default) makes netd reject the
 * explicit selection with EPERM: Android does not let an app leave such a VPN. That case cannot be fixed from here, so it
 * is logged once per session with what the user can do (exclude DroidCar in the VPN app, or let it bypass the LAN), and
 * the socket falls back to the process binding.
 *
 * With no VPN this is a no-op in effect: the process is already bound to the same network.
 */
object CarNetwork {
    /** The car hotspot network bound for this session (WifiCarPlayConnector.currentNetwork); null = not bound, do nothing. */
    @Volatile
    var provider: () -> Network? = { null }

    @Volatile
    var connectivity: ConnectivityManager? = null

    @Volatile
    var log: (String) -> Unit = {}

    /** Called once per session when a VPN keeps the head-unit sockets off the hotspot (for a user-visible hint). */
    @Volatile
    var onVpnBlocked: () -> Unit = {}

    private val failureReported = AtomicBoolean(false)
    private val vpnNoted = AtomicBoolean(false)

    fun bind(
        socket: Socket,
        label: String,
    ): Boolean = pin(label) { it.bindSocket(socket) }

    fun bind(
        socket: DatagramSocket,
        label: String,
    ): Boolean = pin(label) { it.bindSocket(socket) }

    /** Re-arms the once-per-session log lines (called when a session is retired). */
    fun resetSession() {
        failureReported.set(false)
        vpnNoted.set(false)
    }

    private inline fun pin(
        label: String,
        action: (Network) -> Unit,
    ): Boolean {
        val network = runCatching { provider() }.getOrNull() ?: return false
        return try {
            action(network)
            if (vpnActive() && vpnNoted.compareAndSet(false, true)) {
                log("NET: a VPN is active - head-unit sockets are pinned to the hotspot network (first: $label)")
            }
            true
        } catch (error: Exception) {
            if (failureReported.compareAndSet(false, true)) {
                val vpn = vpnActive()
                log(
                    "NET: could not pin $label to the head-unit network - ${error.message}" +
                        if (vpn) {
                            "; a VPN without bypass is active, so head-unit traffic may enter the VPN. " +
                                "Exclude DroidCar in the VPN app (per-app / split tunnel) or let it bypass the LAN"
                        } else {
                            "; falling back to the process network binding"
                        },
                )
                if (vpn) runCatching { onVpnBlocked() }
            }
            false
        }
    }

    /** activeNetwork is the uid's default network (ignores the process binding), so it is the VPN when one applies. */
    fun vpnActive(): Boolean {
        val cm = connectivity ?: return false
        return runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)
    }
}
