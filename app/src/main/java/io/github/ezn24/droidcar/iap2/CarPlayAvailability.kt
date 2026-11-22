package io.github.ezn24.droidcar.iap2

internal object CarPlayAvailability {
    /**
     * 0x4300 CarPlayAvailability body. Default is a single group (group 1: available + Bluetooth MAC, measured on Audi/BMW/test bench).
     * [dualGroup]: also add group 0 (available + 24-char uppercase hex CarPlay device identifier). A real iPhone sends this
     * dual-group form to generic head units; only after the fork chengshl/cardeck (MIT) switched the Buick E5 (B233) to it did the
     * head unit keep progressing, matching the upstream Buick/Cadillac logs stuck before 0x4301. Enabled for generic head units
     * only; other models are unaffected.
     */
    fun body(
        bluetoothMac: String,
        dualGroup: Boolean,
    ): ByteArray {
        val bluetoothGroup = Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac))
        if (!dualGroup) return bluetoothGroup
        return Iap2Csm.group(0, Iap2Csm.u8(0, 1), Iap2Csm.string(1, deviceIdentifier(bluetoothMac))) + bluetoothGroup
    }

    /**
     * 24-char uppercase hex identifier derived deterministically from the Bluetooth MAC (first 12 bytes of SHA-256), stable across
     * sessions.
     */
    fun deviceIdentifier(bluetoothMac: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(("CarDeck-CarPlay:" + bluetoothMac.uppercase()).toByteArray())
            .take(12)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
