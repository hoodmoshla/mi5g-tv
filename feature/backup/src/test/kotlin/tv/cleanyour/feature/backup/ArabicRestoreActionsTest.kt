package tv.cleanyour.feature.backup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.RestoreBundleAction
import tv.cleanyour.core.flows.RestoreBundleActionKind
import tv.cleanyour.core.flows.RestoreBundleInfo
import tv.cleanyour.core.flows.RestorePhase
import tv.cleanyour.core.flows.RestorePointState
import tv.cleanyour.core.flows.UndoPreview
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rSA-ldrtl-w360dp-h2400dp-xhdpi")
class ArabicRestoreActionsTest {
    @get:Rule val compose = createComposeRule()
    private var previousLocale: Locale = Locale.getDefault()

    @Before fun useArabicLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))
    }

    @After fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test fun importedPreviewShowsArabicWarningsAndActions() {
        val pkg = PackageName("com.example.tv")
        val actions = listOf(RestoreBundleAction(RestoreBundleActionKind.ENABLE_PACKAGE, pkg.value))
        val preview = UndoPreview(
            plan = Samples.plan(listOf(Op.Enable(pkg)), kind = PlanKind.UNDO_EVERYTHING),
            thingsBack = 1,
            stockName = null,
            wizardBack = false,
            removeLauncher = false,
            importedChanges = emptyList(),
            actions = actions,
        )
        val info = RestoreBundleInfo(
            model = "Mi TV 4A",
            appVersion = "1.0",
            createdAtMs = Samples.NOW_MS,
            baselineAtMs = Samples.NOW_MS,
            changes = 1,
            ignoredApps = listOf("com.example.unverified"),
            canRemoveLauncher = false,
        )
        val state = RestorePointState(
            tvName = "Mi TV 4A",
            baselineAtMs = Samples.NOW_MS,
            latestAtMs = Samples.NOW_MS,
            phase = RestorePhase.BundlePreview(preview, info, confirming = false),
        )

        compose.setContent { CytTheme(reducedMotion = true) { RestoreScreen(state, {}, {}) } }
        compose.waitForIdle()

        compose.onNodeWithText("لن تُطبّق تغييرات هذه النسخة على تطبيق واحد غير موثّق.").assertExists()
        compose.onNodeWithText("حزم التطبيقات التي ستُتجاوز: com.example.unverified").assertExists()
        compose.onNodeWithText("إعادة تفعيل: com.example.tv").assertExists()
        compose.onNodeWithText("إزالة التطبيق: com.gathin.tvhome").assertDoesNotExist()
    }
}
