package io.github.ezn24.cardeck.neo.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bluetooth "**pair only, do not connect**" policy.
 *
 * Audi MIB3 measured (2026-10-18, user report): while the phone and the head unit are **connected** over Bluetooth
 * (HFP call / A2DP media profiles online), the head unit does not open its wireless CarPlay iAP2 service
 * window, which shows up as `BT-C: connect failed (head unit not ready or not in wireless pairing window)` retried every 3s.
 * A real iPhone's wireless CarPlay does not rely on these profiles either: the control channel uses iAP2 RFCOMM (a separate
 * SDP service) and audio goes over AirPlay on WiFi. So the right shape is **system-level pairing + iAP2
 * RFCOMM, never connect any other profile**.
 *
 * Implementation: `BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` is a
 * **@SystemApi**, invisible at compile time, but at runtime the annotation only requires `BLUETOOTH_CONNECT`
 * (which we hold), so reflection works. State is queried with the public `getProfileProxy` +
 * `connectedDevices` (needs the main Looper, which the app process has).
 *
 * Moving this into the Shizuku UserService (shell uid) was tried and does not work:
 * `BluetoothManager.getAdapter()` returns null there (app context without a main Looper), so the query always says
 * "not connected" and hides the real state. Hence it stays in the app process.
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /** Profiles we care about (in disconnect priority order). */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "Call(HFP)"
            BluetoothProfile.A2DP -> "Audio(A2DP)"
            BluetoothProfile.LE_AUDIO -> "LE Audio"
            else -> "profile#$profile"
        }

    /** profile id -> acquired proxy (reused in-process; the proxy itself holds no session state). */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** Names of the profiles currently connected for this address. */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * Best-effort disconnect of every profile connection of this device.
     *
     * @return profiles that were connected before disconnecting (empty = nothing was connected, no action needed)
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        for (id in profiles) {
            if (profileName(id) !in before) continue
            proxy(context, id)?.let { disconnectProfile(it, device) }
        }
        if (!awaitDisconnected(context, address, 1_500L)) {
            // Profile-level disconnect had no effect (some ROMs rate-limit): fall back to a device-level disconnect
            // (@hide, also only needs BLUETOOTH_CONNECT). Other devices connected to the car/headset may be dropped too; expected.
            runCatching { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
            awaitDisconnected(context, address, 500L)
        }
        return before
    }

    /** Profiles still connected after disconnecting (used by logs to tell whether the disconnect worked). */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // Old proxy is invalid after a Bluetooth service restart: clear the cache and re-acquire next time
                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        // The `getProfileProxy` callback is delivered through the **main-thread Handler**: if this method runs on the main
        // thread the callback can never run, so we would always wait out the timeout (1.2s) and return null. Fail explicitly
        // rather than stall silently; callers must move to a background thread (all entry points of this object are background calls).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() called on the main thread: the getProfileProxy callback would be blocked, returning null")
            return null
        }
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /**
     * Disconnect one profile. `disconnect(BluetoothDevice)` is a @SystemApi (invisible at compile time),
     * called via reflection; failures do not throw, the caller judges by the final state.
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}
