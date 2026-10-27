package tv.cleanyour.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.HttpResponse
import tv.cleanyour.core.registry.IndexLimits
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.trust.DownloadHosts
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The GitHub and F-Droid build (STORES-PLAN 1): the update check, the add-ons, the GitHub hosts
// and the "updates" choice, exactly as before the store flavours.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FullStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val graphs = mutableListOf<AppGraph>()
    private fun graph(): AppGraph = AppGraph(context).also { graphs += it }

    @After fun closeGraphs() { graphs.forEach { it.close() } }

    private class OneAnswer(val code: Int, val body: String) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse {
            urls += url
            return HttpResponse(code, null, ByteArrayInputStream(body.encodeToByteArray()), body.length.toLong())
        }
    }

    private class OneStream(private val stream: InputStream, private val length: Long? = null) : HttpFetcher {
        override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse =
            HttpResponse(200, null, stream, length)
    }

    private class CountingInputStream(private val data: ByteArray) : InputStream() {
        var bytesRead = 0
            private set
        var closed = false
            private set

        override fun read(): Int = if (bytesRead == data.size) -1 else data[bytesRead++].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRead == data.size) return -1
            val count = minOf(length, data.size - bytesRead)
            data.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }

        override fun close() { closed = true }
    }

    @Test fun thisIsTheFullBuild() {
        assertEquals("full", Store.flavour)
        assertEquals(StoreRules.FULL, Store.rules)
        assertEquals(DownloadHosts.ALL, Store.hosts)
        assertTrue(Store.updatesMayGoOff)
        val g = graph()
        assertSame(Store, g.store)
        assertIs<InstallableOnlyFetcher>(g.seams.fetcher)
        assertEquals(StoreRules.FULL, g.manageDeps.store)
        assertEquals(StoreRules.FULL, g.setupDeps.store)
        for (h in DownloadHosts.ALL) assertTrue(DownloadHosts.isAllowed("https://$h/x", Store.hosts), h)
    }

    @Test fun theUpdateCheckAsksGithubForTheLatestRelease() = runBlocking {
        val newer = OneAnswer(200, """{"tag_name": "v0.4.0", "name": "x"}""")
        assertEquals("0.4.0", Store.updateChecker(newer).latestNewerThan("0.3.0"))
        assertEquals(listOf(Store.RELEASES_URL), newer.urls)
        assertEquals("", Store.updateChecker(OneAnswer(200, """{"tag_name":"v0.3.0"}""")).latestNewerThan("0.3.0"))
        assertEquals(null, Store.updateChecker(OneAnswer(404, "")).latestNewerThan("0.3.0"))
        assertEquals("https://github.com/al3nkboot/mi5g-tv/releases", Store.releasePage)
    }

    @Test fun theUpdateCheckNeverOffersOlderOrMalformedVersions() = runBlocking {
        assertEquals("", Store.updateChecker(OneAnswer(200, """{"tag_name":"v1.4.9"}""")).latestNewerThan("1.5.0"))
        assertEquals("1.10.0", Store.updateChecker(OneAnswer(200, """{"tag_name":"v1.10.0"}""")).latestNewerThan("1.9.0"))
        assertEquals(null, Store.updateChecker(OneAnswer(200, """{"tag_name":"v1.5.0-beta"}""")).latestNewerThan("1.4.0"))
    }

    @Test fun oversizedReleaseMetadataWithUnknownLengthIsReadOnlyToTheCap() = runBlocking {
        val stream = CountingInputStream(ByteArray(IndexLimits.INDEX_BYTES + 100) { 'x'.code.toByte() })
        assertEquals(null, Store.updateChecker(OneStream(stream)).latestNewerThan("0.1.0"))
        assertEquals(IndexLimits.INDEX_BYTES + 1, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test fun oversizedReleaseMetadataWithKnownLengthIsRejectedWithoutReading() = runBlocking {
        val stream = CountingInputStream(ByteArray(32) { 'x'.code.toByte() })
        assertEquals(null, Store.updateChecker(OneStream(stream, IndexLimits.INDEX_BYTES.toLong() + 1)).latestNewerThan("0.1.0"))
        assertEquals(0, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test fun softwareUpdatesStayTheOwnersChoice() {
        val g = graph()
        val ota = PackageName("com.mitv.ota")
        val on = PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false)
        val snap = Samples.snapshot(packages = Samples.miTvPackages(emptySet()) + (ota to on))
        val r = g.planner.plan(PlanRequest.SwitchOff(setOf(ota)), snap, g.facts, emptyList())
        assertIs<PlanResult.Ok>(r)
        assertEquals(listOf<Op>(Op.Disable(ota)), r.plan.steps.map { it.op })
    }
}
