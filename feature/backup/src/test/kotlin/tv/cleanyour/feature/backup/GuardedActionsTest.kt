package tv.cleanyour.feature.backup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.RestorePhase
import tv.cleanyour.core.flows.RestoreBundleAction
import tv.cleanyour.core.flows.RestoreBundleActionKind
import tv.cleanyour.core.flows.RestoreBundleInfo
import tv.cleanyour.core.flows.RestorePointIntent
import tv.cleanyour.core.flows.RestorePointState
import tv.cleanyour.core.flows.UndoPreview
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// R5-W8 O1 as ruled: "Put everything back" and "Switch the wizard back on" drop a tap through
// another app's overlay; Cancel and the way back into the wizard never do.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class GuardedActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun putEverythingBackIsGuardedAndCancelIsNot() {
        val hits = mutableListOf<RestorePointIntent>()
        compose.showColumn { ConfirmContent { hits += it } }
        compose.tap("Put everything back", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf<RestorePointIntent>(RestorePointIntent.Cancel), hits)
        compose.tap("Put everything back", 0)
        assertEquals(listOf(RestorePointIntent.Cancel, RestorePointIntent.PutEverythingBack), hits)
    }

    @Test fun theFactoryResetSwitchIsGuarded() {
        val hits = mutableListOf<String>()
        compose.showColumn { FactoryContent({ hits += "confirm" }, { hits += "cancel" }) }
        compose.tap("Switch the wizard back on", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf("cancel"), hits)
    }

    @Test fun cleaningAgainIsNavigationAndNotGuarded() {
        val hits = mutableListOf<RestorePointIntent>()
        val state = RestorePointState("Mi TV 4A", Samples.NOW_MS, Samples.NOW_MS, phase = RestorePhase.Finished("undo", Samples.NOW_MS))
        compose.setContent { CytTheme(reducedMotion = true) { RestoreScreen(state, { hits += it }, {}) } }
        compose.waitForIdle()
        compose.tap("Clean it up again", OBSCURED)
        assertEquals(listOf<RestorePointIntent>(RestorePointIntent.CleanAgain), hits)
    }

    @Test fun restoreFromCopyRequestsDocumentImport() {
        val hits = mutableListOf<RestorePointIntent>()
        val state = RestorePointState("Mi TV 4A", Samples.NOW_MS, Samples.NOW_MS)
        compose.setContent { CytTheme(reducedMotion = true) { RestoreScreen(state, { hits += it }, {}) } }
        compose.waitForIdle()
        compose.tap("Restore from a copy", 0)
        assertEquals(listOf(RestorePointIntent.ImportCopy), hits)
    }

    @Test fun importedPreviewListsActionsAndDoesNotOfferUnverifiedLauncherRemoval() {
        val pkg = PackageName("com.example.tv")
        val disabledPkg = PackageName("com.example.disabled")
        val home = Component(PackageName("com.example.home"), ".MainActivity")
        val ops = listOf(Op.Enable(pkg), Op.Disable(disabledPkg), Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "1"),
            Op.SetHome(home), Op.VerifyHome(home), Op.PressHome)
        val actions = listOf(
            RestoreBundleAction(RestoreBundleActionKind.ENABLE_PACKAGE, pkg.value),
            RestoreBundleAction(RestoreBundleActionKind.DISABLE_PACKAGE, disabledPkg.value),
            RestoreBundleAction(RestoreBundleActionKind.SETTING, "screensaver_enabled = 1"),
            RestoreBundleAction(RestoreBundleActionKind.HOME, home.flat),
            RestoreBundleAction(RestoreBundleActionKind.VERIFY_HOME, home.flat),
            RestoreBundleAction(RestoreBundleActionKind.SHOW_HOME, ""),
        )
        val plan = Samples.plan(ops, kind = PlanKind.UNDO_EVERYTHING)
        val preview = UndoPreview(plan, 1, null, false, false, emptyList(), actions)
        val info = RestoreBundleInfo("Mi TV 4A", "1.0", Samples.NOW_MS, Samples.NOW_MS, 1,
            ignoredApps = listOf("com.example.unverified"), canRemoveLauncher = false,
            ignoredFiles = listOf("/sdcard/Android/data/com.gathin.tvhome/files/unverified.jpg"))
        val state = RestorePointState("Mi TV 4A", Samples.NOW_MS, Samples.NOW_MS,
            phase = RestorePhase.BundlePreview(preview, info, confirming = false))
        compose.setContent { CytTheme(reducedMotion = true) { RestoreScreen(state, {}, {}) } }
        compose.waitForIdle()

        compose.onNodeWithText("Turn back on: com.example.tv").assertExists()
        compose.onNodeWithText("Leave disabled: com.example.disabled").assertExists()
        compose.onNodeWithText("Restore setting: screensaver_enabled = 1").assertExists()
        compose.onNodeWithText("Set home screen: com.example.home/.MainActivity").assertExists()
        compose.onNodeWithText("Check home screen: com.example.home/.MainActivity").assertExists()
        compose.onNodeWithText("Show the home screen").assertExists()
        compose.onNodeWithText("Changes for one unverified app will be skipped.").assertExists()
        compose.onNodeWithText("Skipped app packages: com.example.unverified").assertExists()
        compose.onNodeWithText("One unverified file change will be skipped.").assertExists()
        compose.onNodeWithText("Remove it from the TV").assertDoesNotExist()
    }
}
