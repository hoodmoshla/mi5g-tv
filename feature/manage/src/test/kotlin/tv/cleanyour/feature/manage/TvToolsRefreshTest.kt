package tv.cleanyour.feature.manage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.TvToolsIntent
import tv.cleanyour.core.flows.TvToolsState
import tv.cleanyour.core.model.TvAnimationScale
import tv.cleanyour.ui.design.theme.CytTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class TvToolsRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun refreshButtonDispatchesPerformanceRefreshIntent() {
        val intents = mutableListOf<TvToolsIntent>()
        compose.setContent {
            CytTheme(reducedMotion = true) {
                TvToolsScreen(TvToolsState(), { intents += it }, {})
            }
        }
        compose.waitForIdle()

        val refresh = compose.onNodeWithText("Refresh memory status")
        refresh.performScrollTo()
        refresh.performClick()
        compose.waitForIdle()

        assertEquals(listOf(TvToolsIntent.RefreshPerformance), intents)
    }

    @Test fun lowResourceOptionAppliesOnlyTheExistingHalfSpeedAnimationSetting() {
        val intents = mutableListOf<TvToolsIntent>()
        compose.setContent {
            CytTheme(reducedMotion = true) {
                TvToolsScreen(TvToolsState(), { intents += it }, {})
            }
        }
        compose.waitForIdle()

        val option = compose.onNodeWithText("Low-resource option (0.5x)")
        option.performScrollTo()
        option.performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(TvToolsIntent.SetAnimationScale(TvAnimationScale.FAST_0_5X)),
            intents,
        )
        compose.onNodeWithText(
            "This only changes Android animation scales to 0.5x. It does not free RAM, close apps, clear data, or guarantee faster apps. Restore 1x with the option above.",
        ).assertExists()
    }
}
