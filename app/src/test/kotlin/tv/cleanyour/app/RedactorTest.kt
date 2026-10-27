package tv.cleanyour.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// F13: logcat of a full flow contains no token, key, serial or full IP.
class RedactorTest {
    @Test fun addressesKeepOnlyTheLastOctet() = assertEquals("connect x.x.x.50:5555", Redactor.redact("connect 192.168.1.50:5555"))

    @Test fun macAddressesInCommonFormatsAreRedacted() {
        val text = "peer aa:bb:cc:dd:ee:ff, wired 01-23-45-67-89-ab, cisco aabb.ccdd.eeff"
        assertEquals("peer [mac], wired [mac], cisco [mac]", Redactor.redact(text))
    }

    @Test fun ipv6AddressesIncludingBracketsAndZonesAreRedacted() {
        val text = "address 2001:db8::1, link fe80::1%wlan0, bracket [2001:db8::2]:5555"
        assertEquals("address [ipv6], link [ipv6], bracket [ipv6]:5555", Redactor.redact(text))
    }

    @Test fun ipv6CanFollowAFieldLabelWithoutWhitespace() {
        assertEquals("ip:[ipv6]", Redactor.redact("ip:2001:db8::3"))
        assertEquals("address:[ipv6]", Redactor.redact("address:2001:db8::4"))
    }

    @Test fun uncompressedIpv6IsRedacted() {
        val address = "2001:0db8:0000:0000:0000:ff00:0042:8329"
        assertEquals("peer [ipv6]", Redactor.redact("peer $address"))
    }

    @Test fun ipv4EmbeddedIpv6IsRedactedWithoutMaskingTimes() {
        val text = "mapped ::ffff:192.0.2.128; time 12:34:56"
        assertEquals("mapped [ipv6]; time 12:34:56", Redactor.redact(text))
    }

    @Test fun malformedColonTextAndBuildMetadataRemainReadable() {
        val text = "time 12:34:56; build V816.0.11.0.UZFAABX; malformed 1:2:3"
        assertEquals(text, Redactor.redact(text))
    }

    @Test fun malformedAndAdjacentIdentifierTextIsNotPartiallyRedacted() {
        val text = "bad 2001::db8::1; mapped ::ffff:192.0.2.256; " +
            "x2001:db8::1 x2001:db8::1g xaa:bb:cc:dd:ee:ffz xaabb.ccdd.eeffz"
        assertEquals(text, Redactor.redact(text))
    }

    @Test fun keysTokensAndFingerprintsGo() {
        val out = Redactor.redact("key QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB cleanyourtv fp AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89")
        assertFalse("QUFB" in out)
        assertFalse("AB:CD" in out)
    }

    @Test fun serialsIdsAndCodesGo() {
        val out = Redactor.redact("[ro.serialno]: [12345ABCDE] tv 0123456789abcdef code 482913 ssid=HomeNet")
        assertFalse("12345ABCDE" in out)
        assertFalse("0123456789abcdef" in out)
        assertFalse("482913" in out)
        assertFalse("HomeNet" in out)
    }

    @Test fun serialNumberLabelsRedactTheEntireIdentifier() {
        val cases = listOf(
            "Serial number: ABC123XYZ789" to "Serial number: [serial]",
            "serial number ABC123XYZ789" to "serial number [serial]",
        )
        for ((input, expected) in cases) assertEquals(expected, Redactor.redact(input))
    }

    @Test fun wifiNamesWithSpacesAndCommasAreRedactedCompletely() {
        val text = """ssid="Home Network, Guest", wifi name='Family Room', ssid:Office, Lobby Network; signal=-50"""
        assertEquals("ssid=[wifi], wifi name=[wifi], ssid:[wifi]; signal=-50", Redactor.redact(text))
        assertEquals("ssid=[wifi]\nsignal=-50", Redactor.redact("ssid=Home Network\nsignal=-50"))
    }

    // 0.3.2 B3: the new log lines stay readable after the redactor (codes, models, codenames and the
    // failed op's package), while an address or a serial in the TV's own stderr still goes.
    @Test fun theReadableLogLinesSurviveTheRedactor() {
        val probe = "setup: probe done in 5464 ms, 269 packages, 27 launchable, profile brand set Amazon, tv Insignia NS-55F501NA26 (mantis) sdk 30"
        assertEquals(probe, Redactor.redact(probe))
        val home = "setup: home switch blocked WIZARD_STAYS_ON"
        assertEquals(home, Redactor.redact(home))
        val pause = Redactor.redact("setup: run paused STEP_FAILED at step 72: undo step failed: Enable com.amazon.tv.launcher: " +
            "nonzero_exit(1) Error: package com.amazon.tv.launcher is protected, serialno=G070VM1234567 from 192.168.1.20")
        for (kept in listOf("paused STEP_FAILED at step 72", "undo step failed: Enable com.amazon.tv.launcher", "nonzero_exit(1)")) {
            kotlin.test.assertTrue(kept in pause, "$kept lost in $pause")
        }
        assertFalse("G070VM1234567" in pause)
        assertFalse("192.168.1.20" in pause)
    }

    // 0.3.3 D3 (issue #10): an address is four octets of 0 to 255 standing on their own. Build
    // strings and fingerprints from real reports stay whole; real addresses still go.
    @Test fun buildStringsStayWholeAndAddressesGo() {
        val whole = listOf(
            "V816.0.11.0.UZFAABX",
            "UKG3.250107.001",
            "Xiaomi/jaws/jaws:14/UKG3.250107.001/V816.0.11.0.UZFAABX:user/release-keys",          // issue #10
            "AURZEN/AURZEN_BOOMAIR/himalaya:11/RTMA.250416.043/13549864:user/release-keys",       // issue #9
            "Haier/nippori/nippori:11/RTO5.250812.001/13924387:user/release-keys",                // issue #11
            "Smarttech/SMARTTECH_4K_ATV3/marina:11/RTMA.250416.028/13485761:user/release-keys",   // issue #15
            "version 1.2.3.4.5", "v1.2.3.4", "256.1.1.1", "192.168.1.2555",
        )
        for (s in whole) assertEquals(s, Redactor.redact(s))
        assertEquals("x.x.x.195", Redactor.redact("192.168.0.195"))
        assertEquals("x.x.x.5:5555", Redactor.redact("10.0.0.5:5555"))
        assertEquals("addr x.x.x.2,", Redactor.redact("addr 172.16.4.2,"))
        // The dot that ends a sentence is not part of a build string.
        assertEquals("connect failed from x.x.x.20.", Redactor.redact("connect failed from 192.168.1.20."))
        assertEquals("[x.x.x.1] (x.x.x.9)", Redactor.redact("[192.168.1.1] (10.0.0.9)"))
    }
}
