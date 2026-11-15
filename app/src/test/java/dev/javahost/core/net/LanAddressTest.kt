package dev.javahost.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [LanAddressSelector] and the [LanEndpoint] helpers. No
 * Android classes and no real network interfaces are touched, so the rules are
 * verified deterministically on any host.
 */
class LanAddressTest {

    private fun iface(
        name: String,
        address: String,
        up: Boolean = true,
        loopback: Boolean = false,
        pointToPoint: Boolean = false
    ) = LanInterface(
        name = name,
        address = address,
        isUp = up,
        isLoopback = loopback,
        isPointToPoint = pointToPoint
    )

    // ------------------------------------------------------------------
    // Private IPv4 ranges, including both ends of every range
    // ------------------------------------------------------------------

    @Test
    fun `10 slash 8 range includes both boundaries`() {
        assertTrue(LanAddressSelector.isPrivateIPv4("10.0.0.0"))
        assertTrue(LanAddressSelector.isPrivateIPv4("10.0.0.1"))
        assertTrue(LanAddressSelector.isPrivateIPv4("10.128.64.32"))
        assertTrue(LanAddressSelector.isPrivateIPv4("10.255.255.255"))
    }

    @Test
    fun `172 dot 16 slash 12 range includes both boundaries`() {
        assertTrue(LanAddressSelector.isPrivateIPv4("172.16.0.0"))
        assertTrue(LanAddressSelector.isPrivateIPv4("172.16.0.1"))
        assertTrue(LanAddressSelector.isPrivateIPv4("172.20.10.2"))
        assertTrue(LanAddressSelector.isPrivateIPv4("172.31.255.255"))
    }

    @Test
    fun `172 addresses just outside the slash 12 range are not private`() {
        assertFalse(LanAddressSelector.isPrivateIPv4("172.0.0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("172.15.255.255"))
        assertFalse(LanAddressSelector.isPrivateIPv4("172.32.0.0"))
        assertFalse(LanAddressSelector.isPrivateIPv4("172.32.0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("172.255.255.255"))
    }

    @Test
    fun `192 dot 168 slash 16 range includes both boundaries`() {
        assertTrue(LanAddressSelector.isPrivateIPv4("192.168.0.0"))
        assertTrue(LanAddressSelector.isPrivateIPv4("192.168.0.1"))
        assertTrue(LanAddressSelector.isPrivateIPv4("192.168.1.50"))
        assertTrue(LanAddressSelector.isPrivateIPv4("192.168.255.255"))
    }

    @Test
    fun `192 addresses just outside the slash 16 range are not private`() {
        assertFalse(LanAddressSelector.isPrivateIPv4("192.167.255.255"))
        assertFalse(LanAddressSelector.isPrivateIPv4("192.169.0.0"))
        assertFalse(LanAddressSelector.isPrivateIPv4("192.200.1.1"))
    }

    @Test
    fun `public and special-purpose addresses are rejected`() {
        assertFalse(LanAddressSelector.isPrivateIPv4("8.8.8.8"))
        assertFalse(LanAddressSelector.isPrivateIPv4("1.1.1.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("100.64.0.1")) // CGNAT, not home LAN
        assertFalse(LanAddressSelector.isPrivateIPv4("169.254.1.1")) // link-local
        assertFalse(LanAddressSelector.isPrivateIPv4("127.0.0.1")) // loopback
        assertFalse(LanAddressSelector.isPrivateIPv4("0.0.0.0"))
        assertFalse(LanAddressSelector.isPrivateIPv4("224.0.2.60")) // multicast
        assertFalse(LanAddressSelector.isPrivateIPv4("255.255.255.255"))
        assertFalse(LanAddressSelector.isPrivateIPv4("11.0.0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("9.255.255.255"))
    }

    // ------------------------------------------------------------------
    // Strict dotted-quad parsing
    // ------------------------------------------------------------------

    @Test
    fun `malformed dotted quads are rejected`() {
        assertFalse(LanAddressSelector.isPrivateIPv4(""))
        assertFalse(LanAddressSelector.isPrivateIPv4(" "))
        assertFalse(LanAddressSelector.isPrivateIPv4(" 10.0.0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1 "))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1\n"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1.5"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.256"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.-1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1a"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1:80"))
        assertFalse(LanAddressSelector.isPrivateIPv4("abc"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10..0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4(".10.0.0.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.1."))
        assertFalse(LanAddressSelector.isPrivateIPv4("10.0.0.0x1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("10,0,0,1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("192.168.001.1")) // 3-digit octet with leading zeros
    }

    @Test
    fun `contract regex allows a single leading zero in 1-2 digit octets`() {
        // The frozen contract pattern is 1?\d?\d, which accepts e.g. "01"/"09"
        // while still rejecting three-digit values such as "001" or "256".
        assertTrue(LanAddressSelector.isPrivateIPv4("192.168.01.1"))
        assertFalse(LanAddressSelector.isPrivateIPv4("010.0.0.1")) // "010" is three digits
    }

    // ------------------------------------------------------------------
    // Loopback helpers
    // ------------------------------------------------------------------

    @Test
    fun `loopback 127 slash 8 is detected strictly`() {
        assertTrue(LanAddressSelector.isLoopbackIPv4("127.0.0.1"))
        assertTrue(LanAddressSelector.isLoopbackIPv4("127.0.0.0"))
        assertTrue(LanAddressSelector.isLoopbackIPv4("127.255.255.255"))
        assertFalse(LanAddressSelector.isLoopbackIPv4("126.0.0.1"))
        assertFalse(LanAddressSelector.isLoopbackIPv4("128.0.0.1"))
        assertFalse(LanAddressSelector.isLoopbackIPv4("10.0.0.1"))
        assertFalse(LanAddressSelector.isLoopbackIPv4("127.0.0"))
        assertFalse(LanAddressSelector.isLoopbackIPv4("127.0.0.1.1"))
        assertFalse(LanAddressSelector.isLoopbackIPv4(" 127.0.0.1"))
        assertFalse(LanAddressSelector.isLoopbackIPv4(""))
    }

    // ------------------------------------------------------------------
    // Cellular / VPN / virtual interface name detection
    // ------------------------------------------------------------------

    @Test
    fun `android cellular interfaces are recognised`() {
        val names = listOf("ccmni0", "ccmni1", "rmnet0", "rmnet1", "rmnet_data0", "seth0", "seth1", "pdp0", "clat4")
        for (name in names) {
            assertTrue("$name must be cellular", LanAddressSelector.isCellularInterface(name))
        }
        assertTrue(LanAddressSelector.isCellularInterface("CCMNI0"))
    }

    @Test
    fun `lan interfaces are not mistaken for cellular`() {
        val names = listOf("wlan0", "wlan1", "eth0", "usb0", "ap0", "softap0", "tun0", "wg0", "", "swlan0")
        for (name in names) {
            assertFalse("$name must not be cellular", LanAddressSelector.isCellularInterface(name))
        }
    }

    @Test
    fun `vpn and virtual interfaces are recognised`() {
        val names = listOf(
            "tun0", "tap0", "ppp0", "wg0", "ipsec0", "v4-rmnet0",
            "ip6tnl0", "sit0", "dummy0", "ifb0", "p2p0", "swlan0", "bond0"
        )
        for (name in names) {
            assertTrue("$name must be vpn/virtual", LanAddressSelector.isVpnOrVirtualInterface(name))
        }
        assertTrue(LanAddressSelector.isVpnOrVirtualInterface("TUN0"))
    }

    @Test
    fun `physical lan interfaces are not mistaken for vpn or virtual`() {
        val names = listOf("wlan0", "wlan1", "eth0", "usb0", "rndis0", "ap0", "softap0", "ccmni0", "rmnet0", "")
        for (name in names) {
            assertFalse("$name must not be vpn/virtual", LanAddressSelector.isVpnOrVirtualInterface(name))
        }
    }

    // ------------------------------------------------------------------
    // Classification and labels
    // ------------------------------------------------------------------

    @Test
    fun `interface names classify into the expected kind`() {
        assertEquals(LanKind.WIFI, LanAddressSelector.classify("wlan0"))
        assertEquals(LanKind.WIFI, LanAddressSelector.classify("wlan1"))
        assertEquals(LanKind.ETHERNET, LanAddressSelector.classify("eth0"))
        assertEquals(LanKind.ETHERNET, LanAddressSelector.classify("usb0"))
        assertEquals(LanKind.ETHERNET, LanAddressSelector.classify("rndis0"))
        assertEquals(LanKind.HOTSPOT, LanAddressSelector.classify("ap0"))
        assertEquals(LanKind.HOTSPOT, LanAddressSelector.classify("ap1"))
        assertEquals(LanKind.HOTSPOT, LanAddressSelector.classify("softap0"))
        assertEquals(LanKind.OTHER, LanAddressSelector.classify("ccmni0"))
        assertEquals(LanKind.OTHER, LanAddressSelector.classify("tun0"))
        assertEquals(LanKind.OTHER, LanAddressSelector.classify("p2p0"))
        assertEquals(LanKind.OTHER, LanAddressSelector.classify("bond0"))
        assertEquals(LanKind.OTHER, LanAddressSelector.classify(""))
    }

    @Test
    fun `kinds map to the contract labels`() {
        assertEquals("Wi-Fi", LanAddressSelector.label(LanKind.WIFI))
        assertEquals("Ethernet", LanAddressSelector.label(LanKind.ETHERNET))
        assertEquals("Hotspot", LanAddressSelector.label(LanKind.HOTSPOT))
        assertEquals("Network", LanAddressSelector.label(LanKind.OTHER))
    }

    // ------------------------------------------------------------------
    // Address formatting
    // ------------------------------------------------------------------

    @Test
    fun `hostPort and httpUrl format correctly`() {
        assertEquals("192.168.1.50:4567", LanAddressSelector.hostPort("192.168.1.50", 4567))
        assertEquals("http://192.168.1.50:4567/", LanAddressSelector.httpUrl("192.168.1.50", 4567))
        assertEquals("10.0.0.1:1", LanAddressSelector.hostPort("10.0.0.1", LanAddressSelector.MIN_PORT))
        assertEquals(
            "http://10.0.0.1:65535/",
            LanAddressSelector.httpUrl("10.0.0.1", LanAddressSelector.MAX_PORT)
        )
    }

    @Test
    fun `endpoint delegates formatting to the selector`() {
        val endpoint = LanEndpoint("192.168.1.50", "wlan0", LanKind.WIFI, "Wi-Fi")
        assertEquals("192.168.1.50:25565", endpoint.hostPort(25565))
        assertEquals("http://192.168.1.50:25565/", endpoint.httpUrl(25565))
    }

    // ------------------------------------------------------------------
    // Selection: filtering
    // ------------------------------------------------------------------

    @Test
    fun `empty input produces no endpoints`() {
        assertTrue(LanAddressSelector.select(emptyList()).isEmpty())
    }

    @Test
    fun `interfaces that cannot be reached from the lan are filtered out`() {
        val interfaces = listOf(
            iface("", "192.168.1.2"), // blank name
            iface("   ", "192.168.1.2"), // whitespace-only name
            iface("wlan0", "192.168.1.2", up = false), // down
            iface("wlan0", "192.168.1.2", loopback = true), // loopback flag
            iface("wlan0", "192.168.1.2", pointToPoint = true), // point-to-point
            iface("wlan0", "8.8.8.8"), // public
            iface("wlan0", "192.168.1.256"), // malformed
            iface("wlan0", "192.168.1"), // malformed
            iface("ccmni0", "10.0.0.2"), // cellular
            iface("rmnet0", "10.0.0.2"), // cellular
            iface("tun0", "10.8.0.2"), // vpn
            iface("wg0", "192.168.1.9"), // vpn
            iface("p2p0", "192.168.1.9"), // virtual
            iface("swlan0", "192.168.1.9"), // virtual
            iface("bond0", "192.168.1.9"), // virtual
            iface("dummy0", "192.168.1.9") // virtual
        )
        assertTrue(LanAddressSelector.select(interfaces).isEmpty())
    }

    @Test
    fun `usable wifi interface becomes a labelled endpoint`() {
        val result = LanAddressSelector.select(listOf(iface("wlan0", "192.168.1.50")))
        assertEquals(1, result.size)
        assertEquals(LanEndpoint("192.168.1.50", "wlan0", LanKind.WIFI, "Wi-Fi"), result.single())
    }

    @Test
    fun `hotspot ap interface is kept with hotspot kind`() {
        val result = LanAddressSelector.select(listOf(iface("ap0", "192.168.43.1")))
        assertEquals(1, result.size)
        assertEquals(LanKind.HOTSPOT, result.single().kind)
        assertEquals("Hotspot", result.single().label)
    }

    @Test
    fun `ether usb and rndis interfaces are kept as ethernet`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("eth0", "192.168.1.10"),
                iface("usb0", "192.168.1.11"),
                iface("rndis0", "192.168.1.12")
            )
        )
        assertEquals(3, result.size)
        assertTrue(result.all { it.kind == LanKind.ETHERNET })
        assertTrue(result.all { it.label == "Ethernet" })
    }

    @Test
    fun `other non cellular non virtual interfaces are kept as other`() {
        val result = LanAddressSelector.select(listOf(iface("xyz0", "192.168.1.10")))
        assertEquals(1, result.size)
        assertEquals(LanKind.OTHER, result.single().kind)
        assertEquals("Network", result.single().label)
    }

    // ------------------------------------------------------------------
    // Selection: dedupe
    // ------------------------------------------------------------------

    @Test
    fun `duplicate address keeps wifi over ethernet`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("eth0", "192.168.1.50"),
                iface("wlan0", "192.168.1.50")
            )
        )
        assertEquals(1, result.size)
        assertEquals(LanKind.WIFI, result.single().kind)
        assertEquals("wlan0", result.single().interfaceName)
    }

    @Test
    fun `duplicate address dedupe is independent of input order`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("wlan0", "192.168.1.50"),
                iface("eth0", "192.168.1.50")
            )
        )
        assertEquals(1, result.size)
        assertEquals("wlan0", result.single().interfaceName)
    }

    @Test
    fun `duplicate address of same kind keeps the lexicographically first name`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("wlan1", "192.168.1.50"),
                iface("wlan0", "192.168.1.50")
            )
        )
        assertEquals(1, result.size)
        assertEquals("wlan0", result.single().interfaceName)
    }

    @Test
    fun `distinct addresses are all kept`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("wlan0", "192.168.1.10"),
                iface("wlan0", "192.168.1.11"),
                iface("eth0", "10.0.0.5")
            )
        )
        assertEquals(3, result.size)
        assertEquals(setOf("192.168.1.10", "192.168.1.11", "10.0.0.5"), result.map { it.address }.toSet())
    }

    // ------------------------------------------------------------------
    // Selection: ordering
    // ------------------------------------------------------------------

    @Test
    fun `endpoints order wifi then ethernet then hotspot then other`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("xyz0", "192.168.1.40"), // OTHER
                iface("ap0", "192.168.1.30"), // HOTSPOT
                iface("eth0", "192.168.1.20"), // ETHERNET
                iface("wlan0", "192.168.1.10") // WIFI
            )
        )
        assertEquals(
            listOf(LanKind.WIFI, LanKind.ETHERNET, LanKind.HOTSPOT, LanKind.OTHER),
            result.map { it.kind }
        )
        assertEquals(listOf("wlan0", "eth0", "ap0", "xyz0"), result.map { it.interfaceName })
        assertEquals(listOf("Wi-Fi", "Ethernet", "Hotspot", "Network"), result.map { it.label })
    }

    @Test
    fun `ordering within one kind breaks ties by interface name`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("eth1", "192.168.1.11"),
                iface("eth0", "192.168.1.10"),
                iface("usb0", "192.168.1.12")
            )
        )
        assertEquals(listOf("eth0", "eth1", "usb0"), result.map { it.interfaceName })
    }

    @Test
    fun `ordering within one interface breaks ties by address`() {
        val result = LanAddressSelector.select(
            listOf(
                iface("wlan0", "192.168.1.9"),
                iface("wlan0", "192.168.1.2"),
                iface("wlan0", "192.168.1.10")
            )
        )
        // Address ties are broken by natural String order, so "1.10" sorts before "1.2".
        assertEquals(listOf("192.168.1.10", "192.168.1.2", "192.168.1.9"), result.map { it.address })
    }

    @Test
    fun `mixed snapshot produces stable fully ordered endpoints`() {
        val interfaces = listOf(
            iface("rmnet0", "10.0.0.2"), // cellular -> dropped
            iface("eth0", "192.168.1.20"),
            iface("tun0", "10.8.0.2"), // vpn -> dropped
            iface("wlan0", "192.168.1.10"),
            iface("ap0", "192.168.43.1"),
            iface("wlan1", "192.168.1.10"), // duplicate of wlan0 address
            iface("wlan0", "169.254.1.1"), // link-local -> dropped
            iface("usb0", "192.168.1.20") // duplicate of eth0 address
        )
        val result = LanAddressSelector.select(interfaces)
        assertEquals(3, result.size)
        assertEquals(
            listOf(
                LanEndpoint("192.168.1.10", "wlan0", LanKind.WIFI, "Wi-Fi"),
                LanEndpoint("192.168.1.20", "eth0", LanKind.ETHERNET, "Ethernet"),
                LanEndpoint("192.168.43.1", "ap0", LanKind.HOTSPOT, "Hotspot")
            ),
            result
        )
    }

    // ------------------------------------------------------------------
    // Subnet broadcast for the Bedrock LAN beacon (1.3.0 additions)
    // ------------------------------------------------------------------

    @Test
    fun `subnet broadcast for common home network prefixes`() {
        assertEquals("10.255.255.255", LanAddressSelector.subnetBroadcast("10.20.30.40", 8))
        assertEquals("172.16.255.255", LanAddressSelector.subnetBroadcast("172.16.5.4", 16))
        assertEquals("192.168.1.255", LanAddressSelector.subnetBroadcast("192.168.1.50", 24))
        assertEquals("192.168.1.127", LanAddressSelector.subnetBroadcast("192.168.1.50", 25))
        assertEquals("192.168.1.7", LanAddressSelector.subnetBroadcast("192.168.1.5", 30))
        assertEquals("192.168.1.5", LanAddressSelector.subnetBroadcast("192.168.1.5", 31))
    }

    @Test
    fun `subnet broadcast of a slash 32 is the address itself`() {
        assertEquals("192.168.1.50", LanAddressSelector.subnetBroadcast("192.168.1.50", 32))
        assertEquals("10.0.0.1", LanAddressSelector.subnetBroadcast("10.0.0.1", 32))
    }

    @Test
    fun `subnet broadcast handles edge prefixes`() {
        assertEquals("127.255.255.255", LanAddressSelector.subnetBroadcast("0.0.0.0", 1))
        assertEquals("255.255.255.255", LanAddressSelector.subnetBroadcast("128.0.0.1", 1))
        assertEquals("255.255.255.255", LanAddressSelector.subnetBroadcast("255.255.255.255", 24))
        assertEquals("192.168.255.255", LanAddressSelector.subnetBroadcast("192.168.0.0", 16))
    }

    @Test
    fun `subnet broadcast rejects out of range prefixes`() {
        assertNull(LanAddressSelector.subnetBroadcast("192.168.1.50", 0))
        assertNull(LanAddressSelector.subnetBroadcast("192.168.1.50", -1))
        assertNull(LanAddressSelector.subnetBroadcast("192.168.1.50", 33))
        assertNull(LanAddressSelector.subnetBroadcast("192.168.1.50", Int.MAX_VALUE))
    }

    @Test
    fun `subnet broadcast rejects malformed addresses`() {
        for (address in listOf(
            "",
            " ",
            "192.168.1",
            "192.168.1.5.6",
            "192.168.1.256",
            "192.168.1.-1",
            "192.168.1.5a",
            "192.168.1.5:80",
            "192.168.1.5/24",
            " 192.168.1.5",
            "192.168.1.5 ",
            "abc",
            "10..0.1"
        )) {
            assertNull("\"$address\" must be rejected", LanAddressSelector.subnetBroadcast(address, 24))
        }
    }

    // ------------------------------------------------------------------
    // Prefix-length propagation (1.3.0 additions)
    // ------------------------------------------------------------------

    private fun ifaceWithPrefix(name: String, address: String, prefixLength: Int) =
        LanInterface(
            name = name,
            address = address,
            isUp = true,
            isLoopback = false,
            isPointToPoint = false,
            prefixLength = prefixLength
        )

    @Test
    fun `prefix length defaults to 24`() {
        assertEquals(24, LanInterface("wlan0", "192.168.1.50").prefixLength)
        assertEquals(24, LanEndpoint("192.168.1.50", "wlan0", LanKind.WIFI, "Wi-Fi").prefixLength)
    }

    @Test
    fun `selection copies the interface prefix length into the endpoint`() {
        val result = LanAddressSelector.select(
            listOf(ifaceWithPrefix("wlan0", "192.168.1.50", prefixLength = 16))
        )
        assertEquals(1, result.size)
        assertEquals(16, result.single().prefixLength)
    }

    @Test
    fun `selection keeps the default prefix length when the interface has none`() {
        val result = LanAddressSelector.select(listOf(iface("wlan0", "192.168.1.50")))
        assertEquals(24, result.single().prefixLength)
    }

    @Test
    fun `dedupe keeps the prefix length of the surviving interface`() {
        val result = LanAddressSelector.select(
            listOf(
                ifaceWithPrefix("eth0", "192.168.1.50", prefixLength = 16),
                ifaceWithPrefix("wlan0", "192.168.1.50", prefixLength = 24)
            )
        )
        assertEquals(1, result.size)
        assertEquals("wlan0", result.single().interfaceName)
        assertEquals(24, result.single().prefixLength)
    }
}
