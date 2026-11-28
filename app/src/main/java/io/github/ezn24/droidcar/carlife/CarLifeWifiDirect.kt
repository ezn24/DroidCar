/*
 * Join logic follows FT's CarFinder (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Rewritten for DroidCar (single target, no recovery tiers).
 */
package io.github.ezn24.droidcar.carlife

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Joins the head unit's Wi-Fi Direct group by the name it announced over Bluetooth. The car is the group owner; the
 * phone joins with push-button WPS and group-owner intent 0, then reports its own address on the p2p interface.
 * All WifiP2pManager callbacks run on the main looper.
 */
@SuppressLint("MissingPermission")
class CarLifeWifiDirect(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** Phone address on the group (null when the group is gone) and the car's (group owner) address. */
    var onAddress: ((phoneIp: String?, carIp: String?) -> Unit)? = null

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile private var target: String? = null
    private var connecting = false
    private var connectAt = 0L
    private var joinedIface: String? = null

    @Volatile var joined = false
        private set

    fun start() =
        main.post {
            if (channel != null) return@post
            val m = manager ?: return@post log("CARLIFE: this phone has no Wi-Fi Direct")
            channel = m.initialize(context, Looper.getMainLooper()) { log("CARLIFE: Wi-Fi Direct channel lost") }
            val r =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        i: Intent,
                    ) = handle(i)
                }
            val f =
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, f)
            }
            receiver = r
        }

    fun stop() =
        main.post {
            target = null
            connecting = false
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
            val m = manager
            val ch = channel
            if (m != null && ch != null) {
                if (joined) runCatching { m.removeGroup(ch, null) }
                runCatching { m.stopPeerDiscovery(ch, null) }
                if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
            }
            channel = null
            if (joined) {
                joined = false
                onAddress?.invoke(null, null)
            }
        }

    /** The car announced its group name over Bluetooth: look for it and join. */
    fun join(name: String) =
        main.post {
            target = name
            connecting = false
            discover()
        }

    private fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(
            ch,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    log("CARLIFE: Wi-Fi Direct search failed reason=$reason")
                    if (target != null && !joined) main.postDelayed({ discover() }, RETRY_MS)
                }
            },
        )
    }

    private fun handle(i: Intent) {
        when (i.action) {
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> peers()
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info: WifiP2pInfo? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                if (info?.groupFormed == true) {
                    onGroup(info)
                } else if (joined) {
                    joined = false
                    joinedIface = null
                    log("CARLIFE: left the car's Wi-Fi Direct group")
                    onAddress?.invoke(null, null)
                    if (target != null) discover()
                }
            }
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                if (on && target != null && !joined) discover()
            }
        }
    }

    private fun peers() {
        val m = manager ?: return
        val ch = channel ?: return
        val want = target ?: return
        if (joined) return
        m.requestPeers(ch) { list ->
            val car = list.deviceList.firstOrNull { it.deviceName?.trim() == want } ?: return@requestPeers
            val now = android.os.SystemClock.elapsedRealtime()
            if (connecting && now - connectAt < JOIN_TIMEOUT_MS) return@requestPeers
            if (car.status == WifiP2pDevice.CONNECTED) return@requestPeers
            connect(car)
        }
    }

    private fun connect(car: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        connecting = true
        connectAt = android.os.SystemClock.elapsedRealtime()
        log("CARLIFE: joining the car's Wi-Fi Direct group")
        val cfg =
            WifiP2pConfig().apply {
                deviceAddress = car.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
        m.connect(
            ch,
            cfg,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    log("CARLIFE: Wi-Fi Direct join failed reason=$reason")
                    connecting = false
                    main.postDelayed({ discover() }, RETRY_MS)
                }
            },
        )
    }

    private fun onGroup(info: WifiP2pInfo) {
        val m = manager ?: return
        val ch = channel ?: return
        val carIp = info.groupOwnerAddress?.hostAddress ?: return
        m.requestGroupInfo(ch) { g ->
            val iface = g?.`interface`
            if (joined && iface == joinedIface) return@requestGroupInfo
            joined = true
            connecting = false
            joinedIface = iface
            log("CARLIFE: on the car's Wi-Fi Direct group (iface=$iface, we own it=${info.isGroupOwner})")
            // The p2p interface address shows up a moment after the group forms
            io.github.ezn24.droidcar.util.Bg.run("carlife-p2p-ip") {
                var ip: String? = null
                var tries = 0
                while (ip == null && tries++ < 40 && joined) {
                    ip = ipv4Of(iface)
                    if (ip == null) Thread.sleep(500)
                }
                if (ip == null) {
                    log("CARLIFE: Wi-Fi Direct is up but the phone has no address on it")
                } else {
                    onAddress?.invoke(ip, if (info.isGroupOwner) null else carIp)
                }
            }
        }
    }

    private fun ipv4Of(iface: String?): String? =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && (iface.isNullOrBlank() && it.name.startsWith("p2p") || it.name == iface) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

    private companion object {
        const val RETRY_MS = 3000L
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
