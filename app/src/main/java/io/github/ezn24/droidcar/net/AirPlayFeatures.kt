package io.github.ezn24.droidcar.net

/**
 * Parse of the `_airplay._tcp` TXT `features` bitmap (shaped like `0x44540380,0x61`).
 *
 * Low word first: `low 32 bits,high 32 bits`, merged into 64 bits. AirPlay receivers use bit38
 * `HK_PAIRING_AND_ENCRYPT` to mean "supports HomeKit pairing/encryption"; old head units
 * (CarPlay 210.81 era) do not declare it and only accept plaintext `et=NONE` sessions.
 *
 * Any parse failure returns null (unknown) and callers keep the old behavior;
 * a parse failure must never be treated as "pairing unsupported", which would downgrade healthy head units to plaintext.
 */
object AirPlayFeatures {
    /** Supports /pair-setup + /pair-verify (AirPlayFeature::HK_PAIRING_AND_ENCRYPT). */
    const val HK_PAIRING_AND_ENCRYPT: Long = 1L shl 38

    /** Supports screen mirroring. */
    const val SCREEN: Long = 1L shl 7

    /** Car control (CarPlay). */
    const val CARPLAY_CONTROL: Long = 1L shl 37

    fun parse(txt: String?): Long? {
        val text = txt?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val low = parseHex(parts[0]) ?: return null
        val high = if (parts.size == 2) parseHex(parts[1]) ?: return null else 0L
        if (low < 0 || high < 0) return null
        return (high shl 32) or low
    }

    /** Whether the receiver declares HomeKit pairing/encryption support; null = TXT missing or unparsable. */
    fun supportsPairing(features: Long?): Boolean? = features?.let { (it and HK_PAIRING_AND_ENCRYPT) != 0L }

    private fun parseHex(value: String): Long? {
        val cleaned = value.removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull(16)
    }
}
