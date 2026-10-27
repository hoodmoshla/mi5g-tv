package tv.cleanyour.core.flows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvNetworkDiagnosticsTest {
    @Test fun parsesDnsPrivateDnsAndDefaultRoute() {
        val result = TvNetworkDiagnosticsParser.parse(
            dnsServerValues = listOf("192.168.1.2\n", "fd00::1\n", "192.168.1.2", "null"),
            privateDnsModeValue = "opportunistic\n",
            privateDnsHostnameValue = "ignored.example\n",
            defaultRouteValue = "default via 192.168.1.1 dev wlan0 proto dhcp src 192.168.1.20 metric 600\n",
        )

        assertEquals(listOf("192.168.1.2", "fd00::1"), result.dnsServers)
        assertEquals(TvPrivateDnsMode.AUTOMATIC, result.privateDnsMode)
        assertNull(result.privateDnsProviderHostname)
        assertEquals("192.168.1.1", result.defaultGateway)
        assertEquals("wlan0", result.networkInterface)
    }

    @Test fun showsProviderHostOnlyForHostnameModeAndHandlesMissingValues() {
        val hostnameMode = TvNetworkDiagnosticsParser.parse(
            dnsServerValues = listOf("", "<unknown>", null, "null"),
            privateDnsModeValue = "hostname",
            privateDnsHostnameValue = "dns.example.net\n",
            defaultRouteValue = "unreachable default\n",
        )
        assertEquals(emptyList(), hostnameMode.dnsServers)
        assertEquals(TvPrivateDnsMode.PROVIDER_HOSTNAME, hostnameMode.privateDnsMode)
        assertEquals("dns.example.net", hostnameMode.privateDnsProviderHostname)
        assertNull(hostnameMode.defaultGateway)
        assertNull(hostnameMode.networkInterface)

        val off = TvNetworkDiagnosticsParser.parse(emptyList(), "off", "stale.example", "")
        assertEquals(TvPrivateDnsMode.OFF, off.privateDnsMode)
        assertNull(off.privateDnsProviderHostname)
    }
}
