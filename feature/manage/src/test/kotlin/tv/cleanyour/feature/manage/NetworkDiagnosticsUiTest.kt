package tv.cleanyour.feature.manage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.TvNetworkDiagnostics
import tv.cleanyour.core.flows.TvNetworkDiagnosticsStatus
import tv.cleanyour.core.flows.TvPrivateDnsMode
import tv.cleanyour.core.flows.TvToolsIntent
import tv.cleanyour.core.flows.TvToolsState
import tv.cleanyour.ui.design.theme.CytTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class NetworkDiagnosticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsLocalNetworkSnapshotAndReadButtonSendsIntent() {
        val intents = mutableListOf<TvToolsIntent>()
        compose.setContent {
            CytTheme(reducedMotion = true) {
                TvToolsScreen(
                    state = TvToolsState(
                        networkDiagnostics = TvNetworkDiagnostics(
                            dnsServers = listOf("192.168.1.2", "1.1.1.1"),
                            privateDnsMode = TvPrivateDnsMode.AUTOMATIC,
                            privateDnsProviderHostname = null,
                            defaultGateway = "192.168.1.1",
                            networkInterface = "wlan0",
                        ),
                        networkDiagnosticsStatus = TvNetworkDiagnosticsStatus.READY,
                    ),
                    onIntent = { intents += it },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        val dns = compose.onNodeWithText("DNS servers: 192.168.1.2, 1.1.1.1")
        dns.performScrollTo()
        dns.assertIsDisplayed()
        val readButton = compose.onNodeWithText("Read network settings")
        readButton.performScrollTo()
        readButton.performClick()
        assertEquals(1, intents.size)
        assertEquals(TvToolsIntent.RefreshNetworkDiagnostics, intents.single())
    }
}
