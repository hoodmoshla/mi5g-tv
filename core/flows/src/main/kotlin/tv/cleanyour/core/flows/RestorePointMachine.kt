package tv.cleanyour.core.flows

import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.backup.ReadBundle
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest

// M6 Restore point (UX 4.13, F10): Undo everything with its preview, the one outlined danger button
// inside its confirmation sheet, the credential prompt, narration, and the end line; "Save a copy"
// as a SAF zip; "Before a factory reset" on Google TV. Imported copies use the same typed undo plan,
// bound to the TV id and the preview/confirmation flow; archived scripts are never executed.
data class UndoPreview(
    val plan: Plan,
    val thingsBack: Int,
    val stockName: String?,
    val wizardBack: Boolean,
    val removeLauncher: Boolean,
    val importedChanges: List<ChangeRecord>? = null,
    val actions: List<RestoreBundleAction> = emptyList(),
)

enum class RestoreBundleActionKind { ENABLE_PACKAGE, DISABLE_PACKAGE, DEFAULT_PACKAGE, REMOVE_APP, SETTING, HOME, VERIFY_HOME, SHOW_HOME, REMOVE_FILE, RESTART_HOME, OTHER }
data class RestoreBundleAction(val kind: RestoreBundleActionKind, val target: String)
data class RestoreBundleInfo(
    val model: String,
    val appVersion: String,
    val createdAtMs: Long,
    val baselineAtMs: Long,
    val changes: Int,
    val ignoredApps: List<String> = emptyList(),
    val canRemoveLauncher: Boolean = false,
    val ignoredFiles: List<String> = emptyList(),
)

enum class RestoreBundleIssue { INVALID, WRONG_TV, EMPTY, TOO_MANY_CHANGES, NO_EFFECTIVE_ACTIONS, PREVIEW_FAILED }

private const val MAX_IMPORTED_BUNDLE_CHANGES = 1_000

sealed interface RestorePhase {
    data object Idle : RestorePhase
    data object Previewing : RestorePhase
    data class Preview(val preview: UndoPreview, val confirming: Boolean) : RestorePhase
    data class BundlePreview(val preview: UndoPreview, val info: RestoreBundleInfo, val confirming: Boolean) : RestorePhase
    data class BundleError(val issue: RestoreBundleIssue) : RestorePhase
    data class Working(val kind: String, val percent: Int, val lines: List<String>) : RestorePhase   // kind: "undo" or "factory"
    data class Finished(val kind: String, val atMs: Long) : RestorePhase
    data object Failed : RestorePhase
}

data class RestorePointState(
    val tvName: String = "",
    val baselineAtMs: Long? = null,
    val latestAtMs: Long? = null,
    val googleTv: Boolean = false,
    val phase: RestorePhase = RestorePhase.Idle,
    val exporting: Boolean = false,
    val importingBundle: Boolean = false,
)

sealed interface RestorePointIntent {
    data object PreviewUndo : RestorePointIntent
    data class RemoveLauncher(val remove: Boolean) : RestorePointIntent
    data object Confirm : RestorePointIntent           // opens the confirmation sheet
    data object PutEverythingBack : RestorePointIntent
    data object Cancel : RestorePointIntent
    data object SaveCopy : RestorePointIntent
    data object ImportCopy : RestorePointIntent
    data object BeforeFactoryReset : RestorePointIntent
    data object CleanAgain : RestorePointIntent
}

class RestorePointMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<RestorePointState, RestorePointIntent, ManageEffect>(RestorePointState(), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val name: String, val baseline: Long?, val latest: Long?, val google: Boolean)
    private data class Phase(val p: RestorePhase)
    private data class Exporting(val on: Boolean)
    private data class ImportingBundle(val on: Boolean)

    init { scope.launch { load() } }

    override fun reduce(state: RestorePointState, event: Any): RestorePointState = when (event) {
        is Loaded -> state.copy(tvName = event.name, baselineAtMs = event.baseline, latestAtMs = event.latest, googleTv = event.google)
        is Phase -> state.copy(phase = event.p)
        is Exporting -> state.copy(exporting = event.on)
        is ImportingBundle -> state.copy(importingBundle = event.on)
        RestorePointIntent.PreviewUndo -> state.copy(phase = RestorePhase.Previewing)
        RestorePointIntent.Confirm -> when (val phase = state.phase) {
            is RestorePhase.Preview -> state.copy(phase = phase.copy(confirming = true))
            is RestorePhase.BundlePreview -> state.copy(phase = phase.copy(confirming = true))
            else -> state
        }
        RestorePointIntent.ImportCopy -> if (!state.importingBundle && !state.exporting && state.phase !is RestorePhase.Working) {
            state.copy(importingBundle = true)
        } else state
        RestorePointIntent.Cancel -> state.copy(phase = RestorePhase.Idle, importingBundle = false)
        else -> state
    }

    override fun handle(event: Any, before: RestorePointState, after: RestorePointState) {
        when (event) {
            RestorePointIntent.PreviewUndo -> scope.launch { preview(removeLauncher = false) }
            is RestorePointIntent.RemoveLauncher -> when (val phase = before.phase) {
                is RestorePhase.Preview -> scope.launch { preview(event.remove) }
                is RestorePhase.BundlePreview -> phase.preview.importedChanges?.let { changes ->
                    scope.launch { preview(event.remove && phase.info.canRemoveLauncher, changes, phase.info) }
                }
                else -> Unit
            }
            RestorePointIntent.PutEverythingBack -> when (val phase = before.phase) {
                is RestorePhase.Preview -> if (phase.confirming) scope.launch { undoEverything(phase.preview) }
                is RestorePhase.BundlePreview -> if (phase.confirming) scope.launch { undoEverything(phase.preview, phase.info) }
                else -> Unit
            }
            RestorePointIntent.SaveCopy -> if (!before.exporting && !before.importingBundle) d.tv(tvId)?.let { tv ->
                val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date(d.clock.nowMs()))
                val model = tv.facts.model.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
                effect(ManageEffect.CreateDocument("cleanyourtv-restore-$model-$date.zip"))
            }
            RestorePointIntent.ImportCopy -> if (after.importingBundle && !before.importingBundle) effect(ManageEffect.PickRestoreBundle)
            RestorePointIntent.BeforeFactoryReset -> scope.launch { factoryReset() }
            RestorePointIntent.CleanAgain -> effect(ManageEffect.Navigate("setup/${tvId.value}"))
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        send(Loaded(tv.nickname, d.snapshots.baseline(tvId)?.takenAtMs, d.snapshots.latest(tvId)?.takenAtMs, profile?.platform == Platform.GOOGLE_TV))
    }

    // The inverse is computed from journals (or a validated, same-TV bundle), never from "enable
    // everything disabled", so things the owner switched off before the app stay off (ARCHITECTURE 2.4).
    private suspend fun preview(removeLauncher: Boolean, importedChanges: List<ChangeRecord>? = null, info: RestoreBundleInfo? = null, fresh: Boolean = false) {
        val changes = importedChanges ?: d.ledger.changes(tvId)
        val plan = writer.preview(tvId, PlanRequest.UndoEverything(changes, removeLauncher), fresh = fresh)
        if (plan == null) {
            send(Phase(if (info != null) RestorePhase.BundleError(RestoreBundleIssue.PREVIEW_FAILED) else RestorePhase.Failed))
            return
        }
        if (info != null && plan.steps.isEmpty()) {
            send(Phase(RestorePhase.BundleError(RestoreBundleIssue.NO_EFFECTIVE_ACTIONS)))
            return
        }
        val tv = d.tv(tvId)
        val profile = tv?.let { d.profileOf(it) }
        val stock = profile?.stockLaunchers?.firstOrNull()?.pkg
        val wraith = profile?.setupWraith
        val back = plan.steps.count { it.op is Op.Enable || it.op is Op.DefaultState }
        val wizard = wraith != null && plan.steps.any { (it.op as? Op.Enable)?.pkg == wraith }
        val actions = if (info != null) plan.steps.map { describeBundleAction(it.op) } else emptyList()
        val result = UndoPreview(plan, back, stock?.let { d.nameOf(it) }, wizard, removeLauncher, importedChanges?.toList(), actions)
        send(Phase(if (info == null) RestorePhase.Preview(result, confirming = false) else RestorePhase.BundlePreview(result, info, confirming = false)))
    }

    private fun describeBundleAction(op: Op): RestoreBundleAction = when (op) {
        is Op.Enable -> RestoreBundleAction(RestoreBundleActionKind.ENABLE_PACKAGE, op.pkg.value)
        is Op.Disable -> RestoreBundleAction(RestoreBundleActionKind.DISABLE_PACKAGE, op.pkg.value)
        is Op.DefaultState -> RestoreBundleAction(RestoreBundleActionKind.DEFAULT_PACKAGE, op.pkg.value)
        is Op.Uninstall -> RestoreBundleAction(RestoreBundleActionKind.REMOVE_APP, op.pkg.value)
        is Op.SettingsPut -> RestoreBundleAction(RestoreBundleActionKind.SETTING, "${op.key.key} = ${op.value ?: "(not set)"}")
        is Op.SetHome -> RestoreBundleAction(RestoreBundleActionKind.HOME, op.component.flat)
        is Op.VerifyHome -> RestoreBundleAction(RestoreBundleActionKind.VERIFY_HOME, op.expected.flat)
        Op.PressHome -> RestoreBundleAction(RestoreBundleActionKind.SHOW_HOME, "")
        is Op.RemoveFile -> RestoreBundleAction(RestoreBundleActionKind.REMOVE_FILE, op.path.absolute)
        is Op.RestartHome -> RestoreBundleAction(RestoreBundleActionKind.RESTART_HOME, op.component.flat)
        else -> RestoreBundleAction(RestoreBundleActionKind.OTHER, op::class.simpleName ?: "operation")
    }

    private fun installedAppPackage(op: Op) = when (op) {
        is Op.Install -> op.pkg
        is Op.InstallStream -> op.pkg
        is Op.Uninstall -> op.pkg
        else -> null
    }

    private fun importedFilePath(op: Op): RemotePath? = when (op) {
        is Op.PushAsset -> op.dest
        is Op.RemoveFile -> op.path
        else -> null
    }

    private fun importedChangeIsUnsafe(change: ChangeRecord): Boolean {
        val pkg = installedAppPackage(change.op)
        // The archive is unsigned, so even our pinned launcher's package name cannot prove who
        // installed it. Never allow a ZIP record to authorize uninstalling any app.
        if (change.prior?.present == false && pkg != null) return true
        val path = importedFilePath(change.op) ?: return false
        // Unsigned archives cannot prove bytes previously belonged on the TV. Never push those bytes
        // back, and only permit file removals/restores inside the pinned launcher's app-owned overlay.
        return path.dir != RemoteDir.OVERLAY || change.prior?.overlay != null
    }

    // Only a reader-validated bundle reaches this method. Its identity and embedded baseline must
    // both match the selected TV before any plan is created. The manifest is integrity-checked, not
    // cryptographically signed; the UI says so and the user reviews the generated plan before apply.
    suspend fun previewImportedBundle(bundle: ReadBundle) {
        send(ImportingBundle(false))
        if (bundle.tvId != tvId || bundle.baseline.tvId != tvId) {
            send(Phase(RestorePhase.BundleError(RestoreBundleIssue.WRONG_TV)))
            return
        }
        if (bundle.changes.isEmpty()) {
            send(Phase(RestorePhase.BundleError(RestoreBundleIssue.EMPTY)))
            return
        }
        if (bundle.changes.size > MAX_IMPORTED_BUNDLE_CHANGES) {
            send(Phase(RestorePhase.BundleError(RestoreBundleIssue.TOO_MANY_CHANGES)))
            return
        }
        val introducedApps = bundle.changes.filter { it.prior?.present == false }.mapNotNull { installedAppPackage(it.op) }.toSet()
        val ignoredApps = introducedApps.map { it.value }.sorted()
        // An unsigned ZIP cannot prove that it installed any app, including this app's launcher.
        // Imported restores therefore never uninstall apps; local journal-based undo remains intact.
        val unsafeChanges = bundle.changes.filter { importedChangeIsUnsafe(it) }
        val safeChanges = bundle.changes.filterNot { importedChangeIsUnsafe(it) }
        if (safeChanges.isEmpty()) {
            send(Phase(RestorePhase.BundleError(RestoreBundleIssue.NO_EFFECTIVE_ACTIONS)))
            return
        }
        val ignoredFiles = unsafeChanges.mapNotNull { importedFilePath(it.op)?.absolute }.distinct().sorted()
        val info = RestoreBundleInfo(bundle.model, bundle.appVersion, bundle.createdAtMs, bundle.baseline.takenAtMs,
            bundle.changes.size, ignoredApps, canRemoveLauncher = false, ignoredFiles = ignoredFiles)
        preview(removeLauncher = false, importedChanges = safeChanges, info = info)
    }

    suspend fun importBundleReadFailed() {
        send(ImportingBundle(false))
        send(Phase(RestorePhase.BundleError(RestoreBundleIssue.INVALID)))
    }

    suspend fun importBundleCancelled() { send(ImportingBundle(false)) }

    private suspend fun undoEverything(p: UndoPreview, info: RestoreBundleInfo? = null) {
        send(Phase(RestorePhase.Working("undo", 0, emptyList())))
        val changes = p.importedChanges ?: d.ledger.changes(tvId)
        val r = writer.write(tvId, PlanRequest.UndoEverything(changes, p.removeLauncher), onBusy = { b ->
            if (b is Busy.Working) send(Phase(RestorePhase.Working("undo", b.percent, b.lines)))
        }, expectedPlan = p.plan)
        when (r) {
            is WriteResult.Done -> {
                d.tv(tvId)?.let { d.tvs.upsert(it.copy(launcher = if (p.removeLauncher) null else it.launcher)) }
                val restoredAt = info?.baselineAtMs?.takeIf { it > 0L } ?: info?.createdAtMs ?: d.snapshots.baseline(tvId)?.takenAtMs ?: d.clock.nowMs()
                send(Phase(RestorePhase.Finished("undo", restoredAt)))
                effect(ManageEffect.HapticDouble)
            }
            WriteResult.GateCancelled, WriteResult.NoTv, is WriteResult.Paused -> {
                val phase = if (info == null) RestorePhase.Preview(p, confirming = false) else RestorePhase.BundlePreview(p, info, confirming = false)
                send(Phase(phase))   // Paused: the open-run card (O2)
            }
            WriteResult.StalePreview -> preview(p.removeLauncher, p.importedChanges, info, fresh = true)
            else -> send(Phase(RestorePhase.Failed))
        }
        load()
    }

    private suspend fun factoryReset() {
        send(Phase(RestorePhase.Working("factory", 0, emptyList())))
        val r = writer.write(tvId, PlanRequest.BeforeFactoryReset, onBusy = { b ->
            if (b is Busy.Working) send(Phase(RestorePhase.Working("factory", b.percent, b.lines)))
        })
        send(Phase(if (r is WriteResult.Done) RestorePhase.Finished("factory", d.clock.nowMs()) else if (r is WriteResult.GateCancelled || r is WriteResult.NoTv || r is WriteResult.Paused) RestorePhase.Idle else RestorePhase.Failed))
    }

    // Called by the app with the stream SAF opened for the CreateDocument effect.
    suspend fun writeBundle(sink: OutputStream): Boolean {
        val tv = d.tv(tvId) ?: return false
        send(Exporting(true))
        val ok = attempt {
            val baseline = d.snapshots.baseline(tvId) ?: d.snapshots.latest(tvId) ?: error("no restore point yet")
            d.bundleWriter.write(tv, baseline, d.ledger.changes(tvId), overlay = null, wallpaper = null, sink = sink)
        }.isSuccess
        send(Exporting(false))
        effect(ManageEffect.Snackbar(if (ok) SnackKind.EXPORT_SAVED else SnackKind.EXPORT_FAILED, "", false))
        return ok
    }
}
