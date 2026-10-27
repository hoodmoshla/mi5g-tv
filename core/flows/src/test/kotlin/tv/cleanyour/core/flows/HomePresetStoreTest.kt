package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.core.model.PackageName

class HomePresetStoreTest {
    @Test fun roundTripsArabicPresetAndKeepsCurrentHomeChoiceSeparate() = runTest {
        val h = Harness()
        val tv = h.knownTv()
        val prefs = HomePrefs(h.documents)
        val active = HomeChoice(listOf(PackageName("com.netflix.ninja")), "aurora", cards = true)
        val preset = HomeChoice(listOf(PackageName("com.example.news"), PackageName("com.example.video")), null, cards = false)
        prefs.save(tv.id, active)

        assertEquals(LauncherPresetSaveResult.SAVED, prefs.savePreset(tv.id, "  غرفة العائلة  ", preset))
        assertEquals(listOf(LauncherPreset("غرفة العائلة", preset)), HomePrefs(h.documents).presets(tv.id))
        assertEquals("saving a preset must not change the active choice", active, prefs.load(tv.id))
    }

    @Test fun rejectsInvalidDuplicateAndOversizedLayoutsAndEnforcesPresetLimit() = runTest {
        val h = Harness()
        val tv = h.knownTv()
        val prefs = HomePrefs(h.documents)
        val empty = HomeChoice(emptyList(), null, null)

        assertEquals(LauncherPresetSaveResult.INVALID_NAME, prefs.savePreset(tv.id, " \n ", empty))
        assertEquals(LauncherPresetSaveResult.INVALID_NAME,
            prefs.savePreset(tv.id, "x".repeat(HomePrefs.MAX_PRESET_NAME_CHARS + 1), empty))
        assertEquals(LauncherPresetSaveResult.INVALID_LAYOUT,
            prefs.savePreset(tv.id, "Too many apps", empty.copy(dock = List(HomePrefs.MAX_DOCK_APPS + 1) { PackageName("com.example.app$it") })))

        assertEquals(LauncherPresetSaveResult.SAVED, prefs.savePreset(tv.id, "Office", empty))
        assertEquals(LauncherPresetSaveResult.DUPLICATE_NAME, prefs.savePreset(tv.id, "office", empty))
        for (index in 1 until HomePrefs.MAX_PRESETS) {
            assertEquals(LauncherPresetSaveResult.SAVED, prefs.savePreset(tv.id, "Preset $index", empty))
        }
        assertEquals(HomePrefs.MAX_PRESETS, prefs.presets(tv.id).size)
        assertEquals(LauncherPresetSaveResult.LIMIT_REACHED, prefs.savePreset(tv.id, "Overflow", empty))

        assertTrue(prefs.deletePreset(tv.id, "pReSeT 1"))
        assertFalse(prefs.deletePreset(tv.id, "missing"))
        assertEquals(LauncherPresetSaveResult.SAVED, prefs.savePreset(tv.id, "After delete", empty))
        assertEquals(HomePrefs.MAX_PRESETS, prefs.presets(tv.id).size)
    }
}
