package tv.cleanyour.feature.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.HomeChoice
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LauncherPreset
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.ui.design.theme.CytTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class LauncherPresetUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeLookSheetOpensSavedLayouts() {
        val state = HomeScreenState(
            themes = listOf(ThemeOption("aurora", "Aurora", "Blue")),
            presets = listOf(LauncherPreset("Travel", HomeChoice(listOf(PackageName("com.example.video")), "aurora", false))),
        )
        compose.setContent { CytTheme(reducedMotion = true) { HomeScreen(state, {}, {}) } }
        compose.waitForIdle()

        compose.onNodeWithText("Look").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Saved layouts (1)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Saved launcher layouts").assertIsDisplayed()
    }

    @Test fun saveApplyAndDeleteSendDistinctIntentsAndApplyDeleteRejectObscuredTaps() {
        val hits = mutableListOf<HomeScreenIntent>()
        val state = HomeScreenState(
            themes = listOf(ThemeOption("aurora", "Aurora", "Blue")),
            presets = listOf(LauncherPreset("Travel", HomeChoice(listOf(PackageName("com.example.video")), "aurora", false))),
        )
        compose.showColumn { PresetsContent(state) { hits += it } }

        compose.onNode(hasSetTextAction()).performTextInput("Evening")
        compose.tap("Save current layout", 0)
        assertEquals(listOf(HomeScreenIntent.SavePreset("Evening")), hits)

        compose.tap("Apply to this TV", OBSCURED)
        assertEquals(listOf(HomeScreenIntent.SavePreset("Evening")), hits)
        compose.tap("Apply to this TV", 0)
        assertEquals(listOf(HomeScreenIntent.SavePreset("Evening"), HomeScreenIntent.ApplyPreset("Travel")), hits)

        compose.tap("Delete saved layout", OBSCURED)
        assertEquals(listOf(HomeScreenIntent.SavePreset("Evening"), HomeScreenIntent.ApplyPreset("Travel")), hits)
        compose.tap("Delete saved layout", 0)
        assertEquals(listOf(HomeScreenIntent.SavePreset("Evening"), HomeScreenIntent.ApplyPreset("Travel"), HomeScreenIntent.DeletePreset("Travel")), hits)
    }
}
