package io.github.ezn24.droidcar.net

/**
 * Builds the `cmd wifi connect-network` command used for silent Shizuku network join (pure function, unit-tested).
 *
 * Syntax (AOSP `WifiShellCommand#connect-network`):
 * ```
 * cmd wifi connect-network <ssid> open|owe|wpa2|wpa3 [<passphrase>] [-m] [-d] [-b <bssid>]
 * ```
 * Key points:
 *  - SSID/password always go through [quote] (single quotes + escaped embedded single quotes), so names with spaces/CJK/quotes
 *    are never split by the shell and there is no command injection;
 *  - Security type maps from the 0x5703 `Iap2WirelessSecurity` (0=NONE 1=WEP 2=WPA/WPA2
 *    3=WPA3 transition 4=WPA3). WEP has no matching keyword, so try `wpa2` once (same as the old fallback path);
 *    WPA3 transition mode also accepts WPA2, so it uses `wpa2` like the specifier path;
 *  - **The command's exit=0 does not mean connected**: it returns once the config is handed to WifiService, so the caller must
 *    poll `cmd wifi status` for the SSID (see `SessionController.waitForCarSsid`).
 */
object WifiConnectCommand {
    /** POSIX shell single-quoted literal (`$`/spaces/CJK/double quotes are kept verbatim). */
    fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /**
     * Full command line. Only an empty `passphrase` means an open hotspot: some head units declare
     * [securityType] 0=NONE while still sending a real password, which is then joined as WPA2.
     */
    fun build(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): String {
        val open = passphrase.isEmpty()
        val security =
            when {
                open -> "open"
                securityType == 4 -> "wpa3"
                else -> "wpa2"
            }
        val credentials = if (open) "" else " " + quote(passphrase)
        return "cmd wifi connect-network " + quote(ssid) + " " + security + credentials + AUTOJOIN_DISABLED
    }

    /**
     * `-d`: mark the written config as "no auto-join". connect-network overwrites the config of a same-named saved network, so without it
     * the user's system-level "auto-join" switch was turned back on on every connection (WinPlay measured 2026-11-20, both when the head
     * unit and when DroidCar disconnected). The car hotspot has no internet, so a system auto-join would only steal the phone's
     * normal network.
     */
    const val AUTOJOIN_DISABLED = " -d"
}
