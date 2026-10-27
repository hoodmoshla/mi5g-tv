package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.backup.AppSettingsBackupCodec
import tv.cleanyour.core.backup.AppSettingsBackupDocument
import tv.cleanyour.core.backup.AppSettingsBackupHistoryEntry
import tv.cleanyour.core.backup.AppSettingsBackupOperation
import tv.cleanyour.core.backup.AppSettingsBackupResult
import tv.cleanyour.core.backup.AppSettingsBackupStore
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.TvId

// M7 TV settings: name and room, level (D46: per TV), the address (read-only), "Forget this TV"
// (credential prompt; deletes the record, the journals and the restore points on this phone).
data class TvSettingsState(
    val name: String = "",
    val room: String? = null,
    val level: Level = Level.SIMPLE,
    val address: String? = null,
    val lastSeenAtMs: Long? = null,
    val confirmingForget: Boolean = false,
    val forgetting: Boolean = false,
)

sealed interface TvSettingsIntent {
    data class Rename(val name: String, val room: String?) : TvSettingsIntent
    data class SetLevel(val level: Level) : TvSettingsIntent
    data object AskForget : TvSettingsIntent
    data object CancelForget : TvSettingsIntent
    data object Forget : TvSettingsIntent
}

class TvSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<TvSettingsState, TvSettingsIntent, ManageEffect>(TvSettingsState(), scope) {

    private data class Loaded(val s: TvSettingsState)
    private data class Forgetting(val on: Boolean)

    init { scope.launch { d.tvs.tvs.collect { list -> list.firstOrNull { it.id == tvId }?.let { tv ->
        send(Loaded(TvSettingsState(tv.nickname, tv.room, tv.level, tv.endpoints.firstOrNull()?.host, tv.lastSeenAt)))
    } } } }

    override fun reduce(state: TvSettingsState, event: Any): TvSettingsState = when (event) {
        is Loaded -> event.s.copy(confirmingForget = state.confirmingForget, forgetting = state.forgetting)
        TvSettingsIntent.AskForget -> state.copy(confirmingForget = true)
        TvSettingsIntent.CancelForget -> state.copy(confirmingForget = false)
        is Forgetting -> state.copy(forgetting = event.on, confirmingForget = false)
        else -> state
    }

    override fun handle(event: Any, before: TvSettingsState, after: TvSettingsState) {
        when (event) {
            is TvSettingsIntent.Rename -> scope.launch {
                val name = event.name.trim().take(40)
                if (name.isNotEmpty()) d.tv(tvId)?.let { d.tvs.upsert(it.copy(nickname = name, room = event.room?.trim()?.take(40)?.ifEmpty { null })) }
            }
            // "Back to Simple hides the extra options. Your TV stays as it is." (UX 3)
            is TvSettingsIntent.SetLevel -> scope.launch { d.tv(tvId)?.let { d.tvs.upsert(it.copy(level = event.level)) } }
            TvSettingsIntent.Forget -> if (!before.forgetting) scope.launch { forget() }
            else -> {}
        }
    }

    private suspend fun forget() {
        val tv = d.tv(tvId) ?: return
        // A run still at work on this TV (step R included) is never cut off from here: its journal is
        // what puts the TV's own home screen back. Wait for it, or stop it first.
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        d.gate.pass(tvId, tv.nickname) ?: return send(Forgetting(false))
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        send(Forgetting(true))
        // D7: closing the session ends it, so debugging goes off first if a write ran this session.
        attempt { d.sessionEnd.end(tvId) }
        attempt { d.sessions.existing(tvId)?.close() }
        d.documents.deleteTree("tvs/${tvId.value}")
        d.ledger.forget(tvId)
        d.gate.forget(tvId)
        d.tvs.forget(tvId)
        // Issue #2: the engine drops its in-memory run for this TV once the journal is gone (an
        // abandon of a forgotten run needs no TV), so a stuck setup ends here without a force stop
        // and a new Setup is not refused RUN_OPEN. After the record is gone, so nothing reconnects.
        attempt { d.engine.abandon(tvId) }
        send(Forgetting(false))
        effect(ManageEffect.Navigate("home"))
    }
}

// M8 App settings.
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Available(val version: String) : UpdateCheck
    data object Latest : UpdateCheck
    data object Failed : UpdateCheck
}

data class AppSettingsState(
    val version: String = "",
    val prefs: AppPrefsValue = AppPrefsValue(),
    val launcherPkg: String = "",
    val launcherVersion: String = "",
    val launcherDigest: String = "",
    val keyBacking: String = "",
    val keyFingerprint: String = "",
    val update: UpdateCheck = UpdateCheck.Idle,
    // STORES-PLAN 1: "Check for updates" exists only in the GitHub and F-Droid build, and the
    // privacy line names GitHub only when the app can reach it (an update check or an add-on).
    val updateCheck: Boolean = true,
    val addOns: Boolean = true,
    val confirm: AppConfirm? = null,
    val working: Boolean = false,
    val backupStatus: SettingsBackupStatus = SettingsBackupStatus.IDLE,
    val backupDraft: AppSettingsBackupDocument? = null,
    val backupHistory: List<AppSettingsBackupHistoryEntry> = emptyList(),
    val canUndoSettingsImport: Boolean = false,
)

enum class SettingsBackupStatus { IDLE, EXPORTING, EXPORTED, IMPORTING, PREVIEW_READY, APPLYING, IMPORTED, UNDONE, INVALID, FAILED, UNDOING }

enum class AppConfirm { RESET_KEY, DELETE_EVERYTHING }

sealed interface AppSettingsIntent {
    data class SetHaptics(val on: Boolean) : AppSettingsIntent
    data class SetLargeText(val on: Boolean) : AppSettingsIntent
    data object CheckUpdates : AppSettingsIntent
    data object ReportProblem : AppSettingsIntent     // 0.3.2 B2: the report preview, its own screen
    data class Ask(val what: AppConfirm) : AppSettingsIntent
    data object CancelConfirm : AppSettingsIntent
    data object ResetKey : AppSettingsIntent
    data object DeleteEverything : AppSettingsIntent
    data object ExportSettingsStarted : AppSettingsIntent
    data class ExportSettingsFinished(val result: AppSettingsBackupResult) : AppSettingsIntent
    data object ImportSettingsStarted : AppSettingsIntent
    data object ImportSettingsCancelled : AppSettingsIntent
    data object ImportSettingsReadFailed : AppSettingsIntent
    data class ImportSettingsFile(val bytes: ByteArray) : AppSettingsIntent
    data object ConfirmSettingsImport : AppSettingsIntent
    data object CancelSettingsImport : AppSettingsIntent
    data object UndoSettingsImport : AppSettingsIntent
}

// A run at work on the TV: running, waiting for the person's answer on the check screen, putting
// the TV's own home screen back (step R) or re-reading the step it was in doubt about. Forget this TV
// and Delete everything wait for it: they would delete the journal it is writing.
internal fun Engine.isWorking(tvId: TvId): Boolean = when (runState(tvId).value) {
    is RunState.Running, is RunState.AwaitingConfirm, is RunState.RollingBack, is RunState.InDoubt -> true
    else -> false
}

// The app-level actions are gated against a placeholder TV id: the gate is per TV (5.13) and
// "Reset my key" and "Delete everything" belong to no single TV.
val APP_GATE_ID = TvId("0000000000000000")

class AppSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
) : Machine<AppSettingsState, AppSettingsIntent, ManageEffect>(AppSettingsState(updateCheck = d.store.updateCheck, addOns = d.store.addOns), scope) {

    private val settingsBackup = AppSettingsBackupManager(d.appPrefs, AppSettingsBackupStore(d.documents), d.clock, d.appVersion)
    private data class Loaded(val s: AppSettingsState)
    private data class Update(val u: UpdateCheck)
    private data class Working(val on: Boolean)
    private data class BackupUpdated(
        val status: SettingsBackupStatus,
        val draft: AppSettingsBackupDocument?,
        val history: List<AppSettingsBackupHistoryEntry>,
        val canUndo: Boolean,
        val prefs: AppPrefsValue? = null,
        val working: Boolean? = null,
    )

    init {
        scope.launch {
            val pin = attempt { d.launcherPin.bundled() }.getOrNull()
            val history = attempt { settingsBackup.history() }.getOrDefault(emptyList())
            val canUndo = attempt { settingsBackup.canUndo() }.getOrDefault(false)
            send(Loaded(AppSettingsState(
                version = d.appVersion, prefs = d.appPrefs.load(),
                launcherPkg = pin?.pkg?.value.orEmpty(), launcherVersion = pin?.versionName.orEmpty(),
                launcherDigest = pin?.let { keyDigest12(it.sha256) }.orEmpty(),
                keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""), keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""),
                updateCheck = d.store.updateCheck, addOns = d.store.addOns,
                backupHistory = history, canUndoSettingsImport = canUndo,
            )))
        }
    }

    override fun reduce(state: AppSettingsState, event: Any): AppSettingsState = when (event) {
        is Loaded -> event.s.copy(update = state.update, confirm = state.confirm)
        is AppSettingsIntent.SetHaptics -> state.copy(prefs = state.prefs.copy(haptics = event.on))
        is AppSettingsIntent.SetLargeText -> state.copy(prefs = state.prefs.copy(largeTextFollowsSystem = event.on))
        AppSettingsIntent.CheckUpdates -> if (state.updateCheck) state.copy(update = UpdateCheck.Checking) else state
        is Update -> state.copy(update = event.u)
        is AppSettingsIntent.Ask -> state.copy(confirm = event.what)
        AppSettingsIntent.CancelConfirm -> state.copy(confirm = null)
        AppSettingsIntent.ExportSettingsStarted -> if (state.working) state else state.copy(backupStatus = SettingsBackupStatus.EXPORTING, backupDraft = null, working = true)
        AppSettingsIntent.ImportSettingsStarted -> if (state.working) state else state.copy(backupStatus = SettingsBackupStatus.IMPORTING, backupDraft = null, working = true)
        AppSettingsIntent.ImportSettingsCancelled -> if (state.backupStatus == SettingsBackupStatus.IMPORTING) state.copy(backupStatus = SettingsBackupStatus.IDLE, backupDraft = null, working = false) else state
        AppSettingsIntent.ImportSettingsReadFailed -> if (state.backupStatus == SettingsBackupStatus.IMPORTING) state.copy(backupStatus = SettingsBackupStatus.FAILED, backupDraft = null, working = false) else state
        AppSettingsIntent.CancelSettingsImport -> if (state.backupStatus == SettingsBackupStatus.PREVIEW_READY) state.copy(backupStatus = SettingsBackupStatus.IDLE, backupDraft = null, working = false) else state
        is BackupUpdated -> state.copy(
            backupStatus = event.status, backupDraft = event.draft, backupHistory = event.history,
            canUndoSettingsImport = event.canUndo, prefs = event.prefs ?: state.prefs,
            working = event.working ?: state.working,
        )
        is Working -> state.copy(working = event.on, confirm = null)
        else -> state
    }

    override fun handle(event: Any, before: AppSettingsState, after: AppSettingsState) {
        when (event) {
            is AppSettingsIntent.SetHaptics, is AppSettingsIntent.SetLargeText -> scope.launch { d.appPrefs.save(after.prefs) }
            AppSettingsIntent.CheckUpdates -> if (d.store.updateCheck && before.update != UpdateCheck.Checking) scope.launch {
                val v = attempt { d.updates.latestNewerThan(d.appVersion) }.getOrNull()
                send(Update(when { v == null -> UpdateCheck.Failed; v.isEmpty() -> UpdateCheck.Latest; else -> UpdateCheck.Available(v) }))
            }
            AppSettingsIntent.ReportProblem -> effect(ManageEffect.Navigate("report/problem"))
            AppSettingsIntent.ResetKey -> if (!before.working) scope.launch { resetKey() }
            AppSettingsIntent.DeleteEverything -> if (!before.working) scope.launch { deleteEverything() }
            AppSettingsIntent.ExportSettingsStarted -> Unit
            is AppSettingsIntent.ExportSettingsFinished -> if (before.backupStatus == SettingsBackupStatus.EXPORTING) scope.launch { finishSettingsExport(event.result) }
            AppSettingsIntent.ImportSettingsStarted -> Unit
            AppSettingsIntent.ImportSettingsCancelled -> if (before.backupStatus == SettingsBackupStatus.IMPORTING) scope.launch { finishSettingsImport(AppSettingsBackupResult.CANCELLED, SettingsBackupStatus.IDLE) }
            AppSettingsIntent.ImportSettingsReadFailed -> if (before.backupStatus == SettingsBackupStatus.IMPORTING) scope.launch { finishSettingsImport(AppSettingsBackupResult.FAILED, SettingsBackupStatus.FAILED) }
            AppSettingsIntent.CancelSettingsImport -> if (before.backupStatus == SettingsBackupStatus.PREVIEW_READY) scope.launch { finishSettingsImport(AppSettingsBackupResult.CANCELLED, SettingsBackupStatus.IDLE) }
            is AppSettingsIntent.ImportSettingsFile -> if (before.backupStatus == SettingsBackupStatus.IMPORTING) scope.launch { previewSettingsImport(event.bytes) }
            AppSettingsIntent.ConfirmSettingsImport -> if (!before.working && before.backupStatus == SettingsBackupStatus.PREVIEW_READY) {
                before.backupDraft?.let { draft -> scope.launch { applySettingsImport(draft) } }
            }
            AppSettingsIntent.UndoSettingsImport -> if (!before.working && before.canUndoSettingsImport) scope.launch { undoSettingsImport() }
            else -> {}
        }
    }

    suspend fun exportSettingsBackup(): ByteArray = settingsBackup.exportBytes()

    private suspend fun finishSettingsExport(result: AppSettingsBackupResult) {
        val status = when (result) {
            AppSettingsBackupResult.SUCCESS -> SettingsBackupStatus.EXPORTED
            AppSettingsBackupResult.CANCELLED -> SettingsBackupStatus.IDLE
            else -> SettingsBackupStatus.FAILED
        }
        attempt { settingsBackup.record(AppSettingsBackupOperation.EXPORT, result) }
        refreshBackup(status, working = false)
    }

    private suspend fun finishSettingsImport(result: AppSettingsBackupResult, status: SettingsBackupStatus) {
        attempt { settingsBackup.record(AppSettingsBackupOperation.IMPORT, result) }
        refreshBackup(status, working = false)
    }

    private suspend fun previewSettingsImport(bytes: ByteArray) {
        val draft = try {
            AppSettingsBackupCodec.decode(bytes)
        } catch (_: Exception) {
            attempt { settingsBackup.record(AppSettingsBackupOperation.IMPORT, AppSettingsBackupResult.REJECTED) }
            refreshBackup(SettingsBackupStatus.INVALID, working = false)
            return
        }
        refreshBackup(SettingsBackupStatus.PREVIEW_READY, draft, working = false)
    }

    private suspend fun applySettingsImport(draft: AppSettingsBackupDocument) {
        send(BackupUpdated(SettingsBackupStatus.APPLYING, draft, state.value.backupHistory, state.value.canUndoSettingsImport, working = true))
        try {
            settingsBackup.apply(draft)
            refreshBackup(SettingsBackupStatus.IMPORTED, prefs = d.appPrefs.load(), working = false)
        } catch (_: Exception) {
            refreshBackup(SettingsBackupStatus.FAILED, working = false)
        }
    }

    private suspend fun undoSettingsImport() {
        send(BackupUpdated(SettingsBackupStatus.UNDOING, null, state.value.backupHistory, state.value.canUndoSettingsImport, working = true))
        try {
            val restored = settingsBackup.undo()
            if (restored == null) refreshBackup(SettingsBackupStatus.FAILED, working = false)
            else refreshBackup(SettingsBackupStatus.UNDONE, prefs = d.appPrefs.load(), working = false)
        } catch (_: Exception) {
            refreshBackup(SettingsBackupStatus.FAILED, working = false)
        }
    }

    private suspend fun refreshBackup(
        status: SettingsBackupStatus,
        draft: AppSettingsBackupDocument? = null,
        prefs: AppPrefsValue? = null,
        working: Boolean? = null,
    ) {
        val current = state.value
        val history = attempt { settingsBackup.history() }.getOrDefault(current.backupHistory)
        val canUndo = attempt { settingsBackup.canUndo() }.getOrDefault(current.canUndoSettingsImport)
        send(BackupUpdated(status, draft, history, canUndo, prefs, working))
    }

    // Delete everything waits for a run at work on any TV, as Forget this TV does.
    private fun refusedWhileWorking(): Boolean {
        val busy = d.tvs.tvs.value.firstOrNull { d.engine.isWorking(it.id) } ?: return false
        send(Working(false))
        effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY_ANY, busy.nickname, undo = false))
        return true
    }

    // "Reset my key" (F2): every TV asks to trust this phone again.
    private suspend fun resetKey() {
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_RESET_KEY), forcePrompt = true) ?: return send(Working(false))
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        attempt { d.keyInfo.reset() }
        d.gate.clear()
        send(Working(false))
        send(Loaded(state.value.copy(keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""), keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""))))
    }

    // "Delete everything" (F13): every TV record, journal, restore point and cached download, the key,
    // and the app's diagnostics log.
    private suspend fun deleteEverything() {
        if (refusedWhileWorking()) return
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_DELETE_ALL), forcePrompt = true) ?: return send(Working(false))
        if (refusedWhileWorking()) return
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        for (tv in d.tvs.tvs.value) {
            d.documents.deleteTree("tvs/${tv.id.value}")
            d.tvs.forget(tv.id)
            attempt { d.engine.abandon(tv.id) }   // as in Forget this TV: the engine drops a run whose journal is gone
        }
        attempt { d.documents.deleteTree("registry") }
        attempt { d.documents.delete("settings.txt") }
        attempt { d.documents.delete(AppSettingsBackupStore.HISTORY_PATH) }
        attempt { d.documents.delete(AppSettingsBackupStore.ROLLBACK_PATH) }
        attempt { d.keyInfo.reset() }
        attempt { d.diagnostics.clear() }   // the app's own diagnostics log goes too
        d.gate.clear()
        send(Working(false))
        effect(ManageEffect.Navigate("welcome"))
    }
}
