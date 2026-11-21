package io.github.ezn24.cardeck.neo.bt

import android.content.Context

/**
 * "Target head unit" selection preference (2026-10-16):
 *
 * The user may have paired several CarPlay head units (a real car + a test bench). With no target set, both links treat
 * "any paired device" as a candidate: the outbound client tries each one and the inbound server accepts anyone, so which
 * unit connects depends on who answers first (non-deterministic). Once a target is chosen we connect **only to that device**
 * and accept only its incoming connection, avoiding a wrong head unit (typical: one of two cars at home).
 *
 * Stores address + name snapshot: the address is the criterion (case-insensitive compare), the name is only for UI display
 * (a device may be renamed; the display uses the snapshot, just pick again if needed).
 */
object TargetDevicePrefs {
    private const val PREFS = "target_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Target(
        val address: String,
        val name: String,
    )

    /** null = not set (auto: outbound tries all candidates, inbound accepts any device). */
    fun get(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Target(address = address, name = prefs.getString(KEY_NAME, null) ?: address)
    }

    fun set(
        context: Context,
        target: Target?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target == null) {
            prefs.edit().clear().apply()
        } else {
            prefs
                .edit()
                .putString(KEY_ADDRESS, target.address)
                .putString(KEY_NAME, target.name)
                .apply()
        }
    }

    fun matches(
        target: Target,
        address: String?,
    ): Boolean = address != null && address.equals(target.address, ignoreCase = true)
}
