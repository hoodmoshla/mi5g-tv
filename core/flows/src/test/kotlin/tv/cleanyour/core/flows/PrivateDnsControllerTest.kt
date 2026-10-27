package tv.cleanyour.core.flows

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.Recording

class PrivateDnsControllerTest {
    @Test fun applySavesOriginalSettingsAndRemovalRestoresThemExactly() = runTest {
        val tv = DnsTv(mode = "off", specifier = null)
        val store = MemoryDocumentStore()
        val controller = PrivateDnsController(store)

        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals("hostname", tv.settings[MODE])
        assertEquals("dns.adguard-dns.com", tv.settings[SPECIFIER])

        assertEquals(PrivateDnsRestoreResult.RESTORED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertEquals("off", tv.settings[MODE])
        assertEquals(null, tv.settings[SPECIFIER])
        controller.clearBackup(tv.session.tvId)
        assertEquals(PrivateDnsRestoreResult.NO_BACKUP, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
    }

    @Test fun savedBackupCanBeRestoredByANewControllerInstance() = runTest {
        val tv = DnsTv(mode = "opportunistic", specifier = "old.example")
        val store = MemoryDocumentStore()
        val installerController = PrivateDnsController(store)
        val removerController = PrivateDnsController(store)

        assertEquals(PrivateDnsApplyResult.APPLIED, installerController.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals(PrivateDnsRestoreResult.RESTORED, removerController.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertEquals("opportunistic", tv.settings[MODE])
        assertEquals("old.example", tv.settings[SPECIFIER])
        assertTrue(removerController.retireBackup(tv.session.tvId))
        assertEquals(PrivateDnsRestoreResult.NO_BACKUP, PrivateDnsController(store).restoreBeforeRemoval(tv.session.tvId, tv.session))
    }

    @Test fun removalPreservesPrivateDnsChangedByUserAfterAdGuardInstall() = runTest {
        val tv = DnsTv(mode = "opportunistic", specifier = "old.example")
        val controller = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))

        tv.settings[MODE] = "hostname"
        tv.settings[SPECIFIER] = "dns.google"
        val result = controller.restoreBeforeRemoval(tv.session.tvId, tv.session)

        assertEquals(PrivateDnsRestoreResult.USER_SETTINGS_PRESERVED, result)
        assertEquals("hostname", tv.settings[MODE])
        assertEquals("dns.google", tv.settings[SPECIFIER])
    }

    @Test fun unreadableOrUnknownOriginalSettingsFailClosedWithoutWriting() = runTest {
        val unreadable = DnsTv(mode = "off", specifier = null).apply { failRead = MODE }
        val unreadableController = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsApplyResult.NOT_APPLIED, unreadableController.applyAdGuard(unreadable.session.tvId, unreadable.session))
        assertFalse(unreadable.session.execLines.any { it.contains("'put'") || it.contains("'delete'") })

        val unknownMode = DnsTv(mode = "future-mode", specifier = "dns.example")
        val unknownController = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsApplyResult.NOT_APPLIED, unknownController.applyAdGuard(unknownMode.session.tvId, unknownMode.session))
        assertFalse(unknownMode.session.execLines.any { it.contains("'put'") || it.contains("'delete'") })
    }

    @Test fun failedApplyRollsBackAndDiscardsNewBackupWhenRestoreIsVerified() = runTest {
        val tv = DnsTv(mode = "opportunistic", specifier = "old.example")
        tv.failPut = { key, value -> key == MODE && value == "hostname" }
        val controller = PrivateDnsController(MemoryDocumentStore())

        assertEquals(PrivateDnsApplyResult.NOT_APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals("opportunistic", tv.settings[MODE])
        assertEquals("old.example", tv.settings[SPECIFIER])
        assertEquals(PrivateDnsRestoreResult.NO_BACKUP, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
    }

    @Test fun failedRollbackKeepsTheSavedOriginalForARetry() = runTest {
        val tv = DnsTv(mode = "off", specifier = "old.example")
        tv.failPut = { key, value ->
            (key == MODE && value == "hostname") || (key == SPECIFIER && value == "old.example")
        }
        val controller = PrivateDnsController(MemoryDocumentStore())

        assertEquals(PrivateDnsApplyResult.RESTORE_PENDING, controller.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals("off", tv.settings[MODE])
        assertEquals("dns.adguard-dns.com", tv.settings[SPECIFIER])

        tv.failPut = { _, _ -> false }
        assertEquals(PrivateDnsRestoreResult.RESTORED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertEquals("off", tv.settings[MODE])
        assertEquals("old.example", tv.settings[SPECIFIER])
    }

    @Test fun removalWithoutASavedOriginalLeavesCurrentSettingsUntouched() = runTest {
        val tv = DnsTv(mode = "hostname", specifier = "custom.example")
        val controller = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsRestoreResult.NO_BACKUP, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertTrue(tv.session.execLines.isEmpty())
        assertEquals("hostname", tv.settings[MODE])
        assertEquals("custom.example", tv.settings[SPECIFIER])
    }

    @Test fun failedFirstRestoreWriteStopsBeforeChangingTheSpecifier() = runTest {
        val tv = DnsTv(mode = "off", specifier = null)
        val controller = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        val firstRestoreCommand = tv.session.execLines.size
        tv.failPut = { key, value -> key == MODE && value == "off" }

        assertEquals(PrivateDnsRestoreResult.FAILED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        val restoreCommands = tv.session.execLines.drop(firstRestoreCommand).map(Recording::normalize)
        assertTrue(restoreCommands.any { it == "settings put global $MODE off" })
        assertFalse(restoreCommands.any { it.startsWith("settings put global $SPECIFIER ") || it == "settings delete global $SPECIFIER" })
        assertEquals("dns.adguard-dns.com", tv.settings[SPECIFIER])
    }

    @Test fun cancellationDuringRestorePropagatesAndKeepsBackupForRetry() = runTest {
        val tv = DnsTv(mode = "off", specifier = null)
        val controller = PrivateDnsController(MemoryDocumentStore())
        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        tv.cancelRead = MODE

        assertFailsWith<CancellationException> { controller.restoreBeforeRemoval(tv.session.tvId, tv.session) }
        tv.cancelRead = null
        assertEquals(PrivateDnsRestoreResult.RESTORED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertEquals("off", tv.settings[MODE])
        assertEquals(null, tv.settings[SPECIFIER])
    }

    @Test fun retiredBackupIsNotReusedWhenItsFileCannotBeDeleted() = runTest {
        val tv = DnsTv(mode = "off", specifier = null)
        val store = DeleteFailStore(MemoryDocumentStore())
        val controller = PrivateDnsController(store)
        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals(PrivateDnsRestoreResult.RESTORED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertTrue(controller.retireBackup(tv.session.tvId)) // the consumed marker is durable even though delete fails

        tv.settings[MODE] = "opportunistic"
        tv.settings[SPECIFIER] = "custom.example"
        assertEquals(PrivateDnsApplyResult.APPLIED, controller.applyAdGuard(tv.session.tvId, tv.session))
        assertEquals(PrivateDnsRestoreResult.RESTORED, controller.restoreBeforeRemoval(tv.session.tvId, tv.session))
        assertEquals("opportunistic", tv.settings[MODE])
        assertEquals("custom.example", tv.settings[SPECIFIER])
    }

    private class DnsTv(mode: String?, specifier: String?) {
        val session = FakeTvSession()
        val settings = mutableMapOf(MODE to mode, SPECIFIER to specifier)
        var failRead: String? = null
        var cancelRead: String? = null
        var failPut: (String, String) -> Boolean = { _, _ -> false }

        init {
            session.recording.on(Regex("settings get global (private_dns_mode|private_dns_specifier)")) { match ->
                val key = match.groupValues[1]
                if (cancelRead == key) throw CancellationException("test cancellation")
                else if (failRead == key) ExecResult(1, "", "read failed", false, 1)
                else ExecResult(0, (settings[key] ?: "null") + "\n", "", false, 1)
            }
            session.recording.on(Regex("settings put global (private_dns_mode|private_dns_specifier) (.+)")) { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (failPut(key, value)) ExecResult(1, "", "write failed", false, 1)
                else {
                    settings[key] = value
                    ExecResult(0, "", "", false, 1)
                }
            }
            session.recording.on(Regex("settings delete global (private_dns_mode|private_dns_specifier)")) { match ->
                settings[match.groupValues[1]] = null
                ExecResult(0, "", "", false, 1)
            }
            session.recording.on("pm grant com.midroid.mi5gtv.dns android.permission.WRITE_SECURE_SETTINGS", "")
        }
    }

    private class DeleteFailStore(private val delegate: DocumentStore) : DocumentStore by delegate {
        override suspend fun delete(path: String) {
            if (path.endsWith("private-dns-backup.bin")) error("delete unavailable")
            delegate.delete(path)
        }
    }

    private companion object {
        const val MODE = "private_dns_mode"
        const val SPECIFIER = "private_dns_specifier"
    }
}
