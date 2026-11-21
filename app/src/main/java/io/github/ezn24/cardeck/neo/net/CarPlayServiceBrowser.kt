package io.github.ezn24.cardeck.neo.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.ezn24.cardeck.neo.bt.AdapterMode
import io.github.ezn24.cardeck.neo.util.Bg
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mDNS service discovery: finds the _airplay._tcp advertised by the receiver (head unit/xcertplay)
 * and resolves the AirPlay endpoint and TXT records (deviceid/features/pi/pk/model...).
 *
 * In the real sequence this step happens after joining the head unit hotspot and before starting the AirPlay main session.
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** Result acceptance: only cache/report real head unit endpoints (on a dual-homed phone mDNS mixes in other AirPlay devices). */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"

        /** Adapter mode: how long a resolve may stay silent before we synthesize the endpoint. */
        private const val ADAPTER_RESOLVE_TIMEOUT_MS = 4_000L

        /** smartBox `_airplay._tcp` port (field capture smartbox_local_20261026.json). */
        private const val ADAPTER_AIRPLAY_PORT = 7000
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var discovering = false

    /**
     * The resolved `_airplay._tcp` (on this subnet). Background discovery starts the instant the head unit hotspot is joined,
     * so by the time the endpoint address is really needed it has usually hit. Compare catplay: it keeps a resident Bonjour cache +
     * `watch`, so the address is ready when the ctrl invitation arrives; we used to query right before pairing and waited several
     * seconds for nothing.
     */
    @Volatile
    private var resolved: NsdServiceInfo? = null

    /** Clear the previous round's cache when a new session starts (the head unit may have changed address/port). */
    fun resetCache() {
        resolved = null
    }

    /**
     * Wait for a resolved result (polling the cache). Returns null = still not discovered at timeout.
     * Callers usually keep doing other things while waiting (binding the network, handshakes); this is not a pure blocking sleep.
     */
    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        return resolved
    }

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: already scanning")
            return
        }
        log("$TAG: starting discovery of $serviceType")
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        discovering = true
    }

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
        log("$TAG: stopping discovery")
    }

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                discovering = false
                log("$TAG: discovery start failed code=$errorCode")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                log("$TAG: stop failed code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("$TAG: service found ${serviceInfo.serviceName} (${serviceInfo.serviceType}), resolving...")
                resolveWithRetry(serviceInfo, attempts = 3)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("$TAG: service lost ${serviceInfo.serviceName}")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
    ) {
        // Adapter mode only: the smartBox's SRV target is `(none).local.`, so resolution can fail,
        // hang, or return no usable host (unapple-phone NEXT_STEPS "Local smartBox field result").
        val adapter = AdapterMode.active && serviceInfo.serviceType.orEmpty().contains(AIRPLAY_TYPE)
        val settled = AtomicBoolean(false)
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                        settled.set(true)
                        Thread {
                            Thread.sleep(200)
                            resolveWithRetry(info, attempts - 1)
                        }.start()
                    } else {
                        log("$TAG: resolve failed ${info.serviceName} code=$errorCode")
                        if (adapter && settled.compareAndSet(false, true)) {
                            deliverAdapterFallback(info, "resolve failed code=$errorCode")
                        }
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    settled.set(true)
                    if (adapter) repairAdapterHost(info)
                    deliver(info)
                }
            }
        nsdManager.resolveService(serviceInfo, listener)
        if (adapter) {
            Bg.run("nsd-adapter-resolve-watchdog") {
                try {
                    Thread.sleep(ADAPTER_RESOLVE_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return@run
                }
                if (!settled.compareAndSet(false, true)) return@run
                // A pending resolve blocks later ones on the same NSD client; stop it where the API exists.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { nsdManager.stopServiceResolution(listener) }
                }
                deliverAdapterFallback(serviceInfo, "no resolve callback in ${ADAPTER_RESOLVE_TIMEOUT_MS}ms")
            }
        }
    }

    private fun deliver(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: "no-host"
        val txt =
            info.attributes.entries.joinToString(", ") { (key, value) ->
                val text =
                    value
                        ?.toString(Charsets.UTF_8)
                        ?.trimEnd { it.code == 0 }
                        .orEmpty()
                "$key=$text"
            }
        // Acceptance goes before the cache: non-head-unit endpoints (such as a Mac on the same WiFi) must not pollute awaitEndpoint
        val accepted = accept(info)
        log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else " (not a head unit, ignored)"}")
        if (txt.isNotEmpty()) log("$TAG: TXT $txt")
        if (accepted) resolved = info
        onEndpoint(info)
    }

    /** Replaces an unusable resolved host with the DHCP server / gateway of the bound network. */
    private fun repairAdapterHost(info: NsdServiceInfo) {
        val address = info.host?.hostAddress
        // InetAddress.toString() is "name/literal" without a reverse lookup; the name is the SRV target.
        val target = info.host?.toString()?.substringBefore('/')
        if (target != null && target.isNotEmpty() && EndpointHosts.isMalformedSrvTarget(target)) {
            log("$TAG: adapter ${info.serviceName} has malformed SRV target \"$target\" (resolved address=${address ?: "none"})")
        }
        val choice =
            EndpointHosts.chooseAdapterHost(
                mdnsAddress = address,
                dhcpServer = AdapterNetwork.dhcpServer(connectivityManager, connectivityManager.boundNetworkForProcess),
                gateway = AdapterNetwork.defaultGateway(connectivityManager, connectivityManager.boundNetworkForProcess),
            )
        if (choice == null) {
            log("$TAG: adapter ${info.serviceName} resolved without a usable host (host=${info.host}) and no DHCP server/gateway")
            return
        }
        if (choice.source != EndpointHosts.AdapterHostSource.MDNS) {
            log("$TAG: adapter ${info.serviceName} resolved host=${info.host} unusable - using ${choice.source} ${choice.host}")
            runCatching { info.host = InetAddress.getByName(choice.host) }
        }
        if (info.port <= 0) info.port = ADAPTER_AIRPLAY_PORT
    }

    /** Resolution failed/hung for the adapter's `_airplay._tcp`: synthesize an endpoint (no TXT). */
    private fun deliverAdapterFallback(
        info: NsdServiceInfo,
        reason: String,
    ) {
        val fallback = AdapterNetwork.fallbackHost(connectivityManager, connectivityManager.boundNetworkForProcess)
        if (fallback == null) {
            log("$TAG: adapter ${info.serviceName}: $reason, and the bound network has no DHCP server/gateway - no fallback")
            return
        }
        val port = if (info.port > 0) info.port else ADAPTER_AIRPLAY_PORT
        val synthesized =
            try {
                NsdServiceInfo().apply {
                    serviceName = info.serviceName
                    serviceType = info.serviceType
                    host = InetAddress.getByName(fallback.host)
                    setPort(port)
                }
            } catch (error: Exception) {
                log("$TAG: adapter fallback endpoint build failed (${error.message})")
                return
            }
        log("$TAG: adapter ${info.serviceName}: $reason - using ${fallback.source} ${fallback.host}:$port (box SRV target is unresolvable)")
        deliver(synthesized)
    }
}
