package io.github.ezn24.cardeck.neo.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.ezn24.cardeck.neo.shizuku.Shell

/**
 * Local identity resolution: Bluetooth MAC and device name (required by the wireless CarPlay protocol).
 *
 * Background (Audi MIB3 real-car test, 2026-10-09): on Android 11+ app-level
 * `BluetoothAdapter.getAddress()` only returns the placeholder `02:00:00:00:00:00`;
 * while the head unit, when parsing the wireless group of 0x4300 (bluetoothTransportIdentifier) and the later
 * 0x4E0E, checks it against the actual RFCOMM peer; the placeholder makes it silently drop the message
 * (symptom: link layer still ACKs, CSM layer stops responding, 0x4301 never arrives).
 *
 * Resolution order:
 *  1. Shizuku shell: `content query --uri content://settings/secure/bluetooth_address`
 *     (the shell identity can read secure settings, no root)
 *  2. Direct `Settings.Secure` read (unreadable for ordinary apps on most devices; fallback)
 * A successful result is cached permanently; after a failure no retry for 10 seconds (avoids blocking 4 seconds per connection).
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * Real Bluetooth MAC (uppercase colon format); returns the placeholder if resolution fails.
     *
     * @param forceRetry ignore the quiet window and retry immediately. With the ctrl broadcast moved ahead of "start connection",
     *   the first registration may catch Shizuku not ready and only get the placeholder, while the head unit matches by
     *   TXT id (= real Bluetooth MAC) to decide whether to invite; a placeholder means never being invited. The broadcast side
     *   must retry at once on detecting a placeholder instead of waiting out the full 10s quiet window.
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            val resolved = resolveViaShizuku() ?: resolveViaSettings(context) ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /**
     * Resolved and cached local Bluetooth MAC; null if not yet resolved (does not trigger a shell query).
     *
     * For places such as the AirPlay session-level SETUP that "must not block on a shell":
     * the BT phase of wireless CarPlay always called [bluetoothMac] first, so the cache is ready by the time we get here.
     */
    fun cachedBluetoothMac(): String? = cachedMac

    /** Device name (used by 0x4E09 DeviceInformationUpdate; matches the real iPhone, which sends the local Bluetooth name). */
    fun deviceName(context: Context): String =
        try {
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: "iPhone"
        } catch (_: Exception) {
            "iPhone"
        }

    private fun resolveViaShizuku(): String? =
        try {
            val result =
                Shell.exec(
                    "content query --uri content://settings/secure/bluetooth_address",
                    timeoutMs = 4_000,
                )
            MAC_REGEX.find(result.output)?.value?.uppercase()
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L
}
