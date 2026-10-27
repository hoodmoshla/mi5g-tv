package tv.cleanyour.core.flows

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TvAccessTest {
    @Test fun cancelWhileAwaitingAllowCancelsPendingOpenImmediately() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        val sessions = BlockingSessionManager()
        val access = TvAccess(sessions, h.discovery, h.tvs, h.clock, pollMs = 1, giveUpMs = 1_000)
        val result = async { access.session(tv.id) }
        try {
            runCurrent()
            assertTrue(sessions.openStarted.isCompleted)
            assertEquals(Reach.AwaitingAllow(tv.id, tv.nickname, "fingerprint"), access.reach.value)

            access.cancelWaiting()
            runCurrent()

            assertTrue(result.isCompleted, "cancelWaiting should cancel an in-flight ADB open")
            assertEquals(AccessResult.Cancelled, result.await())
            assertTrue(sessions.openCancelled.isCompleted, "the pending session open should be cancelled and cleaned up")
            assertEquals(Reach.Idle, access.reach.value)
        } finally {
            result.cancel()
        }
    }

    private class BlockingSessionManager : SessionManager {
        private val mutableStates = MutableStateFlow<Map<TvId, ConnectionState>>(emptyMap())
        val openStarted = CompletableDeferred<Unit>()
        val openCancelled = CompletableDeferred<Unit>()
        override val states = mutableStates.asStateFlow()

        override suspend fun open(
            endpoint: Endpoint,
            expected: TvId?,
            onAwaitingAllow: suspend (fingerprint: String) -> Unit,
        ): OpenResult {
            openStarted.complete(Unit)
            onAwaitingAllow("fingerprint")
            try {
                awaitCancellation()
            } finally {
                openCancelled.complete(Unit)
            }
        }

        override fun existing(tvId: TvId): TvSession? = null
        override suspend fun closeAll() = Unit
    }
}
