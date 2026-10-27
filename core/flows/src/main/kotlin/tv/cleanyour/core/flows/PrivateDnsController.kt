package tv.cleanyour.core.flows

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.PrivateDnsSetting
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.probe.TvShellRunner
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.DocumentStore

/** A fail-closed coordinator for the AdGuard module's Android Private DNS settings. */
class PrivateDnsController(private val documents: DocumentStore) {
    suspend fun applyAdGuard(tvId: TvId, session: TvSession): PrivateDnsApplyResult = lock(tvId).withLock {
        val stored = readBackup(tvId)
        val snapshot: PrivateDnsSnapshot
        val created: Boolean
        when (stored) {
            BackupState.Invalid -> return@withLock PrivateDnsApplyResult.NOT_APPLIED
            BackupState.Missing -> {
                val original = readSettings(session) ?: return@withLock PrivateDnsApplyResult.NOT_APPLIED
                if (!original.isSupported()) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                if (!writeBackup(tvId, BackupRecord(original, BackupLifecycle.ACTIVE))) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                snapshot = original
                created = true
            }
            is BackupState.Present -> {
                when (stored.value.lifecycle) {
                    BackupLifecycle.RESTORE_PENDING -> {
                        snapshot = stored.value.snapshot
                        created = false
                    }
                    BackupLifecycle.CONSUMED -> {
                        // A previous removal completed but its backup file could not be deleted.
                        // Capture the current preference instead of reusing the retired snapshot.
                        val original = readSettings(session) ?: return@withLock PrivateDnsApplyResult.NOT_APPLIED
                        if (!original.isSupported()) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                        if (!writeBackup(tvId, BackupRecord(original, BackupLifecycle.ACTIVE))) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                        snapshot = original
                        created = true
                    }
                    BackupLifecycle.ACTIVE -> {
                        val current = readSettings(session) ?: return@withLock PrivateDnsApplyResult.NOT_APPLIED
                        if (current == ADGUARD_SETTINGS) {
                            snapshot = stored.value.snapshot
                            created = false
                        } else {
                            // A stale file left after a completed removal, or a person-changed DNS
                            // setting, must not overwrite the current preference on the next removal.
                            if (!current.isSupported()) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                            if (!writeBackup(tvId, BackupRecord(current, BackupLifecycle.ACTIVE))) return@withLock PrivateDnsApplyResult.NOT_APPLIED
                            snapshot = current
                            created = true
                        }
                    }
                }
            }
        }

        if (applyDesired(session)) {
            if (stored is BackupState.Present && stored.value.lifecycle == BackupLifecycle.RESTORE_PENDING) {
                writeBackup(tvId, BackupRecord(snapshot, BackupLifecycle.ACTIVE))
            }
            return@withLock PrivateDnsApplyResult.APPLIED
        }
        val restored = restoreSettings(session, snapshot)
        if (restored) {
            if (created && !retireBackupLocked(tvId)) PrivateDnsApplyResult.RESTORE_PENDING
            else {
                if (stored is BackupState.Present && stored.value.lifecycle == BackupLifecycle.RESTORE_PENDING) {
                    writeBackup(tvId, BackupRecord(snapshot, BackupLifecycle.ACTIVE))
                }
                PrivateDnsApplyResult.NOT_APPLIED
            }
        } else {
            writeBackup(tvId, BackupRecord(snapshot, BackupLifecycle.RESTORE_PENDING))
            PrivateDnsApplyResult.RESTORE_PENDING
        }
    }

    /** Restores the saved settings before removing the module; the caller retires the backup only after removal succeeds. */
    suspend fun restoreBeforeRemoval(tvId: TvId, session: TvSession): PrivateDnsRestoreResult = lock(tvId).withLock {
        when (val stored = readBackup(tvId)) {
            BackupState.Missing -> PrivateDnsRestoreResult.NO_BACKUP
            BackupState.Invalid -> PrivateDnsRestoreResult.FAILED
            is BackupState.Present -> when (stored.value.lifecycle) {
                BackupLifecycle.CONSUMED -> PrivateDnsRestoreResult.NO_BACKUP
                BackupLifecycle.ACTIVE, BackupLifecycle.RESTORE_PENDING -> if (restoreSettings(session, stored.value.snapshot)) {
                    PrivateDnsRestoreResult.RESTORED
                } else {
                    PrivateDnsRestoreResult.FAILED
                }
            }
        }
    }

    /** Marks the snapshot consumed before deletion, so a failed delete cannot seed a future install with stale settings. */
    suspend fun retireBackup(tvId: TvId): Boolean = lock(tvId).withLock { retireBackupLocked(tvId) }

    /** Physical deletion for a user-requested cleanup; normal module removal should use [retireBackup]. */
    suspend fun clearBackup(tvId: TvId): Boolean = lock(tvId).withLock { clearBackupLocked(tvId) }

    private suspend fun retireBackupLocked(tvId: TvId): Boolean = when (val stored = readBackup(tvId)) {
        BackupState.Missing -> true
        BackupState.Invalid -> false
        is BackupState.Present -> {
            val marked = writeBackup(tvId, stored.value.copy(lifecycle = BackupLifecycle.CONSUMED))
            val deleted = clearBackupLocked(tvId)
            marked || deleted
        }
    }

    private suspend fun applyDesired(session: TvSession): Boolean {
        if (!runOk(session, ShellRenderer.grantAdGuardDnsPermissionLine())) return false
        // Set the provider first; Private DNS starts using it only after mode becomes "hostname".
        if (!runOk(session, ShellRenderer.privateDnsPutLine(PrivateDnsSetting.SPECIFIER, ADGUARD_HOST))) return false
        if (!runOk(session, ShellRenderer.privateDnsPutLine(PrivateDnsSetting.MODE, HOSTNAME_MODE))) return false
        return readSettings(session) == ADGUARD_SETTINGS
    }

    private suspend fun restoreSettings(session: TvSession, target: PrivateDnsSnapshot): Boolean {
        val current = readSettings(session)
        if (current == target) return true

        // When restoring strict/hostname mode, install its provider before enabling it. For off or
        // opportunistic mode, switch modes first so a temporary strict provider is not left active.
        val order = if (target.mode == HOSTNAME_MODE) {
            listOf(PrivateDnsSetting.SPECIFIER, PrivateDnsSetting.MODE)
        } else {
            listOf(PrivateDnsSetting.MODE, PrivateDnsSetting.SPECIFIER)
        }
        for (setting in order) {
            val desired = when (setting) {
                PrivateDnsSetting.MODE -> target.mode
                PrivateDnsSetting.SPECIFIER -> target.specifier
            }
            val actual = current?.let {
                when (setting) {
                    PrivateDnsSetting.MODE -> it.mode
                    PrivateDnsSetting.SPECIFIER -> it.specifier
                }
            }
            if ((current == null || actual != desired) && !writeSetting(session, setting, desired)) return false
        }
        return readSettings(session) == target
    }

    private suspend fun writeSetting(session: TvSession, setting: PrivateDnsSetting, value: String?): Boolean {
        val line = if (value == null) ShellRenderer.privateDnsDeleteLine(setting)
        else ShellRenderer.privateDnsPutLine(setting, value)
        return runOk(session, line)
    }

    private suspend fun readSettings(session: TvSession): PrivateDnsSnapshot? {
        val mode = readSetting(session, PrivateDnsSetting.MODE) as? SettingValue.Ok ?: return null
        val specifier = readSetting(session, PrivateDnsSetting.SPECIFIER) as? SettingValue.Ok ?: return null
        return PrivateDnsSnapshot(mode.value, specifier.value)
    }

    private suspend fun readSetting(session: TvSession, setting: PrivateDnsSetting): SettingValue {
        val result = cancellableResult { TvShellRunner.run(session, ShellRenderer.privateDnsGetLine(setting), stdoutCap = MAX_SETTING_OUTPUT) }
            .getOrNull() ?: return SettingValue.Failed
        if (result.exit != 0 || result.truncated || result.stdout.length > MAX_SETTING_OUTPUT) return SettingValue.Failed
        var value = result.stdout
        while (value.endsWith('\n') || value.endsWith('\r')) value = value.dropLast(1)
        if ('\n' in value || '\r' in value || value.length > MAX_SETTING_VALUE) return SettingValue.Failed
        return SettingValue.Ok(if (value == "null") null else value)
    }

    private suspend fun runOk(session: TvSession, line: ShellLine): Boolean =
        cancellableResult { TvShellRunner.run(session, line) }.getOrNull()?.let { it.exit == 0 && !it.truncated } == true

    private suspend fun readBackup(tvId: TvId): BackupState {
        val bytes = try {
            documents.read(path(tvId)) { it.copyOf() } ?: return BackupState.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return BackupState.Invalid
        }
        val record = try { decode(bytes) } catch (_: Exception) { null } ?: return BackupState.Invalid
        return if (record.snapshot.isSupported()) BackupState.Present(record) else BackupState.Invalid
    }

    private suspend fun writeBackup(tvId: TvId, record: BackupRecord): Boolean =
        cancellableResult { documents.write(path(tvId), encode(record)) }.isSuccess

    private suspend fun clearBackupLocked(tvId: TvId): Boolean =
        cancellableResult { documents.delete(path(tvId)) }.isSuccess

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun path(id: TvId) = "tvs/${id.value}/private-dns-backup.bin"

    private fun lock(tvId: TvId): Mutex = locks.computeIfAbsent(tvId.value) { Mutex() }

    private data class PrivateDnsSnapshot(val mode: String?, val specifier: String?) {
        fun isSupported(): Boolean =
            (mode == null || mode in MODES) &&
                (specifier == null || (specifier.length <= MAX_HOST_LENGTH && HOST_RE.matches(specifier)))
    }

    private data class BackupRecord(val snapshot: PrivateDnsSnapshot, val lifecycle: BackupLifecycle)

    private enum class BackupLifecycle { ACTIVE, RESTORE_PENDING, CONSUMED }

    private sealed interface BackupState {
        data object Missing : BackupState
        data object Invalid : BackupState
        data class Present(val value: BackupRecord) : BackupState
    }

    private sealed interface SettingValue {
        data object Failed : SettingValue
        data class Ok(val value: String?) : SettingValue
    }

    private fun encode(record: BackupRecord): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(BACKUP_VERSION)
            writeNullable(out, record.snapshot.mode)
            writeNullable(out, record.snapshot.specifier)
            out.writeByte(record.lifecycle.ordinal)
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): BackupRecord {
        require(bytes.size <= MAX_BACKUP_BYTES) { "Private DNS backup is too large" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in LEGACY_BACKUP_VERSION..BACKUP_VERSION) { "Unsupported Private DNS backup version" }
            val snapshot = PrivateDnsSnapshot(readNullable(input), readNullable(input))
            val lifecycle = when (version) {
                LEGACY_BACKUP_VERSION -> BackupLifecycle.ACTIVE
                PREVIOUS_BACKUP_VERSION -> if (input.readBoolean()) BackupLifecycle.CONSUMED else BackupLifecycle.ACTIVE
                BACKUP_VERSION -> BackupLifecycle.entries.getOrNull(input.readUnsignedByte())
                    ?: error("Unknown Private DNS backup lifecycle")
                else -> error("Unsupported Private DNS backup version")
            }
            require(input.available() == 0) { "Unexpected trailing Private DNS backup data" }
            return BackupRecord(snapshot, lifecycle)
        }
    }

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private companion object {
        const val LEGACY_BACKUP_VERSION = 1
        const val PREVIOUS_BACKUP_VERSION = 2
        const val BACKUP_VERSION = 3
        const val MAX_BACKUP_BYTES = 1024
        const val MAX_SETTING_OUTPUT = 512
        const val MAX_SETTING_VALUE = 256
        const val MAX_HOST_LENGTH = 253
        const val HOSTNAME_MODE = "hostname"
        const val ADGUARD_HOST = "dns.adguard-dns.com"
        val MODES = setOf("off", "opportunistic", HOSTNAME_MODE)
        val HOST_RE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
        val ADGUARD_SETTINGS = PrivateDnsSnapshot(HOSTNAME_MODE, ADGUARD_HOST)
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

enum class PrivateDnsApplyResult { APPLIED, NOT_APPLIED, RESTORE_PENDING }
enum class PrivateDnsRestoreResult { RESTORED, NO_BACKUP, FAILED }
