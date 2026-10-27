package tv.cleanyour.core.flows

import tv.cleanyour.core.backup.AppSettingsBackupCodec
import tv.cleanyour.core.backup.AppSettingsBackupDocument
import tv.cleanyour.core.backup.AppSettingsBackupHistoryEntry
import tv.cleanyour.core.backup.AppSettingsBackupOperation
import tv.cleanyour.core.backup.AppSettingsPreferences
import tv.cleanyour.core.backup.AppSettingsBackupResult
import tv.cleanyour.core.backup.AppSettingsBackupStore
import tv.cleanyour.core.store.Clock

/** Coordinates portable backups of this app's two user-facing preferences; it never includes TV records or keys. */
class AppSettingsBackupManager(
    private val preferences: AppPrefs,
    private val store: AppSettingsBackupStore,
    private val clock: Clock,
    private val appVersion: String,
) {
    suspend fun exportBytes(): ByteArray = AppSettingsBackupCodec.encode(
        appVersion = appVersion,
        createdAtMs = clock.nowMs(),
        preferences = preferences.load().toBackupPreferences(),
    )

    suspend fun apply(document: AppSettingsBackupDocument) {
        val before = preferences.load()
        val now = clock.nowMs()
        var applied = false
        try {
            store.saveRollback(
                AppSettingsBackupDocument(appVersion, now, before.toBackupPreferences()),
            )
            preferences.save(document.preferences.toAppPrefs())
            applied = true
            store.record(AppSettingsBackupHistoryEntry(now, AppSettingsBackupOperation.IMPORT, AppSettingsBackupResult.SUCCESS))
        } catch (failure: Exception) {
            if (applied) runCatching { preferences.save(before) }
            runCatching { store.record(AppSettingsBackupHistoryEntry(clock.nowMs(), AppSettingsBackupOperation.IMPORT, AppSettingsBackupResult.FAILED)) }
            throw failure
        }
    }

    suspend fun undo(): AppSettingsPreferences? {
        val rollback = store.loadRollback() ?: return null
        val current = preferences.load()
        preferences.save(rollback.preferences.toAppPrefs())
        try {
            store.record(AppSettingsBackupHistoryEntry(clock.nowMs(), AppSettingsBackupOperation.ROLLBACK, AppSettingsBackupResult.SUCCESS))
            store.clearRollback()
        } catch (failure: Exception) {
            runCatching { preferences.save(current) }
            throw failure
        }
        return rollback.preferences
    }

    suspend fun history(): List<AppSettingsBackupHistoryEntry> = store.history()

    suspend fun canUndo(): Boolean = runCatching { store.loadRollback() != null }.getOrDefault(false)

    suspend fun record(operation: AppSettingsBackupOperation, result: AppSettingsBackupResult) {
        store.record(AppSettingsBackupHistoryEntry(clock.nowMs(), operation, result))
    }
}

private fun AppPrefsValue.toBackupPreferences() = tv.cleanyour.core.backup.AppSettingsPreferences(
    haptics = haptics,
    largeTextFollowsSystem = largeTextFollowsSystem,
)

private fun AppSettingsPreferences.toAppPrefs() = AppPrefsValue(
    haptics = haptics,
    largeTextFollowsSystem = largeTextFollowsSystem,
)
