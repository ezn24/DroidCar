package io.github.ezn24.cardeck.neo.bridge

import android.content.Context
import java.util.UUID

/** Remote debug bridge preferences (separate file "bridge", not mixed with business settings). */
object BridgePrefs {
    private const val FILE = "bridge"
    private const val KEY_URL = "serverUrl"
    private const val KEY_CODE = "pairCode"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_AUTO = "autoConnect"
    private const val KEY_RESUME = "resumePending"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_URL, "") ?: ""

    fun pairCode(context: Context): String = prefs(context).getString(KEY_CODE, "") ?: ""

    fun save(
        context: Context,
        url: String,
        code: String,
    ) {
        prefs(context)
            .edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_CODE, code.trim())
            .apply()
    }

    /** Stable device identifier of this phone (persisted after first generation; the server tells devices apart by it). */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().substring(0, 8)
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** Auto-connect the bridge when the app starts (set after the user taps "Connect" on the settings page; cleared by "Disconnect"). */
    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoConnect(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    /** Set before reinstall: on the first launch of the new version, ask "Continue into debug mode?". */
    fun resumePending(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME, false)

    // ---- Delta baseline sha cache (keyed by version: naturally invalidated and recomputed after installing a new package) ----

    fun baseApkSha(
        context: Context,
        version: String,
    ): String? {
        val p = prefs(context)
        if (p.getString("baseApkShaVer", "") != version) return null
        return p.getString("baseApkSha", null)
    }

    fun setBaseApkSha(
        context: Context,
        version: String,
        sha: String,
    ) {
        prefs(context)
            .edit()
            .putString("baseApkShaVer", version)
            .putString("baseApkSha", sha)
            .apply()
    }

    fun setResumePending(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_RESUME, value).apply()
    }
}
