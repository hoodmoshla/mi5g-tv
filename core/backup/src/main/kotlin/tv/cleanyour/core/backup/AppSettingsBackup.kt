package tv.cleanyour.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tv.cleanyour.core.store.DocumentStore
import java.security.MessageDigest

/** The only app preferences included in a portable backup. Device records, ADB keys, logs, and other apps' data are excluded. */
data class AppSettingsPreferences(val haptics: Boolean, val largeTextFollowsSystem: Boolean)

data class AppSettingsBackupDocument(
    val appVersion: String,
    val createdAtMs: Long,
    val preferences: AppSettingsPreferences,
)

enum class AppSettingsBackupProblem { TOO_LARGE, INVALID_JSON, UNSUPPORTED_FORMAT, WRONG_APP, INVALID_CONTENT, CHECKSUM_MISMATCH }

class AppSettingsBackupException(val problem: AppSettingsBackupProblem) : IllegalArgumentException(problem.name)

/**
 * Versioned JSON export with a SHA-256 integrity check over canonical known fields. The checksum
 * detects corruption; it is not a signature and does not prove who created or modified a file.
 */
object AppSettingsBackupCodec {
    const val SCHEMA = 1
    const val APPLICATION_ID = "com.midroid.mi5gtv"
    const val MAX_FILE_BYTES = 32 * 1024
    private const val FORMAT = "mi5g-tv-app-settings"
    private val json = Json
    private val rootKeys = setOf("format", "schema", "applicationId", "appVersion", "createdAtMs", "preferences", "sha256")
    private val preferenceKeys = setOf("haptics", "largeTextFollowsSystem")

    fun encode(appVersion: String, createdAtMs: Long, preferences: AppSettingsPreferences): ByteArray {
        if (createdAtMs <= 0 || !appVersion.matches(Regex("[A-Za-z0-9._+-]{1,40}"))) {
            throw IllegalArgumentException("invalid backup metadata")
        }
        val payload = payload(appVersion, createdAtMs, preferences)
        val document = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("sha256", sha256(payload.toString().encodeToByteArray()))
        }.toString().encodeToByteArray()
        require(document.size <= MAX_FILE_BYTES) { "backup is too large" }
        return document
    }

    fun decode(bytes: ByteArray): AppSettingsBackupDocument {
        if (bytes.size > MAX_FILE_BYTES) fail(AppSettingsBackupProblem.TOO_LARGE)
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: Exception) {
            fail(AppSettingsBackupProblem.INVALID_JSON)
        }
        if (root.keys != rootKeys) fail(AppSettingsBackupProblem.INVALID_CONTENT)
        if (string(root, "format") != FORMAT || integer(root, "schema") != SCHEMA) {
            fail(AppSettingsBackupProblem.UNSUPPORTED_FORMAT)
        }
        if (string(root, "applicationId") != APPLICATION_ID) fail(AppSettingsBackupProblem.WRONG_APP)
        val version = string(root, "appVersion")
        if (!version.matches(Regex("[A-Za-z0-9._+-]{1,40}"))) fail(AppSettingsBackupProblem.INVALID_CONTENT)
        val createdAt = long(root, "createdAtMs")
        if (createdAt <= 0) fail(AppSettingsBackupProblem.INVALID_CONTENT)
        val settings = try { root.getValue("preferences").jsonObject } catch (_: Exception) {
            fail(AppSettingsBackupProblem.INVALID_CONTENT)
        }
        if (settings.keys != preferenceKeys) fail(AppSettingsBackupProblem.INVALID_CONTENT)
        val preferences = AppSettingsPreferences(
            haptics = boolean(settings, "haptics"),
            largeTextFollowsSystem = boolean(settings, "largeTextFollowsSystem"),
        )
        val supplied = string(root, "sha256")
        if (!supplied.matches(Regex("[0-9a-f]{64}"))) fail(AppSettingsBackupProblem.INVALID_CONTENT)
        val expected = sha256(payload(version, createdAt, preferences).toString().encodeToByteArray())
        if (!MessageDigest.isEqual(supplied.encodeToByteArray(), expected.encodeToByteArray())) {
            fail(AppSettingsBackupProblem.CHECKSUM_MISMATCH)
        }
        return AppSettingsBackupDocument(version, createdAt, preferences)
    }

    private fun payload(version: String, createdAt: Long, preferences: AppSettingsPreferences): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("schema", SCHEMA)
        put("applicationId", APPLICATION_ID)
        put("appVersion", version)
        put("createdAtMs", createdAt)
        put("preferences", buildJsonObject {
            put("haptics", preferences.haptics)
            put("largeTextFollowsSystem", preferences.largeTextFollowsSystem)
        })
    }

    private fun string(obj: JsonObject, key: String): String =
        try {
            val value = obj.getValue(key).jsonPrimitive
            if (!value.isString) fail(AppSettingsBackupProblem.INVALID_CONTENT)
            value.content
        } catch (_: Exception) { fail(AppSettingsBackupProblem.INVALID_CONTENT) }

    private fun integer(obj: JsonObject, key: String): Int =
        try {
            val value = obj.getValue(key).jsonPrimitive
            if (value.isString) fail(AppSettingsBackupProblem.INVALID_CONTENT)
            value.intOrNull ?: fail(AppSettingsBackupProblem.INVALID_CONTENT)
        }
        catch (_: Exception) { fail(AppSettingsBackupProblem.INVALID_CONTENT) }

    private fun long(obj: JsonObject, key: String): Long =
        try {
            val value = obj.getValue(key).jsonPrimitive
            if (value.isString) fail(AppSettingsBackupProblem.INVALID_CONTENT)
            value.longOrNull ?: fail(AppSettingsBackupProblem.INVALID_CONTENT)
        }
        catch (_: Exception) { fail(AppSettingsBackupProblem.INVALID_CONTENT) }

    private fun boolean(obj: JsonObject, key: String): Boolean =
        try {
            val value = obj.getValue(key).jsonPrimitive
            if (value.isString) fail(AppSettingsBackupProblem.INVALID_CONTENT)
            value.booleanOrNull ?: fail(AppSettingsBackupProblem.INVALID_CONTENT)
        }
        catch (_: Exception) { fail(AppSettingsBackupProblem.INVALID_CONTENT) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fail(problem: AppSettingsBackupProblem): Nothing = throw AppSettingsBackupException(problem)
}

enum class AppSettingsBackupOperation { EXPORT, IMPORT, ROLLBACK }
enum class AppSettingsBackupResult { SUCCESS, CANCELLED, REJECTED, FAILED }
data class AppSettingsBackupHistoryEntry(val atMs: Long, val operation: AppSettingsBackupOperation, val result: AppSettingsBackupResult)

/** Bounded local history and the one-step rollback point for importing this app's own preferences. */
class AppSettingsBackupStore(private val documents: DocumentStore) {
    suspend fun record(entry: AppSettingsBackupHistoryEntry) {
        require(entry.atMs > 0)
        val entries = (history() + entry).takeLast(MAX_HISTORY)
        val text = entries.joinToString(separator = "\n", postfix = "\n") {
            "${it.atMs}\t${it.operation.name}\t${it.result.name}"
        }
        documents.write(HISTORY_PATH, text.encodeToByteArray())
    }

    suspend fun history(): List<AppSettingsBackupHistoryEntry> {
        val saved: List<AppSettingsBackupHistoryEntry>? = documents.read(HISTORY_PATH) { bytes ->
            val parsed = mutableListOf<AppSettingsBackupHistoryEntry>()
            bytes.decodeToString().lineSequence().forEach { line -> parseEntry(line)?.let(parsed::add) }
            parsed.takeLast(MAX_HISTORY)
        }
        return saved.orEmpty()
    }

    suspend fun saveRollback(document: AppSettingsBackupDocument) {
        documents.write(ROLLBACK_PATH, AppSettingsBackupCodec.encode(document.appVersion, document.createdAtMs, document.preferences))
    }

    suspend fun loadRollback(): AppSettingsBackupDocument? = documents.read(ROLLBACK_PATH, AppSettingsBackupCodec::decode)

    suspend fun clearRollback() = documents.delete(ROLLBACK_PATH)

    private fun parseEntry(line: String): AppSettingsBackupHistoryEntry? {
        val fields = line.split('\t')
        if (fields.size != 3) return null
        val atMs = fields[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val operation = runCatching { AppSettingsBackupOperation.valueOf(fields[1]) }.getOrNull() ?: return null
        val result = runCatching { AppSettingsBackupResult.valueOf(fields[2]) }.getOrNull() ?: return null
        return AppSettingsBackupHistoryEntry(atMs, operation, result)
    }

    companion object {
        const val HISTORY_PATH = "app-settings-backup-history.tsv"
        const val ROLLBACK_PATH = "app-settings-backup-rollback.json"
        const val MAX_HISTORY = 50
    }
}
