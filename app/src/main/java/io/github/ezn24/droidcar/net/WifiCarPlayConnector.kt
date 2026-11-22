package io.github.ezn24.droidcar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.ezn24.droidcar.bt.AdapterMode
import io.github.ezn24.droidcar.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Carries CarPlay traffic over the head unit hotspot.
 *
 * **Two ways to join the network (silent command line is the default since 2026-10-22)**:
 *  - Silent path (default, see `SessionController.joinCarWifiSilently`): Shizuku runs
 *    `cmd wifi connect-network` and the system joins the hotspot directly (**no dialog**); then
 *    [bindCurrentWifi] captures the `Network` handle with a `requestNetwork` **without a specifier** and binds it.
 *  - [joinCarPlayNetwork]: an app-specific network via `WifiNetworkSpecifier`, the fallback when the silent path is unavailable.
 * The network is visible only to this process (not saved, not the default network), but the first join shows a system confirmation
 * dialog.
 *
 * Key points:
 *  - The request does not carry `NET_CAPABILITY_INTERNET`, so the system does not drop the connection for "no internet";
 *  - Both paths must `bindProcessToNetwork` before creating sockets / registering mDNS: mDNS advertisement
 *    (`NsdManager.registerService`) has no Network parameter and can only follow the process default network;
 *  - The SSID must match exactly (hotspot names may contain spaces/CJK): the silent path checks with `cmd wifi status`.
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** Result of joining the app-specific network. */
    sealed interface JoinResult {
        /** Connected and bound to this process; [network] can be used for NSD/endpoint decisions. */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** Not allowed by the system (user declined / hotspot out of range / timeout). Retryable. */
        data object Unavailable : JoinResult

        /** This security type cannot be expressed with WifiNetworkSpecifier (WEP); the caller takes the old path. */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * Called when the app-specific network is dropped by the system (hotspot off / out of range). The session layer uses it to
     * notify the user;
     * the empty implementation = not interested (e.g. the manual binding path).
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * Gateway address of the currently bound network (in the head unit hotspot scenario = head unit IP).
     * Fallback path when mDNS discovery fails.
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        // Wireless adapters (smartBox) run a router-less DHCP network: no default route, so
        // the "gateway = car" fallback would be null. Use the DHCP server (= the box) first.
        if (AdapterMode.active) {
            val choice = adapterFallbackHost()
            log("WiFi: adapter fallback host ${choice?.let { "${it.host} (${it.source})" } ?: "unavailable (no DHCP server/gateway)"}")
            return choice?.host
        }
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /** DHCP server of the bound network (the box itself on a wireless adapter's Wi-Fi). */
    fun dhcpServerAddress(): String? = AdapterNetwork.dhcpServer(connectivityManager, boundNetwork.get())

    /**
     * Adapter-mode host when mDNS gave nothing usable: DHCP server → [invitePeer] (source address
     * of the box's `GET /ctrl-int/1/connect`) → default-route gateway.
     */
    fun adapterFallbackHost(invitePeer: String? = null): EndpointHosts.AdapterHost? =
        AdapterNetwork.fallbackHost(connectivityManager, boundNetwork.get(), invitePeer)

    /**
     * IPv4 address of this phone on the currently bound network (such as 10.174.189.57).
     * Used for mDNS result filtering: when the phone is dual-homed (connected to the head unit hotspot + sharing its own hotspot
     * with a computer),
     * mDNS discovers the computer's AirPlay receiver across networks; any address outside this subnet is not the head unit.
     */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * All IPv6 addresses of this phone on the currently bound network.
     *
     * For subnet acceptance of mDNS results: the Audi 4xxx `_airplay._tcp` often publishes only a link-local IPv6,
     * and the old filter judged only by IPv4 prefix, so it dropped the head unit itself as "not on this subnet" (2026-10-19).
     */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Join the head unit hotspot with an app-specific network (`WifiNetworkSpecifier`): **the fallback when the silent path is
     * unavailable**.
     * The first join shows one system confirmation dialog; the network is not saved and does not become the default network.
     *
     * Blocks until connected / refused by the system / timeout (internally in 200ms slices, [isCancelled] exits immediately).
     * On success the process network is bound, and the caller can register mDNS / create sockets directly.
     *
     * Security type mapping follows 0x5703 (xcertplay Iap2WirelessSecurity):
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 transition 4=WPA3 only.
     * WEP has no matching API (returns [JoinResult.Unsupported], the caller takes the old path).
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        /** Quick probe when the first window had no callback (millisecond shell round trip + <=3s binding; must not block the resend). */
        quickFallback: (() -> Network?)? = null,
        /**
         * Deep fallback after the resend window also had no callback (connect-network, ~10s; this round's window has passed, it
         * saves time for the next round).
         */
        deepFallback: (() -> Network?)? = null,
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        // Wait pacing is designed around the "head unit window" (Audi 3315 real-car log ruling, 2026-10-23): after 0x4301 the
        // head unit tears BT down at ~15s and closes the AirPlay service window at ~21s (late arrival = ECONNREFUSED, and a healthy BT
        // cannot activate it), so the join budget counted from 0x5703 is only ~9-14s. Budget of each stage in the specifier-hang shape
        // ("system already saved the SSID + some ROMs (Android 16/Xiaomi measured)" or forget->request in quick
        // succession, where the request stays **pending forever** with zero callbacks):
        //  1. first window 5s (normal joins measured 0.03~3s; zero callbacks in 5s is the hang signature);
        //  2. quick probe (quickFallback: if the system already connected to that SSID by itself, bind directly, <=3s);
        //  3. resend the specifier (measured after a hang: release->resend joins in 1.6s, **the verified effective
        //     recovery, must follow the probe immediately**), 20s window;
        //  4. deep fallback (deepFallback: connect-network direct join, ~10s); this round's window has certainly
        // passed, its value is getting the phone onto the hotspot first so the next round (the head unit reconnects BT within
        // 0.17s) takes the "already connected" shortcut.
        // Lesson from the old 20s first window: in round 1 the head unit tore the link down (15.2s) before the first window
        // finished, so any
        // fallback placed **after** it never got to run; in round 2 the join at 20s+1.6s=21.6s found port 7000 already refusing
        // connections.
        // Known cost: if the user holds the first-time system permission dialog for over 5s, this round's request is withdrawn and
        // resent and
        // the dialog shows again, a one-time UX cost far smaller than a failed car connection.
        val firstWindowMs = minOf(timeoutMillis, 5_000L)
        val secondWindowMs = minOf(timeoutMillis - firstWindowMs, 20_000L).coerceAtLeast(0)
        val unavailable = AtomicBoolean(false)
        var network = requestAndAwait(ssid, request, firstWindowMs, isCancelled, unavailable)
        // After an explicit system refusal (onUnavailable: user declined the dialog / hotspot out of range) a resend is pointless and
        // would only be refused again; it must be distinguished from a hang (zero callbacks).
        if (network == null && !isCancelled() && !unavailable.get() && secondWindowMs > 0) {
            release()
            if (!isCancelled()) {
                // Quick fallback success = already bound to the head unit hotspot (scenario: the system reconnected on its own),
                // return directly;
                // for car models where "the system saved and is connected to the hotspot", resending a specifier would only hang
                // again (a saved
                // SSID is itself one of the hang conditions), so the short circuit is correct.
                quickFallback?.invoke()?.let { fallback ->
                    log("WiFi: quick fallback join succeeded ($fallback); skipping specifier resend")
                    return JoinResult.Joined(fallback)
                }
            }
            if (!isCancelled()) {
                log(
                    "WiFi: first window ${firstWindowMs / 1000}s with zero callbacks; specifier may be hung, resending the network " +
                        "request (measured: a resend joins in 1.6s)",
                )
                network = requestAndAwait(ssid, request, secondWindowMs, isCancelled, unavailable)
            }
            if (network == null && !isCancelled() && !unavailable.get()) {
                release()
                if (!isCancelled()) {
                    deepFallback?.invoke()?.let { fallback ->
                        log("WiFi: deep fallback join succeeded ($fallback)")
                        return JoinResult.Joined(fallback)
                    }
                }
            }
        }
        if (network == null) {
            log(
                "WiFi: waiting to join \"$ssid\" failed (first window ${firstWindowMs / 1000}s + resend window " +
                    "${secondWindowMs / 1000}s + two-level fallback)",
            )
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** Issue one requestNetwork and wait until the window ends / success / cancel; the caller is responsible for release. */
    private fun requestAndAwait(
        ssid: String,
        request: NetworkRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        unavailable: AtomicBoolean,
    ): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log(
                        "WiFi: joined app-specific network \"$ssid\" ($network bindProcessToNetwork=$bound); other apps' " +
                            "connectivity is unaffected",
                    )
                    logAdapterLink(network)
                    latch.countDown()
                }

                override fun onUnavailable() {
                    log("WiFi: system did not allow joining \"$ssid\" (user declined or hotspot out of range)")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: app-specific network \"$ssid\" disconnected ($network)")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        // Withdraw the previous callback first: the quick fallback's bindCurrentWifi shares the callback slot with this request,
        // and overwriting directly leaves the old callback in ConnectivityManager until disconnect.
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log(
                    "WiFi: app-specific network request failed (${error.message}); may need the location permission or the system " +
                        "does not support it",
                )
                false
            }
        if (!accepted) return null
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                return null
            }
        }
        return result.get()
    }

    /** Build the specifier; returns null for WEP or invalid parameters (take the old path). */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // Open hotspot. Some head units report type 0 together with a real password (upstream
                // shaonialife/cardeck 0.1.29 field report); joining as open then never associates, so a
                // password wins over the declared type and is tried as WPA2.
                0 -> if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
                // WEP: WifiNetworkSpecifier has no matching API (modern head units no longer use it)
                1 -> return null
                // WPA/WPA2 and WPA3 transition mode: a transition-mode AP also accepts WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 only
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // Unknown type: try as WPA2 with a password (more likely to succeed than giving up outright)
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: building app-specific network parameters failed (${error.message})")
            null
        }
    }

    /**
     * Bind "the currently connected WiFi": captures the `Network` handle after the silent command-line join succeeded.
     *
     * The request **has no specifier** (declares only TRANSPORT_WIFI, removes INTERNET), so the system shows no
     * confirmation dialog and immediately calls back the head unit WiFi that was just joined. With a no-internet hotspot the
     * system may not make it
     * the default network, so bindProcessToNetwork must be explicit for our sockets / mDNS to use WiFi.
     * With a watchdog: when WiFi is not connected requestNetwork hangs silently forever, so after 5s without a result a clear hint is given
     * (the request stays pending, and once the user joins the hotspot the binding still completes automatically with a callback).
     */
    fun bindCurrentWifi(
        /**
         * SSID of the target hotspot to bind; the disconnect notification ([onNetworkLost]) needs it; pass null if unknown (no
         * disconnect notification).
         */
        ssidHint: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // The network callback thread and the Bg watchdog thread read/write concurrently; an atomic flag guarantees visibility and
        // single delivery
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork.set(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    log("WiFi: bound current WiFi network $network bindProcessToNetwork=$bound")
                    logAdapterLink(network)
                    if (reported.compareAndSet(false, true)) onResult(true)
                }

                override fun onUnavailable() {
                    log("WiFi: no usable WiFi connection; join the receiver hotspot in system settings first")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        // Car switched off / out of range: unbind the process network, otherwise all sockets keep pointing at a
                        // dead network;
                        // and notify the session layer (the hero card must not stay on "connected").
                        // Same semantics as the specifier path: the fallback join (bindCurrentWifi) is the main path on the Audi 3315,
                        // and not forwarding onLost would stay on a false "connected" until the heartbeat times out
                        // (review ruling 2026-10-23).
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: bound WiFi network disconnected ($network)")
                        ssidHint?.let { onNetworkLost?.invoke(it) }
                    }
                }
            }
        callback.set(newCallback)
        connectivityManager.requestNetwork(request, newCallback)
        // Hint-only watchdog (**does not decide success**): when "the phone has not yet joined WiFi" requestNetwork
        // hangs until the system finishes association+DHCP before calling onAvailable; measured up to 10s+ when the user
        // joins the hotspot by hand. It only gives the user one hint at 5s; the timeout verdict belongs to the caller's
        // WIFI_BIND_WAIT_S, and the request must not be cancelled here (a late callback still binds correctly).
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get()) {
                log(
                    "WiFi: not bound after 5s (the phone may not have joined the hotspot yet); join the receiver hotspot under " +
                        "Settings > WLAN, and this round's binding completes automatically after that",
                )
            }
        }
    }

    /**
     * Adapter-mode diagnostic: the box's network has no default route and no internet, which is
     * expected (binding doesn't require either); log what the host fallback will have to work with.
     */
    private fun logAdapterLink(network: Network) {
        if (!AdapterMode.active) return
        val dhcp = AdapterNetwork.dhcpServer(connectivityManager, network)
        val gateway = AdapterNetwork.defaultGateway(connectivityManager, network)
        log("WiFi: adapter link dhcpServer=${dhcp ?: "none"} defaultGateway=${gateway ?: "none (router-less, expected)"}")
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
