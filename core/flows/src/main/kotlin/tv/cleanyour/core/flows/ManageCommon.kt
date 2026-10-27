package tv.cleanyour.core.flows

import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.discovery.Discovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.core.store.TvRepository
import tv.cleanyour.core.trust.ApkInspector
import tv.cleanyour.core.trust.LauncherPin

// One-shot effects of the Manage family (M1 to M8). Snackbars carry a kind and a name; the screen
// turns them into the sentence from strings.xml, so the machines stay free of copy.
sealed interface ManageEffect {
    data class Navigate(val key: String) : ManageEffect
    data class Snackbar(val kind: SnackKind, val name: String, val undo: Boolean, val pkg: PackageName? = null) : ManageEffect
    data class Share(val text: String) : ManageEffect
    data class CreateDocument(val suggestedName: String) : ManageEffect   // SAF ACTION_CREATE_DOCUMENT
    data class OpenUrl(val url: String) : ManageEffect
    data class Copy(val text: String) : ManageEffect                     // to the clipboard (0.3.2 B1, B2)
    data object HapticLight : ManageEffect
    data object HapticDouble : ManageEffect
    data object PickRestoreBundle : ManageEffect                         // SAF ACTION_OPEN_DOCUMENT for a restore zip
    data object PickApk : ManageEffect
    data object PickCastMedia : ManageEffect
    data class SharePng(val png: ByteArray, val fileName: String) : ManageEffect
    data class ShareApk(val apkBytes: ByteArray, val fileName: String) : ManageEffect
    data class DownloadUrlApk(val url: String, val fileName: String) : ManageEffect
}

enum class SnackKind { PUT_BACK, SWITCHED_OFF, HOME_SAVED, MODULE_REMOVED, EXPORT_SAVED, EXPORT_FAILED, FAILED, HOME_UPDATED,
    KEPT_TV_INPUT,    // RF-LAUNCHER-250 F1: a switch-off the live check kept because it runs the TV's inputs
    KEPT_ON,          // issue #10: a switch-off the TV kept on (or the live check refused); nothing changed
    RUN_BUSY,         // Forget this TV refused: setup is still working on this TV
    RUN_BUSY_ANY,     // Delete everything refused: setup is still working on the TV named
    PRIVATE_DNS_NOT_APPLIED, PRIVATE_DNS_RESTORE_PENDING, PRIVATE_DNS_RESTORE_FAILED, PRIVATE_DNS_NO_BACKUP,
    PRIVATE_DNS_BACKUP_RETAINED, PRIVATE_DNS_REMOVAL_FAILED }

// What a write in the Manage family is doing, for the narration row and the disabled buttons.
sealed interface Busy {
    data object Connecting : Busy
    data class Working(val percent: Int, val lines: List<String>) : Busy
}

// Everything the Manage family needs; the app builds one from AppGraph.
class ManageDeps(
    val tvs: TvRepository,
    val sessions: SessionManager,
    val discovery: Discovery,
    val access: TvAccess,
    val gate: GateKeeper,
    val runner: PlanRunner,
    val engine: Engine,
    val ledger: RunLedger,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val snapshots: SnapshotStore,
    val documents: DocumentStore,
    val blobs: BlobStore,
    val themes: ThemeSource,
    val launcherPin: LauncherPin,
    val configTransport: ConfigTransport,
    val restorePoint: RestorePointWriter,
    val bundleWriter: RestoreBundleWriter,
    val registry: Registry,
    val fetcher: ArtifactFetcher,
    val inspector: ApkInspector,
    val cardDetector: CardAnswerDetector,
    val sessionEnd: SessionEnd,
    val homePrefs: HomePrefs,
    val appPrefs: AppPrefs,
    val keyInfo: KeyInfo,
    val updates: UpdateChecker,
    val diagnostics: Diagnostics,
    val clock: Clock,
    val copy: FlowCopy,
    val encoder: ConfigEncoder = ConfigEncoder.Codec,
    val appVersion: String = "0.2.0",
    val openWrites: OpenWrites = OpenWrites(),   // one per graph, shared by every Manage machine (R5-W8 O2)
    val store: StoreRules = StoreRules.FULL,     // STORES-PLAN 1: the update check and the add-ons of this build
    // 0.3.2 B1, B2: the app, its build and the phone, for the reports; and the wizard's home screen
    // switch decision for each TV (SetupMachine keeps it).
    val about: AppAbout = AppAbout(appVersion, "full", "", ""),
    val homeDecisions: HomeDecisions = HomeDecisions(documents),
    val privateDns: PrivateDnsController = PrivateDnsController(documents),
) {
    fun tv(id: TvId): Tv? = tvs.tvs.value.firstOrNull { it.id == id }

    suspend fun profileOf(tv: Tv): Profile? = tv.profileId?.let { id -> attempt { profiles.all() }.getOrNull()?.firstOrNull { it.id == id } }

    fun nameOf(pkg: PackageName, label: String? = null): String = AppNames.of(pkg, facts, label)
}

// The dock and theme this phone last pushed to a TV. Tv has no field for them once the wizard
// draft is gone, so they live beside the TV record: tvs/<id>/home.txt, "dock=a,b" and "theme=id".
// `cards` and `hiddenApps` are nullable until this phone has kept an explicit choice for the TV;
// callers preserve the TV overlay or theme defaults, while the reset marker distinguishes a
// deliberate return to theme defaults from an old file that has no hidden-app preference yet.
data class HomeChoice(
    val dock: List<PackageName>,
    val themeId: String?,
    val cards: Boolean? = null,
    val hiddenApps: List<PackageName>? = null,
    val hiddenAppsResetToTheme: Boolean = false,
)

class HomePrefs(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/home.txt"
    private val presetMutex = Mutex()

    suspend fun load(id: TvId): HomeChoice? = store.read(path(id)) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val hiddenValue = map["hidden"]
        HomeChoice(map["dock"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { PackageName(it) }.getOrNull() },
            map["theme"]?.takeIf { it.isNotBlank() }, map["cards"]?.toBooleanStrictOrNull(),
            hiddenValue?.takeIf { it != "@theme" }?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { runCatching { PackageName(it) }.getOrNull() },
            hiddenAppsResetToTheme = hiddenValue == "@theme")
    }

    suspend fun save(id: TvId, choice: HomeChoice) {
        val text = "dock=${choice.dock.joinToString(",") { it.value }}\ntheme=${choice.themeId.orEmpty()}\n" +
            (choice.cards?.let { "cards=$it\n" } ?: "") +
            when {
                choice.hiddenAppsResetToTheme -> "hidden=@theme\n"
                choice.hiddenApps != null -> "hidden=${choice.hiddenApps.joinToString(",") { it.value }}\n"
                else -> ""
            }
        store.write(path(id), text.encodeToByteArray())
    }

    suspend fun presets(id: TvId): List<LauncherPreset> = presetMutex.withLock { loadPresets(id) }

    suspend fun savePreset(id: TvId, rawName: String, choice: HomeChoice): LauncherPresetSaveResult = presetMutex.withLock {
        val name = normalizedPresetName(rawName) ?: return@withLock LauncherPresetSaveResult.INVALID_NAME
        val existing = loadPresets(id)
        if (existing.any { it.name.equals(name, ignoreCase = true) }) return@withLock LauncherPresetSaveResult.DUPLICATE_NAME
        if (existing.size >= MAX_PRESETS) return@withLock LauncherPresetSaveResult.LIMIT_REACHED
        if (choice.dock.size > MAX_DOCK_APPS) return@withLock LauncherPresetSaveResult.INVALID_LAYOUT
        val bytes = encodePreset(LauncherPreset(name, choice)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PRESET_BYTES) return@withLock LauncherPresetSaveResult.INVALID_LAYOUT
        store.write(presetPath(id, name), bytes)
        LauncherPresetSaveResult.SAVED
    }

    suspend fun deletePreset(id: TvId, rawName: String): Boolean = presetMutex.withLock {
        val name = normalizedPresetName(rawName) ?: return@withLock false
        val existing = loadPresets(id).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@withLock false
        store.delete(presetPath(id, existing.name))
        true
    }

    private suspend fun loadPresets(id: TvId): List<LauncherPreset> {
        val directory = presetDirectory(id)
        val files = try { store.list(directory) } catch (_: IOException) { return emptyList() }
        val loaded = mutableListOf<LauncherPreset>()
        for (file in files.asSequence().filter { PRESET_FILE.matches(it) }.take(MAX_PRESETS * 2)) {
            val preset = try { store.read("$directory/$file", ::decodePreset) } catch (_: IOException) { null }
            if (preset != null) loaded += preset
        }
        return loaded.distinctBy { it.name.lowercase(Locale.ROOT) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }.take(MAX_PRESETS)
    }

    private fun presetDirectory(id: TvId) = "tvs/${id.value}/launcher-presets"
    private fun presetPath(id: TvId, name: String) = "${presetDirectory(id)}/${presetFileKey(name)}.txt"

    private fun presetFileKey(name: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4]); append(HEX[value and 0x0f])
            }
        }
    }

    private fun encodePreset(preset: LauncherPreset): String = listOf(
        "version=1",
        "name=${encodeField(preset.name)}",
        "theme=${encodeField(preset.choice.themeId.orEmpty())}",
        "cards=${preset.choice.cards?.toString().orEmpty()}",
        "dock=${preset.choice.dock.joinToString(",") { encodeField(it.value) }}",
    ).joinToString("\n") + "\n"

    private fun decodePreset(bytes: ByteArray): LauncherPreset? {
        if (bytes.size > MAX_PRESET_BYTES) return null
        val fields = bytes.decodeToString().lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        if (fields["version"] != "1") return null
        val rawName = decodeField(fields["name"] ?: return null) ?: return null
        val name = normalizedPresetName(rawName) ?: return null
        if (name != rawName) return null
        val themeText = decodeField(fields["theme"].orEmpty()) ?: return null
        val theme = themeText.takeIf { it.isNotBlank() }
        val cards = when (fields["cards"]) { "true" -> true; "false" -> false; "" -> null; else -> return null }
        val encodedDock = fields["dock"].orEmpty()
        val dockFields = if (encodedDock.isEmpty()) emptyList() else encodedDock.split(',')
        if (dockFields.size > MAX_DOCK_APPS) return null
        val dock = dockFields.map { encoded -> decodeField(encoded)?.let { runCatching { PackageName(it) }.getOrNull() } ?: return null }
        return LauncherPreset(name, HomeChoice(dock, theme, cards))
    }

    private fun normalizedPresetName(rawName: String): String? {
        val name = rawName.trim()
        if (name.isBlank() || name.length > MAX_PRESET_NAME_CHARS || name.any { Character.isISOControl(it) }) return null
        return name
    }

    private fun encodeField(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decodeField(value: String): String? = try { URLDecoder.decode(value, "UTF-8") } catch (_: IllegalArgumentException) { null }

    companion object {
        const val MAX_PRESETS = 12
        const val MAX_PRESET_NAME_CHARS = 32
        const val MAX_DOCK_APPS = 64
        private const val MAX_PRESET_BYTES = 16 * 1024
        private const val HEX = "0123456789abcdef"
        private val PRESET_FILE = Regex("[0-9a-f]{64}\\.txt")
    }
}

data class LauncherPreset(val name: String, val choice: HomeChoice)
enum class LauncherPresetSaveResult { SAVED, INVALID_NAME, INVALID_LAYOUT, DUPLICATE_NAME, LIMIT_REACHED }

// App settings (M8) that are preferences, not records: haptics and large text.
data class AppPrefsValue(
    val haptics: Boolean = true,
    val largeTextFollowsSystem: Boolean = true,
    val automaticUpdateChecks: Boolean = true,
)

class AppPrefs(private val store: DocumentStore) {
    private val path = "settings.txt"

    suspend fun load(): AppPrefsValue = store.read(path) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        AppPrefsValue(
            haptics = map["haptics"] != "false",
            largeTextFollowsSystem = map["largeText"] != "false",
            automaticUpdateChecks = map["autoUpdates"] != "false",
        )
    } ?: AppPrefsValue()

    suspend fun save(v: AppPrefsValue) {
        store.write(path, "haptics=${v.haptics}\nlargeText=${v.largeTextFollowsSystem}\nautoUpdates=${v.automaticUpdateChecks}\n".encodeToByteArray())
    }
}

// Deletes every document under a folder, depth first (DocumentStore.list returns child names),
// then the folder itself, so nothing is left under it, not even an empty folder (T7 item 6). A
// stray temporary file of a write cut off by a crash keeps its folder; that is harmless, so it is
// not a failure.
suspend fun DocumentStore.deleteTree(dir: String) {
    for (child in list(dir)) {
        val path = "$dir/$child"
        val grand = list(path)
        if (grand.isEmpty()) delete(path) else deleteTree(path)
    }
    try { delete(dir) } catch (e: java.io.IOException) { }
}

sealed interface WriteResult {
    data class Done(val plan: Plan, val finished: RunState.Finished) : WriteResult
    // The run is open (this write paused, or an earlier one is): the OpenWrites card shows it, so the
    // machine shows no snackbar of its own.
    data class Paused(val plan: Plan, val paused: RunState.Paused) : WriteResult
    data class Refused(val reasons: List<String>) : WriteResult
    data object NoTv : WriteResult           // cancelled at the debugging step, or the TV is unknown
    data object GateCancelled : WriteResult
    data object StalePreview : WriteResult    // the prepared actions or prior state changed before confirmation
    data class Failed(val detail: String) : WriteResult
}

sealed interface WritePreflight {
    data class Ready(val pass: GatePass?) : WritePreflight
    data object Cancelled : WritePreflight
    data class Failed(val detail: String) : WritePreflight
}

// The returning-user write of UX 4.11: the debugging step if needed (TvAccess), a plan from the
// newest snapshot, the credential gate (D17), the run, then the restore point on the TV (D44).
// `fresh` plans from a new snapshot instead of the newest stored one, for a request that needs what
// an older snapshot may not hold (UpdateLauncher: the installed launcher's signer, launcher v2.4.0).
class TvWriter(private val d: ManageDeps) {
    private fun samePrior(a: Prior?, b: Prior?): Boolean {
        if (a == null || b == null) return a == b
        val sameOverlay = when {
            a.overlay == null -> b.overlay == null
            b.overlay == null -> false
            else -> a.overlay.contentEquals(b.overlay)
        }
        return a.packageState == b.packageState && a.settingValue == b.settingValue && a.home == b.home &&
            a.present == b.present && sameOverlay
    }

    suspend fun write(
        tvId: TvId,
        request: PlanRequest,
        fresh: Boolean = false,
        expectedPlan: Plan? = null,
        preflight: (suspend (Tv, TvSession, Plan) -> WritePreflight)? = null,
        onBusy: (Busy) -> Unit = {},
    ): WriteResult {
        onBusy(Busy.Connecting)
        val session = when (val a = d.access.session(tvId)) {
            is AccessResult.Ok -> a.session
            is AccessResult.Failed -> return WriteResult.Failed(a.error.toString())
            else -> return WriteResult.NoTv
        }
        val tv = d.tv(tvId) ?: return WriteResult.NoTv
        // An earlier write left its run open: no new plan, no prompt, the card for that run (O2).
        (d.engine.runState(tvId).value as? RunState.Paused)?.let { p -> d.openWrites.show(tv, p); return WriteResult.Paused(p.plan, p) }
        return attempt {
            val snapshot = if (fresh || expectedPlan != null) d.runner.freshSnapshot(session) else d.runner.snapshotFor(tv, session)
            val plan = when (val r = d.runner.plan(tv, request, snapshot)) {
                is PlanResult.Ok -> r.plan
                is PlanResult.Refused -> return@attempt WriteResult.Refused(r.reasons)
            }
            if (expectedPlan != null) {
                val sameActions = plan.steps.size == expectedPlan.steps.size && plan.steps.zip(expectedPlan.steps)
                    .all { (actual, reviewed) -> actual.op == reviewed.op && samePrior(actual.prior, reviewed.prior) }
                if (plan.tvId != expectedPlan.tvId || plan.kind != expectedPlan.kind || !sameActions || plan.sections != expectedPlan.sections) {
                    return@attempt WriteResult.StalePreview
                }
            }
            val pass = when (val prepared = preflight?.invoke(tv, session, plan)) {
                null -> null
                is WritePreflight.Ready -> {
                    if (GateKeeper.needsPass(plan) && prepared.pass == null) return@attempt WriteResult.GateCancelled
                    prepared.pass
                }
                WritePreflight.Cancelled -> return@attempt WriteResult.GateCancelled
                is WritePreflight.Failed -> return@attempt WriteResult.Failed(prepared.detail)
            }
            val outcome = d.runner.run(tv, plan, session, pass, onStarted = { d.sessionEnd.touched(tvId) }) { s ->
                if (s is RunState.Running) onBusy(Busy.Working(s.percent, s.narration.map { it.text }))
            }
            when (outcome) {
                is RunOutcome.Finished -> {
                    afterRun(tv, session, plan, outcome.state)
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> { d.openWrites.show(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
                is RunOutcome.StartRefused -> if (outcome.refusal == StartRefusal.RUN_OPEN) {
                    openRun(tv, session)?.let { WriteResult.Paused(it.plan, it) } ?: WriteResult.Failed(outcome.refusal.name)
                } else WriteResult.Failed(outcome.refusal.name)
                RunOutcome.GateCancelled -> WriteResult.GateCancelled
            }
        }.getOrElse { WriteResult.Failed(it.message ?: it.toString()) }
    }

    // D44: the restore point on the TV after every plan that changed something.
    private suspend fun afterRun(tv: Tv, session: TvSession, plan: Plan, finished: RunState.Finished) {
        if (plan.kind != PlanKind.WRITE_RESTORE_POINT && plan.kind != PlanKind.DEBUG_OFF && finished.status != RunStatus.ABANDONED) {
            d.restorePoint.write(d.tv(tv.id) ?: tv, session, d.profileOf(tv))
        }
    }

    // The open run on this TV as a Paused state, published as the card. After process death the
    // engine has not loaded it yet (start says RUN_OPEN from the journal), so resume loads it: safety
    // only, step R for an open section, then Paused (R0: 6).
    suspend fun openRun(tv: Tv, session: TvSession, publish: Boolean = true): RunState.Paused? {
        if (d.engine.runState(tv.id).value !is RunState.Paused) attempt { d.engine.resume(tv.id, session) }
        val p = d.engine.runState(tv.id).value as? RunState.Paused ?: return null
        if (publish) d.openWrites.show(tv, p)
        return p
    }

    // The card's buttons (O2): carry on, undo what we did, or stop here, for the open run on this
    // TV. Reconnects first (the debugging step if needed), asks for the credential where the plan
    // has a gated step (D17), then follows the run to its end or its next pause.
    suspend fun resolveOpen(tvId: TvId, action: OpenWriteAction): WriteResult {
        val tv = d.tv(tvId) ?: return WriteResult.NoTv
        d.openWrites.update(tvId) { it.copy(busy = true, notTaken = false) }
        fun idle() = d.openWrites.update(tvId) { it.copy(busy = false) }
        val session = when (val a = d.access.session(tvId)) {
            is AccessResult.Ok -> a.session
            is AccessResult.Failed -> { idle(); return WriteResult.Failed(a.error.toString()) }
            else -> { idle(); return WriteResult.NoTv }
        }
        return attempt {
            val paused = openRun(tv, session, publish = false) ?: run { d.openWrites.clear(tvId); return@attempt WriteResult.NoTv }   // it ended meanwhile
            val plan = paused.plan
            when (action) {
                OpenWriteAction.CARRY_ON, OpenWriteAction.UNDO -> {
                    val pass = if (GateKeeper.needsPass(plan)) d.gate.pass(tvId, tv.nickname) ?: run { idle(); return@attempt WriteResult.GateCancelled } else null
                    d.sessionEnd.touched(tvId)
                    if (action == OpenWriteAction.CARRY_ON) d.engine.continueRun(tvId, pass) else d.engine.undoRun(tvId, pass)
                }
                OpenWriteAction.STOP_HERE -> d.engine.abandon(tvId)
            }
            // The engine leaves this Paused state when it takes the action; one that it refuses (the
            // session dropped again) leaves it as it was, and the card says so.
            val moved = withTimeoutOrNull(MOVE_WAIT_MS) { d.engine.runState(tvId).first { it !== paused } } != null
            if (!moved) { d.openWrites.update(tvId) { it.copy(busy = false, notTaken = true) }; return@attempt WriteResult.Paused(plan, paused) }
            when (val outcome = d.runner.follow(tv, plan)) {
                is RunOutcome.Finished -> {
                    d.openWrites.clear(tvId)
                    afterRun(tv, session, plan, outcome.state)
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> { d.openWrites.show(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
                else -> { idle(); WriteResult.Failed("unexpected") }
            }
        }.getOrElse { idle(); WriteResult.Failed(it.message ?: it.toString()) }
    }

    companion object { const val MOVE_WAIT_MS = 5_000L }

    // A plan for preview only (Undo everything, Use the TV's own home screen): no gate, no run.
    // `fresh`: from a new snapshot while the TV is connected (as write takes it), else the stored one.
    suspend fun preview(tvId: TvId, request: PlanRequest, fresh: Boolean = false): Plan? {
        val tv = d.tv(tvId) ?: return null
        val live = if (fresh) d.access.live(tvId)?.let { s -> attempt { d.runner.freshSnapshot(s) }.getOrNull() } else null
        val snapshot = live ?: d.snapshots.latest(tvId) ?: d.access.live(tvId)?.let { d.runner.snapshotFor(tv, it) } ?: return null
        return (d.runner.plan(tv, request, snapshot) as? PlanResult.Ok)?.plan
    }
}
