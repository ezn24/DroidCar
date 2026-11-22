package io.github.ezn24.droidcar.bt

import android.content.Context

/**
 * "Auto-connect when getting in the car" switch (default on): whether to start a session automatically when the car Bluetooth ACL connects.
 *
 * Shown with `DisplayPrefs.screenOffOnConnect` in the Settings -> Display panel;
 * read by [CarBluetoothReceiver] on every ACL broadcast (the process may have just been cold-started, so nothing is cached).
 */
object AutoConnectPrefs {
    private const val PREFS = "settings"
    private const val KEY = "auto_connect_on_bt"

    /** Default on: this is the "open the door and connect" switch; only disabled when the user turns it off. */
    const val DEFAULT = true

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
