package io.github.ezn24.cardeck.neo.net

/**
 * Ordering and validation of receiver address candidates (pure function, unit-tested).
 *
 * Background: on Android it is easy to misjudge "has an address" as "has an IPv4 address". When the Audi MIB3
 * `_airplay._tcp` publishes only a link-local IPv6 (`fe80::...%wlan0`), the old code dropped it entirely
 * and fell back to `gateway:7000`; but the gateway/port of 4xxx firmware head units is not necessarily
 * `10.174.189.1:7000`, so it could never connect (the 4851 reported by a user on 2026-10-19 was exactly this).
 *
 * Ordering policy (a real iPhone / catplay also uses the addresses Bonjour provides directly):
 *  1. IPv4: the most reliable on Android;
 *  2. global IPv6;
 *  3. link-local IPv6 (needs a scope to be usable; kept as-is, the caller adds `%iface`).
 * The same address with and without scope counts as a duplicate; only the first one is kept.
 */
object EndpointHosts {
    fun isIpv4(host: String): Boolean = host.contains('.') && !host.contains(':')

    fun isIpv6(host: String): Boolean = host.contains(':')

    fun isLinkLocalIpv6(host: String): Boolean {
        val normalized = host.substringBefore('%').lowercase()
        return normalized.startsWith("fe80:") ||
            normalized == "fe80" ||
            normalized.startsWith("fe8") ||
            normalized.startsWith("fe9") ||
            normalized.startsWith("fea") ||
            normalized.startsWith("feb")
    }

    /** Strip a scope suffix such as `%wlan0` / `%3`. */
    fun stripScope(host: String): String = host.substringBefore('%')

    /**
     * Whether the receiver gave a usable address to try (IPv4 or IPv6 both count).
     * For "is the endpoint self-sufficient" / "is mDNS completion needed" checks; do not write `contains('.')` again.
     */
    fun hasUsable(addresses: List<String>): Boolean = candidates(addresses).isNotEmpty()

    /** Sort + dedupe. Empty strings / bare scopes are always dropped. */
    fun candidates(addresses: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(addresses.size)

        fun add(host: String) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return
            val base = stripScope(trimmed)
            if (base.isEmpty()) return
            if (!isIpv4(base) && !isIpv6(base)) return
            val key = base.lowercase()
            if (seen.add(key)) out += trimmed
        }
        addresses.filter(::isIpv4).forEach(::add)
        addresses.filter { isIpv6(it) && !isLinkLocalIpv6(it) }.forEach(::add)
        addresses.filter(::isLinkLocalIpv6).forEach(::add)
        return out
    }

    /**
     * Whether an address belongs to "the head unit subnet this phone is on".
     *
     * - IPv4: same /24 prefix as a local IPv4 (keeps the old filter, avoids connecting to a Mac on the same WiFi);
     * - IPv6 link-local: only valid on this link by nature, accepted directly;
     * - IPv6 global: accepted only if the first 64 bits match a local IPv6 address (rejected when no local IPv6 is available;
     *   better to fall back to the gateway than to treat an AirPlay device on a public/home network as the head unit).
     */
    fun isSameNetwork(
        host: String,
        localIpv4: String?,
        localIpv6: List<String> = emptyList(),
    ): Boolean {
        if (isIpv4(host)) {
            val prefix = localIpv4?.substringBeforeLast('.')?.plus(".")
            return prefix == null || host.startsWith(prefix)
        }
        if (!isIpv6(host)) return false
        if (isLinkLocalIpv6(host)) return true
        val candidate = ipv6Prefix64(host) ?: return false
        return localIpv6.any { ipv6Prefix64(it) == candidate }
    }

    /** Where an adapter-mode AirPlay host came from (logged so a wrong pick can be traced). */
    enum class AdapterHostSource { MDNS, DHCP_SERVER, CTRL_INVITE, GATEWAY }

    data class AdapterHost(
        val host: String,
        val source: AdapterHostSource,
    )

    /**
     * Picks the wireless-adapter AirPlay host. The smartBox Wi-Fi is router-less (DHCP server
     * 192.168.1.101, no default route) and its SRV target is the malformed `(none).local.`
     * (unapple-phone NEXT_STEPS "Local smartBox field result"), so mDNS may yield no address and
     * the gateway is usually null. Order: mDNS address → DHCP server → ctrl invite peer → gateway.
     * Each candidate must be a real IP literal (not a host name like `(none)`, unspecified or loopback).
     */
    fun chooseAdapterHost(
        mdnsAddress: String?,
        dhcpServer: String?,
        invitePeer: String? = null,
        gateway: String? = null,
    ): AdapterHost? =
        listOf(
            mdnsAddress to AdapterHostSource.MDNS,
            dhcpServer to AdapterHostSource.DHCP_SERVER,
            invitePeer to AdapterHostSource.CTRL_INVITE,
            gateway to AdapterHostSource.GATEWAY,
        ).firstNotNullOfOrNull { (host, source) ->
            host?.trim()?.takeIf(::isUsableAdapterAddress)?.let { AdapterHost(it, source) }
        }

    /** Strict IP-literal check (unlike [isIpv4], which accepts any dotted text such as `(none).local.`). */
    fun isUsableAdapterAddress(host: String?): Boolean {
        val base = host?.trim()?.let(::stripScope) ?: return false
        if (isIpv4Literal(base)) return base != "0.0.0.0" && !base.startsWith("127.")
        val groups = expandIpv6(base) ?: return false
        val unspecifiedOrLoopback = groups.dropLast(1).all { it == "0000" } && (groups.last() == "0000" || groups.last() == "0001")
        return !unspecifiedOrLoopback
    }

    /** SRV target names that can't resolve (the smartBox publishes `(none).local.`). */
    fun isMalformedSrvTarget(hostName: String?): Boolean {
        val name = hostName?.trim()?.trimEnd('.')?.lowercase() ?: return true
        return name.isEmpty() || name.startsWith("(none)")
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 &&
            parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
    }

    /** First 64 bits of an IPv6 address (first 4 hextets, compared after expansion; null on parse failure). */
    fun ipv6Prefix64(host: String): String? {
        val groups = expandIpv6(stripScope(host)) ?: return null
        return groups.take(4).joinToString(":")
    }

    /** Expand IPv6 text into 8 groups (handling `::` compression), returning lowercase hextets. */
    private fun expandIpv6(host: String): List<String>? {
        if (!host.contains(':')) return null
        val doubleColon = host.indexOf("::")
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
            tail = host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
        } else {
            head = host.split(':')
            tail = emptyList()
        }
        if (head.size + tail.size > 8) return null
        val fill = 8 - head.size - tail.size
        val groups = ArrayList<String>(8)
        groups += head
        repeat(fill) { groups += "0" }
        groups += tail
        if (groups.size != 8) return null
        if (groups.any { it.length > 4 || it.any { c -> c.digitToIntOrNull(16) == null } }) return null
        return groups.map { it.lowercase().padStart(4, '0') }
    }

    /**
     * Whether the mDNS-discovered `_airplay._tcp` is clearly not the same device as the Bluetooth-provided endpoint: port or
     * public key both known and different.
     * WinPlay bench measured 2026-11-20: 0x4301 = 192.168.1.117:17000, while another service on the same subnet advertised
     * 192.168.1.177:7000; the old logic merged .177 into the address list and kept port 17000, which could never connect
     * and burned the pairing retry rounds on the wrong address. When port/key are unknown we still merge (absence is not refutation).
     * Returns the reason for the mismatch; null = safe to merge.
     */
    fun mdnsMismatch(
        endpointPort: Int,
        endpointPublicKey: String?,
        mdnsPort: Int,
        mdnsPublicKey: String?,
    ): String? =
        when {
            endpointPort > 0 && mdnsPort > 0 && mdnsPort != endpointPort ->
                "port $mdnsPort != the $endpointPort given by the head unit"
            endpointPublicKey != null &&
                mdnsPublicKey != null &&
                !endpointPublicKey.equals(mdnsPublicKey, ignoreCase = true) -> "public keys differ"
            else -> null
        }
}
