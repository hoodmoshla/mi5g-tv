package tv.cleanyour.core.flows

/** A transient, read-only snapshot of network settings reported by the TV. */
data class TvNetworkDiagnostics(
    val dnsServers: List<String>,
    val privateDnsMode: TvPrivateDnsMode,
    val privateDnsProviderHostname: String?,
    val defaultGateway: String?,
    val networkInterface: String?,
)

enum class TvPrivateDnsMode { OFF, AUTOMATIC, PROVIDER_HOSTNAME, UNKNOWN }

enum class TvNetworkDiagnosticsStatus { IDLE, READING, READY, FAILED }

object TvNetworkDiagnosticsParser {
    fun parse(
        dnsServerValues: List<String?>,
        privateDnsModeValue: String?,
        privateDnsHostnameValue: String?,
        defaultRouteValue: String?,
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
        )
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
}
