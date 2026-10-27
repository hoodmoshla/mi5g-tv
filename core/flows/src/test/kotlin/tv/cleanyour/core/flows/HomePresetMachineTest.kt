package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.testing.fixtures.Samples
import kotlin.time.Duration.Companion.seconds

class HomePresetMachineTest {
    @Test fun saveIsLocalApplyUsesExistingGuardedWritePathAndDeleteRemovesOnlyThePreset() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))
        val hidden = listOf(PackageName("com.example.hidden"))
        HomePrefs(h.documents).save(tv.id, HomeChoice(dock, "aurora", cards = false, hiddenApps = hidden))
        val machine = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
        settle()

        machine.dispatch(HomeScreenIntent.SavePreset("Evening"))
        settle()
        assertEquals(listOf(LauncherPreset("Evening", HomeChoice(dock, "aurora", cards = false))), machine.state.value.presets)
        assertEquals(HomePresetFeedback.SAVED, machine.state.value.presetFeedback)
        assertTrue("saving a preset must not send a command to the TV", h.planner.requests.none { it is PlanRequest.ApplyTheme })

        machine.dispatch(HomeScreenIntent.ApplyPreset("Evening"))
        settle()
        val applied = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last()
        assertEquals(dock, applied.doc.dockApps)
        assertEquals("aurora", machine.state.value.themeId)
        assertEquals("preset card choice", false, applied.doc.moduleCards)
        assertEquals("preset must preserve hidden-app choices", hidden, applied.doc.hiddenApps)
        assertEquals(HomePresetFeedback.APPLIED, machine.state.value.presetFeedback)

        machine.dispatch(HomeScreenIntent.DeletePreset("Evening"))
        settle()
        assertTrue(machine.state.value.presets.isEmpty())
        assertEquals(HomePresetFeedback.DELETED, machine.state.value.presetFeedback)
        assertEquals(HomeChoice(dock, "aurora", cards = false, hiddenApps = hidden), HomePrefs(h.documents).load(tv.id))
    }

    @Test fun nonCompletedApplyNeverReplacesActiveChoiceOrClaimsSuccess() = runTest(timeout = 60.seconds) {
        for (status in listOf(RunStatus.ROLLED_BACK, RunStatus.ABANDONED, RunStatus.COMPLETED_WITH_SKIPS)) {
            val h = Harness()
            val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
            h.tvs.upsert(tv)
            val active = HomeChoice(listOf(PackageName("com.netflix.ninja")), "aurora", cards = true)
            val preset = HomeChoice(listOf(PackageName("com.google.android.youtube.tv")), "six-columns", cards = false)
            val prefs = HomePrefs(h.documents)
            prefs.save(tv.id, active)
            assertEquals(LauncherPresetSaveResult.SAVED, prefs.savePreset(tv.id, "Partial", preset))
            h.engine.onStart = { plan -> finish(plan, status) }
            val machine = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
            settle()

            machine.dispatch(HomeScreenIntent.ApplyPreset("Partial"))
            settle()

            assertEquals(status.name, active, prefs.load(tv.id))
            assertEquals(status.name, "aurora", machine.state.value.themeId)
            assertEquals(status.name, HomePresetFeedback.FAILED, machine.state.value.presetFeedback)
        }
    }
}
