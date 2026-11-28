/*
 * Ported from FT's CarBeacon / CarDiscovery (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar (threads instead of coroutines).
 */
package io.github.ezn24.droidcar.carlife

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * UDP discovery both ways: the phone broadcasts "CarlifeHost" on udp 7999 every 1.5 s (head units that join the phone's
 * hotspot, or the Wi-Fi Direct group owner, connect back to the TCP channels when they hear it), and answers a car's
 * own search on udp 8999 with a ready message.
 */
class CarLifeDiscovery(
    private val log: (String) -> Unit,
    private val active: () -> Boolean,
) {
    @Volatile private var running = false
    private var responder: DatagramSocket? = null

    /** Extra unicast target (the car's address on Wi-Fi Direct). */
    @Volatile var carAddress: InetAddress? = null

    fun start() {
        if (running) return
        running = true
        Thread({ beaconLoop() }, "carlife-beacon").apply {
            isDaemon = true
            start()
        }
        Thread({ respondLoop() }, "carlife-discovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { responder?.close() }
        responder = null
    }

    private fun beaconLoop() {
        var sent = 0
        var tick = 0L
        while (running) {
            // Always on now (no switch): hotspot and Wi-Fi Direct get the beacon every 1.5 s, an ordinary Wi-Fi
            // network (a head unit on the same LAN, e.g. carlife_pc_tool) every 6 s to keep radio wake-ups low
            val everywhere = tick++ % STATION_EVERY == 0L
            if (active()) {
                val targets = LinkedHashSet<InetAddress>(broadcastAddresses(everywhere))
                carAddress?.let(targets::add)
                if (targets.isNotEmpty()) {
                    runCatching {
                        DatagramSocket().use { s ->
                            s.broadcast = true
                            for (t in targets) runCatching { s.send(DatagramPacket(HOST, HOST.size, t, CarLifeProtocol.DISCOVERY_PORT)) }
                        }
                    }
                    if (sent == 0) log("CARLIFE: discovery beacon on udp ${CarLifeProtocol.DISCOVERY_PORT}")
                    sent++
                }
            }
            Thread.sleep(BEACON_MS)
        }
    }

    private fun respondLoop() {
        val s =
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SEARCH_PORT))
                }
            }.getOrElse {
                log("CARLIFE: cannot listen for car discovery on udp $SEARCH_PORT: ${it.message}")
                return
            }
        responder = s
        val buf = ByteArray(1024)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(p) }.isFailure) break
            val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
            if (!text.contains("carlifehost")) continue
            runCatching { s.send(DatagramPacket(READY, READY.size, p.address, p.port)) }
                .onSuccess { log("CARLIFE: answered a car looking for the phone") }
        }
        runCatching { s.close() }
    }

    private fun broadcastAddresses(stationToo: Boolean): List<InetAddress> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
                .filter { stationToo || HOTSPOT_PREFIXES.any { p -> it.name.startsWith(p) } }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .filter { it is Inet4Address }
                .distinct()
        }.getOrDefault(emptyList())

    private companion object {
        const val SEARCH_PORT = 8999
        const val BEACON_MS = 1500L
        const val STATION_EVERY = 4L

        /** Phone hotspot (swlan / ap / softap) and Wi-Fi Direct (p2p) interfaces. */
        val HOTSPOT_PREFIXES = listOf("p2p", "swlan", "ap", "softap", "wlan1", "wlan2")
        val HOST = "CarlifeHost".toByteArray()
        val READY = "{\"carlifehost\":\"carlife\",\"status\":\"ready\"}".toByteArray()
    }
}
