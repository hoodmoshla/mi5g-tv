package tv.cleanyour.app

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.Diagnostics

// SECURITY 4.6 and F13: everything the app logs goes through this redactor, and so does the
// "Share diagnostics" text. Replaced: base64 or hex blobs of 40 characters or more (AUTH tokens,
// keys, signatures), MD5 colon fingerprints, MAC and IPv6 addresses, IPv4 addresses (the last
// octet is kept), serial numbers, 16 hex TV ids (serial-derived), 6 digit codes, Wi-Fi names, and
// anything the caller marks secret.
object Redactor {
    private val BLOB = Regex("[A-Za-z0-9+/_=-]{40,}")
    private val FINGERPRINT = Regex("\\b(?:[0-9A-Fa-f]{2}:){15}[0-9A-Fa-f]{2}\\b")
    // 0.3.3 D3 (issue #10): four octets of 0 to 255, not glued to a letter, a digit or a dot, so a
    // build string such as "V816.0.11.0.UZFAABX" or "UKG3.250107.001" stays whole. A dot that ends
    // a sentence ("from 192.168.1.20.") still masks: only a dot followed by a letter or digit counts.
    private const val OCTET = "(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)"
    private val IPV4 = Regex("(?<![A-Za-z0-9.])$OCTET\\.$OCTET\\.$OCTET\\.$OCTET(?![A-Za-z0-9]|\\.[A-Za-z0-9])")
    private val SERIAL = Regex("(?i)(serial(?:no)?|ro\\.serialno|ro\\.boot\\.serialno)([\"'\\]\\s:=\\[]+)([A-Za-z0-9]{4,})")
    private val TV_ID = Regex("\\b[0-9a-f]{16}\\b")
    private val CODE = Regex("(?<![\\d.])\\d{6}(?![\\d.])")
    // Quoted names may contain spaces/commas; unquoted values stop at the next field to preserve the log.
    private const val SSID_FIELD_KEY = "(?:[A-Za-z_][A-Za-z0-9_.-]*|wifi[ _-]?name)"
    private val SSID = Regex(
        """(?i)(ssid|wifi[ _-]?name)([ \t]*[:=][ \t]*|[ \t]+)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^;\r\n]+?)(?=,\s*$SSID_FIELD_KEY\s*[:=]|;|\r|\n|$|\s+$SSID_FIELD_KEY\s*[:=])"""
    )
    private val MAC = Regex(
        "(?i)(?<![A-Za-z0-9:-])(?:[0-9a-f]{2}(?::|-)){5}[0-9a-f]{2}(?![A-Za-z0-9:-])" +
            "|(?<![A-Za-z0-9.])[0-9a-f]{4}(?:\\.[0-9a-f]{4}){2}(?![A-Za-z0-9.])"
    )
    private val BRACKETED_IPV6 = Regex(
        "\\[([0-9a-f:.]{2,45}(?:%[a-z0-9_.-]+)?)\\]",
        RegexOption.IGNORE_CASE
    )
    private val IPV6 = Regex(
        """((?:[0-9a-f][0-9a-f:.]{1,44}|::[0-9a-f:.]{0,43})(?:%[a-z0-9_.-]+)?)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val IPV6_FIELD_LABELS = setOf(
        "ip", "ip4", "ip6", "ipv4", "ipv6", "addr", "address", "host", "gateway", "dns", "server",
        "peer", "source", "destination", "remote", "local", "src", "dst", "endpoint", "interface", "iface",
        "eth0", "wlan0", "wlan1", "en0", "lo", "usb0"
    )

    private fun redactIpv6Candidate(candidate: String): String {
        val trailingDots = candidate.takeLastWhile { it == '.' }
        val address = candidate.dropLast(trailingDots.length)
        return if (isIpv6Literal(address)) "[ipv6]$trailingDots" else candidate
    }

    private fun hasIpv6StartBoundary(text: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = text[start - 1]
        if (previous.isLetterOrDigit()) return false
        if (previous != ':') return true

        var labelStart = start - 1
        while (labelStart > 0 && (text[labelStart - 1].isLetterOrDigit() || text[labelStart - 1] == '_' || text[labelStart - 1] == '-')) {
            labelStart--
        }
        val label = text.substring(labelStart, start - 1)
        if (label.isEmpty()) return true
        val normalizedLabel = label.lowercase()
        if (normalizedLabel in IPV6_FIELD_LABELS) return true
        return label.all { it.isLetter() || it == '_' || it == '-' } && label.any { !it.isAsciiHexDigit() }
    }

    private fun redactIpv6(text: String): String {
        val output = StringBuilder(text.length)
        var copiedUntil = 0
        var changed = false
        for (match in IPV6.findAll(text)) {
            val candidate = match.groupValues[1]
            if (!hasIpv6StartBoundary(text, match.range.first)) continue
            val replacement = redactIpv6Candidate(candidate)
            if (replacement == candidate) continue
            output.append(text, copiedUntil, match.range.first)
            output.append(replacement)
            copiedUntil = match.range.last + 1
            changed = true
        }
        if (!changed) return text
        output.append(text, copiedUntil, text.length)
        return output.toString()
    }

    // Validate the literal locally; never call InetAddress, which may perform DNS lookups.
    private fun isIpv6Literal(value: String): Boolean {
        val zoneSeparator = value.indexOf('%')
        val address = if (zoneSeparator >= 0) {
            if (value.indexOf('%', zoneSeparator + 1) >= 0) return false
            val zone = value.substring(zoneSeparator + 1)
            if (zone.isEmpty() || zone.any {
                    it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' && it != '-' && it != '.'
                }) return false
            value.substring(0, zoneSeparator)
        } else value

        if (address.count { it == ':' } < 2) return false
        val compression = address.indexOf("::")
        if (compression >= 0) {
            if (address.indexOf("::", compression + 2) >= 0) return false
            val left = ipv6GroupCount(address.substring(0, compression), allowIpv4AtEnd = false) ?: return false
            val right = ipv6GroupCount(address.substring(compression + 2), allowIpv4AtEnd = true) ?: return false
            return left + right < 8
        }
        return ipv6GroupCount(address, allowIpv4AtEnd = true) == 8
    }

    private fun ipv6GroupCount(part: String, allowIpv4AtEnd: Boolean): Int? {
        if (part.isEmpty()) return 0
        if (part.startsWith(':') || part.endsWith(':') || "::" in part) return null
        val pieces = part.split(':')
        var groups = 0
        for (index in pieces.indices) {
            val piece = pieces[index]
            if ('.' in piece) {
                if (!allowIpv4AtEnd || index != pieces.lastIndex || !isIpv4Literal(piece)) return null
                groups += 2
            } else {
                if (piece.length !in 1..4 || piece.any { !it.isAsciiHexDigit() }) return null
                groups++
            }
        }
        return groups
    }

    private fun isIpv4Literal(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun redact(text: String): String = text
        .replace(FINGERPRINT, "[fingerprint]")
        .replace(MAC, "[mac]")
        .replace(BLOB, "[blob]")
        .replace(BRACKETED_IPV6) { match ->
            if (isIpv6Literal(match.groupValues[1])) "[ipv6]" else match.value
        }
        .let(::redactIpv6)
        .replace(SERIAL) { "${it.groupValues[1]}${it.groupValues[2]}[serial]" }
        .replace(SSID) { "${it.groupValues[1]}${it.groupValues[2]}[wifi]" }
        .replace(IPV4) { "x.x.x.${it.groupValues[4]}" }
        .replace(TV_ID, "[tv]")
        .replace(CODE, "[code]")
}

// The app's only logger: redacted lines to logcat and to a small ring (files/logs, 5 files of
// 1 MB) that "Share diagnostics" reads. Release builds strip d and v calls with R8.
object CytLog {
    private const val TAG = "CleanYourTv"
    private const val MAX_FILE = 1_000_000L
    private const val MAX_LINE_CHARS = 2_000
    private const val TRUNCATION_MARKER = "…[truncated]"
    private const val FILES = 5
    private var dir: File? = null
    private val ring = ArrayDeque<String>()

    fun init(logDir: File) { dir = logDir.also { it.mkdirs() } }

    fun d(message: String) = write("D", message, toLogcat = false)
    fun i(message: String) = write("I", message, toLogcat = true)
    fun w(message: String, t: Throwable? = null) = write("W", message + (t?.let { ": ${it.javaClass.simpleName}" } ?: ""), toLogcat = true)
    fun secret(@Suppress("UNUSED_PARAMETER") value: String): String = "[secret]"

    @Synchronized
    private fun write(level: String, message: String, toLogcat: Boolean) {
        val prefix = "${System.currentTimeMillis()} $level "
        // Redact before truncating so cutting through a token cannot leave an unredacted fragment.
        val redacted = Redactor.redact(message)
        val contentLimit = MAX_LINE_CHARS - prefix.length
        val content = if (redacted.length > contentLimit) {
            val keep = (contentLimit - TRUNCATION_MARKER.length).coerceAtLeast(0)
            redacted.take(keep) + TRUNCATION_MARKER.take(contentLimit)
        } else redacted
        val line = prefix + content
        if (toLogcat) Log.i(TAG, line)
        ring.addLast(line)
        while (ring.size > 2_000) ring.removeFirst()
        val d = dir ?: return
        runCatching {
            val f = File(d, "session-0.log")
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (f.length() + bytes.size > MAX_FILE) {
                for (n in FILES - 1 downTo 1) File(d, "session-${n - 1}.log").takeIf { it.exists() }?.renameTo(File(d, "session-$n.log"))
            }
            File(d, "session-0.log").appendBytes(bytes)
        }
    }

    @Synchronized
    fun snapshot(): String {
        val d = dir
        val files = if (d == null) emptyList() else (FILES - 1 downTo 0).map { File(d, "session-$it.log") }.filter { it.exists() }
        val text = if (files.isEmpty()) ring.joinToString("\n") else files.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
        return Redactor.redact(text)
    }

    fun clear() { synchronized(this) { ring.clear(); dir?.listFiles()?.forEach { it.delete() } } }
}

class LogDiagnostics(private val header: () -> String) : Diagnostics {
    override suspend fun redactedText(): String = Redactor.redact(header()) + "\n" + CytLog.snapshot()
    override suspend fun clear() = withContext(Dispatchers.IO) { CytLog.clear() }
}
