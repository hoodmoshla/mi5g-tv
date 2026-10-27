package tv.cleanyour.feature.manage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.RemoteStatus
import tv.cleanyour.core.flows.TvToolsState
import tv.cleanyour.ui.design.theme.CytTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class RemoteCommandFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvCommandErrorIsNotPresentedAsConnectionLoss() {
        compose.setContent {
            CytTheme(reducedMotion = true) {
                TvToolsScreen(TvToolsState(remoteStatus = RemoteStatus.CommandFailed), {}, {})
            }
        }
        compose.waitForIdle()

        val commandError = compose.onNodeWithText("The TV returned an error for this command.")
        commandError.performScrollTo()
        commandError.assertIsDisplayed()
    }
}
