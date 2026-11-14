package dev.javahost.core.net

/** Coarse category of the network an address belongs to. */
enum class LanKind { WIFI, ETHERNET, HOTSPOT, OTHER }

/**
 * Snapshot of one network interface address, deliberately decoupled from
 * `java.net.NetworkInterface` so the selection rules stay pure JVM and
 * unit-testable on a plain host.
 *
 * @property name interface name reported by the OS, e.g. `wlan0` or `eth0`.
 * @property address IPv4 address as a dotted quad, e.g. `192.168.1.50`.
 * @property isUp whether the interface is up and running.
 * @property isLoopback whether the interface is the loopback interface.
 * @property isPointToPoint whether the interface is point-to-point (e.g. a tunnel).
 */
data class LanInterface(
    val name: String,
    val address: String,
    val isUp: Boolean = true,
    val isLoopback: Boolean = false,
    val isPointToPoint: Boolean = false
)

/** A LAN-reachable IPv4 address on a specific interface, ready to show to the user. */
data class LanEndpoint(
    val address: String,
    val interfaceName: String,
    val kind: LanKind,
    val label: String
) {
    /** `address:port`, e.g. `192.168.1.50:4567`. */
    fun hostPort(port: Int): String = LanAddressSelector.hostPort(address, port)

    /** `http://address:port/`, e.g. `http://192.168.1.50:4567/`. */
    fun httpUrl(port: Int): String = LanAddressSelector.httpUrl(address, port)
}

/**
 * Deterministic, JVM-only LAN address selection.
 *
 * Only private IPv4 addresses on real LAN interfaces are usable from another
 * device. Cellular (`ccmni*`, `rmnet*`, ...) and VPN/virtual (`tun*`, `wg*`,
 * ...) interfaces are rejected even when they carry a site-local address,
 * because that address is either unreachable from the local network or
 * belongs to a different network entirely.
 */
object LanAddressSelector {

    /** Smallest TCP/UDP port accepted by the LAN helpers. */
    const val MIN_PORT = 1

    /** Largest TCP/UDP port accepted by the LAN helpers. */
    const val MAX_PORT = 65535

    /** Strict dotted-quad IPv4 pattern from the LAN contract (no whitespace allowed). */
    private val IPV4_PATTERN =
        Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    /** Interface-name prefixes used by Android cellular data interfaces. */
    private val CELLULAR_PREFIXES =
        listOf("ccmni", "rmnet", "seth", "pdp", "clat")

    /** Interface-name prefixes used by VPNs and other virtual interfaces. */
    private val VPN_PREFIXES = listOf(
        "tun", "tap", "ppp", "wg", "ipsec", "v4-", "ip6tnl", "sit",
        "dummy", "ifb", "p2p", "swlan", "bond"
    )

    /** Priority of a kind when sorting endpoints: Wi-Fi, then Ethernet, then hotspot, then rest. */
    private fun kindPriority(kind: LanKind): Int = when (kind) {
        LanKind.WIFI -> 0
        LanKind.ETHERNET -> 1
        LanKind.HOTSPOT -> 2
        LanKind.OTHER -> 3
    }

    /**
     * True only for a strict dotted-quad IPv4 address inside 10.0.0.0/8,
     * 172.16.0.0/12 or 192.168.0.0/16. Anything malformed or public is false.
     */
    fun isPrivateIPv4(address: String): Boolean {
        val octets = parseIPv4(address) ?: return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    /** True only for a strict dotted-quad IPv4 address in 127.0.0.0/8. */
    fun isLoopbackIPv4(address: String): Boolean {
        val octets = parseIPv4(address) ?: return false
        return octets[0] == 127
    }

    /** True for Android cellular data interface names (`ccmni*`, `rmnet*`, `seth*`, `pdp*`, `clat*`). */
    fun isCellularInterface(name: String): Boolean {
        val normalised = name.trim().lowercase()
        return CELLULAR_PREFIXES.any { normalised.startsWith(it) }
    }

    /** True for VPN/tunnel and other virtual interface names (`tun*`, `wg*`, `p2p*`, ...). */
    fun isVpnOrVirtualInterface(name: String): Boolean {
        val normalised = name.trim().lowercase()
        return VPN_PREFIXES.any { normalised.startsWith(it) }
    }

    /** Classifies an interface name into a [LanKind] (eth/usb/rndis = Ethernet, wlan = Wi-Fi, ap/softap = hotspot). */
    fun classify(name: String): LanKind {
        val normalised = name.trim().lowercase()
        return when {
            normalised.startsWith("eth") ||
                normalised.startsWith("usb") ||
                normalised.startsWith("rndis") -> LanKind.ETHERNET
            normalised.startsWith("wlan") -> LanKind.WIFI
            normalised.startsWith("ap") || normalised.startsWith("softap") -> LanKind.HOTSPOT
            else -> LanKind.OTHER
        }
    }

    /** Human-readable label for a [LanKind]: "Wi-Fi", "Ethernet", "Hotspot" or "Network". */
    fun label(kind: LanKind): String = when (kind) {
        LanKind.WIFI -> "Wi-Fi"
        LanKind.ETHERNET -> "Ethernet"
        LanKind.HOTSPOT -> "Hotspot"
        LanKind.OTHER -> "Network"
    }

    /** Formats `http://address:port/`, e.g. `http://192.168.1.50:4567/`. */
    fun httpUrl(address: String, port: Int): String = "http://$address:$port/"

    /** Formats `address:port`, e.g. `192.168.1.50:4567`. */
    fun hostPort(address: String, port: Int): String = "$address:$port"

    /**
     * Selects the usable LAN endpoints from an interface snapshot, in display order.
     *
     * Keeps only interfaces that are up, not loopback, not point-to-point, carry a
     * strict private IPv4 address and are not cellular/VPN/virtual; deduplicates by
     * address keeping the highest-priority entry; then sorts by kind priority
     * (Wi-Fi, Ethernet, hotspot, other), interface name and address. Never throws.
     */
    fun select(interfaces: List<LanInterface>): List<LanEndpoint> {
        val endpoints = interfaces
            .asSequence()
            .filter { isUsable(it) }
            .map { iface ->
                val kind = classify(iface.name)
                LanEndpoint(iface.address, iface.name, kind, label(kind))
            }
            .sortedWith(ENDPOINT_ORDER)
            .toList()
        return endpoints.distinctBy { it.address }
    }

    /** Display order for endpoints: kind priority, then interface name, then address. */
    private val ENDPOINT_ORDER: Comparator<LanEndpoint> = compareBy(
        { kindPriority(it.kind) },
        { it.interfaceName },
        { it.address }
    )

    /** One interface passes the contract's keep-only rules. */
    private fun isUsable(iface: LanInterface): Boolean {
        if (iface.name.isBlank()) return false
        if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) return false
        if (isCellularInterface(iface.name) || isVpnOrVirtualInterface(iface.name)) return false
        return isPrivateIPv4(iface.address)
    }

    /** Parses a strict dotted-quad IPv4 string into four octets, or null when malformed. */
    private fun parseIPv4(address: String): IntArray? {
        if (!IPV4_PATTERN.matches(address)) return null
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in 0 until 4) {
            octets[index] = parts[index].toIntOrNull() ?: return null
        }
        return octets
    }
}
