package tv.cleanyour.core.flows

/** A transient, read-only snapshot of network settings reported by the TV. */
data class TvNetworkDiagnostics(
    val dnsServers: List<String>,
    val privateDnsMode: TvPrivateDnsMode,
    val privateDnsProviderHostname: String?,
    val defaultGateway: String?,
    val networkInterface: String?,
    val connectivityProbe: TvConnectivityProbe = TvConnectivityProbe.UNKNOWN,
)

enum class TvPrivateDnsMode { OFF, AUTOMATIC, PROVIDER_HOSTNAME, UNKNOWN }
enum class TvConnectivityProbe { HOST_REPLIED, DNS_RESOLVED_NO_REPLY, DNS_LOOKUP_FAILED, NO_ROUTE, UNAVAILABLE, UNKNOWN }

enum class TvNetworkDiagnosticsStatus { IDLE, READING, READY, FAILED }

object TvNetworkDiagnosticsParser {
    fun parse(
        dnsServerValues: List<String?>,
        privateDnsModeValue: String?,
        privateDnsHostnameValue: String?,
        defaultRouteValue: String?,
        probeExitCode: Int? = null,
        probeOutput: String? = null,
        probeTruncated: Boolean = false,
    ): TvNetworkDiagnostics {
        val mode = when (clean(privateDnsModeValue)?.lowercase()) {
            "off" -> TvPrivateDnsMode.OFF
            "opportunistic" -> TvPrivateDnsMode.AUTOMATIC
            "hostname" -> TvPrivateDnsMode.PROVIDER_HOSTNAME
            else -> TvPrivateDnsMode.UNKNOWN
        }
        val (gateway, networkInterface) = parseDefaultRoute(defaultRouteValue)
        return TvNetworkDiagnostics(
            dnsServers = dnsServerValues.mapNotNull(::clean).distinct().take(4),
            privateDnsMode = mode,
            privateDnsProviderHostname = if (mode == TvPrivateDnsMode.PROVIDER_HOSTNAME) clean(privateDnsHostnameValue) else null,
            defaultGateway = gateway,
            networkInterface = networkInterface,
            connectivityProbe = parseConnectivityProbe(probeExitCode, probeOutput, probeTruncated),
        )
    }

    private fun parseConnectivityProbe(exitCode: Int?, rawOutput: String?, truncated: Boolean): TvConnectivityProbe {
        if (exitCode == null && rawOutput == null) return TvConnectivityProbe.UNKNOWN
        val output = rawOutput?.takeIf { !truncated && it.length <= MAX_PROBE_OUTPUT_CHARS }
            ?: return TvConnectivityProbe.UNAVAILABLE
        if (output.isBlank() || exitCode == null) return TvConnectivityProbe.UNAVAILABLE
        val normalized = output.lowercase()
        if (listOf("network is unreachable", "network unreachable", "no route to host")
                .any(normalized::contains)
        ) return TvConnectivityProbe.NO_ROUTE
        if (listOf(
                "unknown host", "bad address", "name or service not known",
                "temporary failure in name resolution", "could not resolve", "getaddrinfo",
            ).any(normalized::contains)
        ) return TvConnectivityProbe.DNS_LOOKUP_FAILED
        if (Regex("(?im)^\\s*\\d+\\s+bytes from\\s+").containsMatchIn(output) ||
            Regex("(?im)^\\s*64 bytes from\\s+").containsMatchIn(output)
        ) return TvConnectivityProbe.HOST_REPLIED
        if (Regex("(?im)^\\s*ping:\\s*(?:.*\\s)?(?:not found|unknown option|usage:)").containsMatchIn(output)) {
            return TvConnectivityProbe.UNAVAILABLE
        }
        if (Regex("(?im)^\\s*PING\\s+example\\.com\\s+\\(").containsMatchIn(output)) {
            return TvConnectivityProbe.DNS_RESOLVED_NO_REPLY
        }
        return TvConnectivityProbe.UNKNOWN
    }

    private fun parseDefaultRoute(raw: String?): Pair<String?, String?> {
        val line = raw.orEmpty().lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("default ") }
            ?: return null to null
        val fields = line.split(Regex("\\s+"))
        fun after(key: String): String? = fields.indexOf(key)
            .takeIf { it >= 0 && it + 1 < fields.size }
            ?.let { clean(fields[it + 1]) }
        return after("via") to after("dev")
    }

    private fun clean(raw: String?): String? {
        val value = raw
            ?.filter { it.code >= 0x20 && it.code != 0x7f }
            ?.trim()
            ?.take(128)
            ?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("null", "<unknown>") }
        return value
    }

    private const val MAX_PROBE_OUTPUT_CHARS = 8 * 1024
}
