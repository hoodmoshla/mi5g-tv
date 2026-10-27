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
        assertEquals(TvConnectivityProbe.UNKNOWN, result.connectivityProbe)
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

    @Test fun classifiesReadOnlyDnsProbeWithoutOverstatingNoPingReply() {
        val replied = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 0,
            "PING example.com (93.184.216.34) 56 data bytes\n64 bytes from 93.184.216.34: icmp_seq=1 ttl=55 time=20 ms",
        )
        assertEquals(TvConnectivityProbe.HOST_REPLIED, replied.connectivityProbe)

        val resolvedButBlocked = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 1,
            "PING example.com (93.184.216.34) 56 data bytes\n1 packets transmitted, 0 packets received",
        )
        assertEquals(TvConnectivityProbe.DNS_RESOLVED_NO_REPLY, resolvedButBlocked.connectivityProbe)

        val dnsFailed = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 2, "ping: bad address 'example.com'",
        )
        assertEquals(TvConnectivityProbe.DNS_LOOKUP_FAILED, dnsFailed.connectivityProbe)

        val noRoute = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 2, "ping: sendto: Network is unreachable",
        )
        assertEquals(TvConnectivityProbe.NO_ROUTE, noRoute.connectivityProbe)

        val unavailable = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 127, "ping: not found",
        )
        assertEquals(TvConnectivityProbe.UNAVAILABLE, unavailable.connectivityProbe)

        val truncated = TvNetworkDiagnosticsParser.parse(
            emptyList(), "off", null, null, 0, "PING example.com (93.184.216.34)", probeTruncated = true,
        )
        assertEquals(TvConnectivityProbe.UNAVAILABLE, truncated.connectivityProbe)
    }
}
