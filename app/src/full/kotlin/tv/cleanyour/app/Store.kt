package tv.cleanyour.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.IndexLimits
import tv.cleanyour.core.trust.DownloadHosts

// The full build (GitHub and F-Droid, STORES-PLAN 1): everything the app did before the store
// flavours, unchanged.
object Store : StorePorts {
    override val flavour = "full"
    override val hosts: Set<String> = DownloadHosts.ALL
    override val rules = StoreRules.FULL
    override val updatesMayGoOff = rules.updatesOff
    override val releasePage: String = RELEASE_PAGE

    // D12: one GET on user action (Settings, "Check for updates"); "couldn't check" when GitHub
    // does not answer or the repository has no published release yet.
    override fun updateChecker(http: HttpFetcher) = UpdateChecker { current ->
        val r = http.get(RELEASES_URL, null, null)
        val contentLength = r.length
        if (r.code != 200) { r.body?.close(); null } else {
            val body = r.body?.use { input ->
                if (contentLength != null && contentLength > IndexLimits.INDEX_BYTES) null
                else readReleaseBody(input)
            } ?: return@UpdateChecker null
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9A-Za-z.]*)\"").find(body)?.groupValues?.get(1) ?: return@UpdateChecker null
            val order = compareDottedNumericVersions(tag, current) ?: return@UpdateChecker null
            if (order > 0) tag else ""
        }
    }

    private fun readReleaseBody(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val remaining = IndexLimits.INDEX_BYTES - total
            val n = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (n < 0) return out.toByteArray().decodeToString()
            if (n > remaining) return null
            out.write(buffer, 0, n)
            total += n
        }
    }

    private fun compareDottedNumericVersions(candidate: String, current: String): Int? {
        fun parts(version: String): List<Long>? {
            val components = version.split('.')
            if (components.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
            return components.map { it.toLongOrNull() ?: return null }
        }
        val candidateParts = parts(candidate) ?: return null
        val currentParts = parts(current) ?: return null
        for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val order = (candidateParts.getOrNull(index) ?: 0L).compareTo(currentParts.getOrNull(index) ?: 0L)
            if (order != 0) return order
        }
        return 0
    }

    override fun addOnFetcher(make: () -> ArtifactFetcher): ArtifactFetcher = make()

    const val RELEASES_URL = "https://api.github.com/repos/al3nkboot/mi5g-tv/releases/latest"
    const val RELEASE_PAGE = "https://github.com/al3nkboot/mi5g-tv/releases"
}
