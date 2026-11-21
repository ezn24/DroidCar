package io.github.ezn24.cardeck.neo.net

/**
 * Parses the output of `cmd wifi list-networks` (pure function, unit-tested).
 *
 * Real format (space-aligned, **no tabs**, SSID may contain spaces; one network may span several rows per security type):
 * ```
 * Network Id      SSID                         Security type
 * 0            2-502                            wpa2-psk
 * 1            MyPhone                           wpa3-sae
 * ```
 * Parsing rule: first token = netId, last token = security type, everything in between is rejoined as the SSID.
 *
 * Known limit: consecutive spaces inside an SSID are squeezed into one; car hotspot names rarely have them,
 * and in that case we would rather miss a match (no prompt to the user) than delete another network by mistake.
 */
object WifiSavedNetworks {
    /**
     * Returns the netId list whose SSID matches exactly (deduplicated; empty when nothing matches).
     * Callers may only run `forget-network` when the list is non-empty.
     */
    fun idsOf(
        listNetworksOutput: String,
        ssid: String,
    ): List<Int> =
        listNetworksOutput
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                // At least netId + SSID + security, three tokens
                if (parts.size < 3) return@mapNotNull null
                val id = parts.first().toIntOrNull() ?: return@mapNotNull null
                val name = parts.subList(1, parts.size - 1).joinToString(" ")
                if (name == ssid) id else null
            }.distinct()
            .toList()
}
