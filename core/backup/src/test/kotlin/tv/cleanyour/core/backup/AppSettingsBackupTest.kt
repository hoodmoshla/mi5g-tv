package tv.cleanyour.core.backup

import kotlinx.coroutines.runBlocking
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsBackupTest {
    private val prefs = AppSettingsPreferences(haptics = false, largeTextFollowsSystem = true)

    @Test fun roundTripsOnlyKnownPreferencesAndMetadata() {
        val encoded = AppSettingsBackupCodec.encode("1.5.0", 1_800_000_000_000, prefs)
        assertTrue(encoded.size <= AppSettingsBackupCodec.MAX_FILE_BYTES)
        assertEquals(
            AppSettingsBackupDocument("1.5.0", 1_800_000_000_000, prefs),
            AppSettingsBackupCodec.decode(encoded),
        )
        assertTrue(encoded.decodeToString().contains("\"sha256\""))
        assertTrue(encoded.decodeToString().contains(AppSettingsBackupCodec.APPLICATION_ID))
    }

    @Test fun rejectsChangedPreferencesWhenChecksumNoLongerMatches() {
        val encoded = AppSettingsBackupCodec.encode("1.5.0", 1_800_000_000_000, prefs)
        val tampered = encoded.decodeToString().replace("\"haptics\":false", "\"haptics\":true").encodeToByteArray()
        assertEquals(
            AppSettingsBackupProblem.CHECKSUM_MISMATCH,
            assertFailsWith<AppSettingsBackupException> { AppSettingsBackupCodec.decode(tampered) }.problem,
        )
    }

    @Test fun rejectsAnotherPackageBeforeUsingItsSettings() {
        val encoded = AppSettingsBackupCodec.encode("1.5.0", 1_800_000_000_000, prefs)
        val foreign = encoded.decodeToString().replace(
            AppSettingsBackupCodec.APPLICATION_ID,
            "example.foreign.app",
        ).encodeToByteArray()
        assertEquals(
            AppSettingsBackupProblem.WRONG_APP,
            assertFailsWith<AppSettingsBackupException> { AppSettingsBackupCodec.decode(foreign) }.problem,
        )
    }

    @Test fun rejectsUnknownFieldsAndUnsupportedSchema() {
        val encoded = AppSettingsBackupCodec.encode("1.5.0", 1_800_000_000_000, prefs).decodeToString()
        val unknownField = encoded.replace("\"sha256\":", "\"futureField\":true,\"sha256\":").encodeToByteArray()
        assertEquals(
            AppSettingsBackupProblem.INVALID_CONTENT,
            assertFailsWith<AppSettingsBackupException> { AppSettingsBackupCodec.decode(unknownField) }.problem,
        )
        val futureSchema = encoded.replace("\"schema\":1", "\"schema\":2").encodeToByteArray()
        assertEquals(
            AppSettingsBackupProblem.UNSUPPORTED_FORMAT,
            assertFailsWith<AppSettingsBackupException> { AppSettingsBackupCodec.decode(futureSchema) }.problem,
        )
    }

    @Test fun rejectsOversizedAndMalformedFiles() {
        assertEquals(
            AppSettingsBackupProblem.TOO_LARGE,
            assertFailsWith<AppSettingsBackupException> {
                AppSettingsBackupCodec.decode(ByteArray(AppSettingsBackupCodec.MAX_FILE_BYTES + 1))
            }.problem,
        )
        assertEquals(
            AppSettingsBackupProblem.INVALID_JSON,
            assertFailsWith<AppSettingsBackupException> { AppSettingsBackupCodec.decode("not json".encodeToByteArray()) }.problem,
        )
    }

    @Test fun rollbackIsVerifiedAndHistoryRemainsBounded() = runBlocking<Unit> {
        val docs = MemoryDocumentStore()
        val store = AppSettingsBackupStore(docs)
        val rollback = AppSettingsBackupDocument("1.5.0", 1_800_000_000_000, prefs)
        store.saveRollback(rollback)
        assertEquals(rollback, store.loadRollback())
        assertNotNull(docs.bytes(AppSettingsBackupStore.ROLLBACK_PATH))
        store.clearRollback()
        assertNull(store.loadRollback())

        repeat(AppSettingsBackupStore.MAX_HISTORY + 5) { index ->
            store.record(
                AppSettingsBackupHistoryEntry(
                    atMs = 1_800_000_000_000 + index,
                    operation = AppSettingsBackupOperation.IMPORT,
                    result = if (index % 2 == 0) AppSettingsBackupResult.SUCCESS else AppSettingsBackupResult.REJECTED,
                ),
            )
        }
        val history = store.history()
        assertEquals(AppSettingsBackupStore.MAX_HISTORY, history.size)
        assertEquals(1_800_000_000_000 + 5, history.first().atMs)
        assertEquals(1_800_000_000_000 + AppSettingsBackupStore.MAX_HISTORY + 4, history.last().atMs)
    }
}
