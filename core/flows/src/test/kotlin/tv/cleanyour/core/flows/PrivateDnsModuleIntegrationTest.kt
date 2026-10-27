package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrivateDnsModuleIntegrationTest {
    @Test fun restoreIsGatedAndAFailedRestorePreventsRemovalButCanBeRetried() = runTest {
        val h = Harness()
        val tv = h.knownTv()
        val pkg = PackageName("com.midroid.mi5gtv.dns")
        val artifact = ArtifactRef("https://example.test/adguard.apk", 1, Samples.zeroHash, Samples.zeroHash)
        val version = ModuleVersion(1, "1.0.0", "2026-10-27", artifact, emptyList(), "", 21, emptyList(), emptyList())
        val module = Module("adguard-dns", "AdGuard DNS", "Private DNS protection", "DNS", pkg, "dns", Tier.VERIFIED,
            "0x16", "https://adguard-dns.com", "GPL-3.0", null, null, listOf(version))
        h.registry.onRefresh = {
            IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(module), emptyList()), stale = false, fetchedAtMs = 0)
        }
        h.planner.respond = { request, snapshot ->
            if (request is PlanRequest.RemoveModule) {
                PlanResult.Ok(h.plan(listOf(Op.Disable(pkg)), kind = PlanKind.REMOVE_MODULE))
            } else {
                PlanResult.Refused(listOf("unexpected plan"))
            }
        }

        val settings = mutableMapOf("private_dns_mode" to "off", "private_dns_specifier" to null)
        var failRestoreMode = false
        var restoreWriteAfterGate = false
        h.session.recording.on(Regex("settings get global (private_dns_mode|private_dns_specifier)")) { match ->
            ExecResult(0, (settings[match.groupValues[1]] ?: "null") + "\n", "", false, 1)
        }
        h.session.recording.on(Regex("settings put global (private_dns_mode|private_dns_specifier) (.+)")) { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (key == "private_dns_mode" && value == "off") {
                restoreWriteAfterGate = h.credential.prompts.isNotEmpty()
                if (failRestoreMode) return@on ExecResult(1, "", "write failed", false, 1)
            }
            settings[key] = value
            ExecResult(0, "", "", false, 1)
        }
        h.session.recording.on(Regex("settings delete global (private_dns_mode|private_dns_specifier)")) { match ->
            settings[match.groupValues[1]] = null
            ExecResult(0, "", "", false, 1)
        }
        h.session.recording.on("pm grant com.midroid.mi5gtv.dns android.permission.WRITE_SECURE_SETTINGS", "")

        val deps = h.manageDeps()
        assertEquals(PrivateDnsApplyResult.APPLIED, deps.privateDns.applyAdGuard(tv.id, h.session))
        val installed = PackageState(EnabledState.ENABLED, system = false, versionCode = 1, signer = null,
            persistent = false, tvInput = false, label = "AdGuard DNS", installedByUs = true)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (pkg to installed), takenAtMs = Samples.NOW_MS + 1))
        h.registry.refresh()
        val machine = ModulesMachine(deps, backgroundScope, tv.id)
        settle()
        assertTrue(machine.state.value.installed.any { it.id == "adguard-dns" })

        failRestoreMode = true
        machine.effects.test {
            machine.dispatch(ModulesIntent.Remove("adguard-dns"))
            settle()
            val snack = awaitItem() as ManageEffect.Snackbar
            assertEquals(SnackKind.PRIVATE_DNS_RESTORE_FAILED, snack.kind)
        }
        assertTrue(restoreWriteAfterGate, "the credential gate must be passed before the first DNS mutation")
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start }, "a failed DNS restore must stop before the removal plan starts")
        assertEquals("hostname", settings["private_dns_mode"])
        assertEquals("dns.adguard-dns.com", settings["private_dns_specifier"])

        failRestoreMode = false
        machine.dispatch(ModulesIntent.Remove("adguard-dns"))
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Start })
        assertEquals("off", settings["private_dns_mode"])
        assertEquals(null, settings["private_dns_specifier"])
        assertIs<RunState.Finished>(h.engine.runState(tv.id).value)
    }
}
