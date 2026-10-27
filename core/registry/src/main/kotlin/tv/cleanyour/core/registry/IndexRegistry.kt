package tv.cleanyour.core.registry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.trust.IndexFailure
import tv.cleanyour.core.trust.IndexVerdict
import tv.cleanyour.core.trust.IndexVerifier
import tv.cleanyour.core.trust.KeysFile
import tv.cleanyour.core.trust.MinisignIndexVerifier
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.core.trust.TrustRoots
import java.io.IOException
import java.util.Base64

// The four files of one index generation, exactly as signed.
class IndexBundle(val index: ByteArray, val indexSig: ByteArray, val keys: ByteArray, val keysSig: ByteArray)

/** Separate capability for validating a root-signed keys.json when IndexVerifier is customized. */
fun interface RootKeysVerifier {
    fun verify(keys: ByteArray, signature: ByteArray): KeysFile?
}

// Where refresh() fetches from. null in IndexRegistry: the bundled seed is the only source
// (PRODUCT-SPEC D29, the interim case).
data class IndexSources(val index: String, val indexSig: String, val keys: String, val keysSig: String) {
    companion object {
        fun under(base: String): IndexSources = base.trimEnd('/').let { IndexSources("$it/index.json", "$it/index.json.minisig", "$it/keys.json", "$it/keys.json.minisig") }
    }
}

// The index state machine (SECURITY 2.4 S2 and S3, PRODUCT-SPEC F9):
//
// restore()  no network. Takes the newer (by sequence) of the stored copy and the bundled seed,
//            re-verifies its signatures (expiry aside) and publishes Ready, stale when it has
//            expired. Nothing verifiable: Empty, or Failed with the verdict of the seed.
// refresh()  fetches keys.json, index.json and both signatures, verifies them against the last
//            accepted sequence and now, refuses a keys.json older than the last accepted one, and on
//            success stores the generation atomically and publishes Ready(stale = false). On any
//            failure it publishes Failed(fetch failure or verdict, last good index); the last index
//            itself is never replaced by a worse one. Calls are single flight.
// stale      Ready.stale is true once now is past the index's expiry; the list is still shown with
//            "The module list may be out of date", and installs wait for a fresh refresh. The same
//            list behind a Failed state is shown but never installed from (IndexState.installableIndex).
//            The bundled seed is published with fetchedAtMs 0.
//
// The high-water marks and latest root-verified keys are stored independently from the replaceable
// cache. A failed index refresh can therefore retain a newer key revocation across restarts.
class IndexRegistry(
    private val http: HttpFetcher,
    private val store: DocumentStore,
    private val clock: Clock,
    private val seed: IndexBundle?,
    private val sources: IndexSources?,
    private val verifier: IndexVerifier = TrustRoots.indexVerifier(),
    private val keysSequenceOf: (ByteArray) -> Long? = KeysFile::peekSequence,
    private val rootKeysVerifier: RootKeysVerifier? = null,
) : Registry {

    private val state = MutableStateFlow<IndexState>(IndexState.Empty)
    override val index: StateFlow<IndexState> = state.asStateFlow()
    private val mutex = Mutex()

    private var current: Generation? = null
    private var highWater: HighWater? = null
    private var highWaterInvalid = false
    private var conflictFence: ConflictFence? = null

    init {
        require(verifier is MinisignIndexVerifier || rootKeysVerifier != null || verifier is RootKeysVerifier) {
            "A custom IndexVerifier must also provide RootKeysVerifier for persisted keys"
        }
    }

    private class Generation(val bundle: IndexBundle, val index: VerifiedIndex, val keysSequence: Long, val fetchedAtMs: Long)
    private data class HighWater(val indexSequence: Long, val keysSequence: Long, val keys: ByteArray, val keysSig: ByteArray)
    private data class ConflictFence(val indexSequence: Long, val keysSequence: Long)
    private sealed interface HighWaterRead {
        data object Missing : HighWaterRead
        data object Invalid : HighWaterRead
        data class Present(val value: HighWater) : HighWaterRead
    }

    override fun module(id: String): Module? = when (val s = state.value) {
        is IndexState.Ready -> s.index.modules.firstOrNull { it.id == id }
        is IndexState.Failed -> s.last?.modules?.firstOrNull { it.id == id }
        IndexState.Empty -> null
    }

    suspend fun restore(): IndexState = mutex.withLock { restoreUnlocked() }

    override suspend fun refresh(): IndexState = mutex.withLock {
        if (current == null && !highWaterInvalid) restoreUnlocked()
        if (highWaterInvalid) return@withLock state.value
        val src = sources ?: return@withLock publish(current?.let(::ready) ?: state.value)
        val now = clock.nowMs()
        val last = current
        val lastShown = last?.index ?: state.value.shownIndex()
        val (keysBytes, keysSignature) = try {
            download(src.keys, IndexLimits.KEYS_BYTES) to download(src.keysSig, IndexLimits.SIG_BYTES)
        } catch (e: FetchException) {
            return@withLock publish(IndexState.Failed(e.failure, null, lastShown))
        } catch (e: IllegalArgumentException) {
            return@withLock publish(IndexState.Failed(FetchFailure.HOST_NOT_ALLOWED, null, lastShown))
        } catch (e: IOException) {
            return@withLock publish(IndexState.Failed(FetchFailure.OFFLINE, null, lastShown))
        }
        val trustedKeys = verifyKeys(keysBytes, keysSignature)
        val keysSeq = keysSequenceOf(keysBytes)
        if (trustedKeys == null || keysSeq == null || trustedKeys.sequence != keysSeq) {
            return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, lastShown))
        }
        val prior = highWater ?: last?.let(::toHighWater)
        val conflict = conflictFence
        if (conflict != null && keysSeq <= conflict.keysSequence) {
            return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, lastShown))
        }
        if (prior != null && keysSeq < prior.keysSequence) {
            return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, lastShown))
        }
        if (prior != null && keysSeq == prior.keysSequence && !sameKeys(prior, keysBytes, keysSignature)) {
            return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, lastShown))
        }

        // Persist a newer root-signed key set before looking at the index. A bad/mismatched index
        // must not erase a revocation learned from an otherwise valid keys.json.
        if (prior == null || keysSeq > prior.keysSequence) {
            val keyFloor = HighWater(maxOf(prior?.indexSequence ?: 0L, conflict?.indexSequence ?: 0L), keysSeq, keysBytes.copyOf(), keysSignature.copyOf())
            try { saveHighWater(keyFloor) } catch (e: IOException) {
                return@withLock publish(IndexState.Failed(null, null, lastShown))
            }
            highWater = keyFloor
            conflictFence = null
            if (last != null) {
                when (val previous = verifier.verify(
                    last.bundle.index,
                    last.bundle.indexSig,
                    keyFloor.keys,
                    keyFloor.keysSig,
                    keyFloor.indexSequence.takeIf { it > 0L },
                    MinisignIndexVerifier.VERIFY_IGNORING_EXPIRY,
                )) {
                    is IndexVerdict.Bad -> publish(IndexState.Failed(null, previous.failure, last.index))
                    is IndexVerdict.Ok -> {
                        val revalidated = Generation(
                            IndexBundle(last.bundle.index, last.bundle.indexSig, keyFloor.keys, keyFloor.keysSig),
                            previous.index,
                            keysSeq,
                            last.fetchedAtMs,
                        )
                        current = revalidated
                        if (state.value is IndexState.Ready) publish(ready(revalidated))
                    }
                }
            }
        }
        val floor = highWater ?: prior

        val (indexBytes, indexSignature) = try {
            download(src.index, IndexLimits.INDEX_BYTES) to download(src.indexSig, IndexLimits.SIG_BYTES)
        } catch (e: FetchException) {
            return@withLock publish(IndexState.Failed(e.failure, null, lastShown))
        } catch (e: IllegalArgumentException) {
            return@withLock publish(IndexState.Failed(FetchFailure.HOST_NOT_ALLOWED, null, lastShown))
        } catch (e: IOException) {
            return@withLock publish(IndexState.Failed(FetchFailure.OFFLINE, null, lastShown))
        }
        val fetched = IndexBundle(indexBytes, indexSignature, keysBytes, keysSignature)
        val lastSequence = maxOf(floor?.indexSequence ?: 0L, last?.index?.sequence ?: 0L).takeIf { it > 0L }
        when (val verdict = verifier.verify(fetched.index, fetched.indexSig, fetched.keys, fetched.keysSig, lastSequence, now)) {
            is IndexVerdict.Bad -> publish(IndexState.Failed(null, verdict.failure, lastShown))
            is IndexVerdict.Ok -> {
                val gen = Generation(fetched, verdict.index, keysSeq, now)
                val nextFloor = HighWater(maxOf(floor?.indexSequence ?: 0L, gen.index.sequence), keysSeq, fetched.keys.copyOf(), fetched.keysSig.copyOf())
                if (floor == null || !sameHighWater(floor, nextFloor)) {
                    try { saveHighWater(nextFloor) } catch (e: IOException) {
                        return@withLock publish(IndexState.Failed(null, null, lastShown))
                    }
                }
                highWater = nextFloor
                current = gen
                try { save(gen) } catch (e: IOException) { /* the independent high-water record remains durable */ }
                publish(IndexState.Ready(gen.index, stale = false, fetchedAtMs = now))
            }
        }
    }

    private suspend fun restoreUnlocked(): IndexState {
        val floorRead = readHighWater()
        if (floorRead is HighWaterRead.Invalid) {
            highWater = null
            highWaterInvalid = true
            conflictFence = null
            current = null
            return publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, null))
        }
        highWaterInvalid = false
        conflictFence = null
        val stored = loadStored()
        val seeded = seed?.let { check(it, fetchedAtMs = 0L) }
        val seedGen = (seeded as? Checked.Ok)?.gen
        val persisted = (floorRead as? HighWaterRead.Present)?.value
        val rawCandidates = listOfNotNull(stored, seedGen)
        val keyRecords = buildList {
            persisted?.let(::add)
            stored?.let { add(toHighWater(it)) }
            seedGen?.let { add(toHighWater(it)) }
        }
        if (keyRecords.isEmpty()) {
            highWater = null
            current = null
            val bad = (seeded as? Checked.Bad)?.verdict
            return publish(if (bad != null) IndexState.Failed(null, bad.failure, null) else IndexState.Empty)
        }

        val newestKeysSequence = keyRecords.maxOf { it.keysSequence }
        val newestKeys = keyRecords.filter { it.keysSequence == newestKeysSequence }
        val selectedKeys = newestKeys.first()
        val indexFloor = maxOf(persisted?.indexSequence ?: 0L, stored?.index?.sequence ?: 0L, seedGen?.index?.sequence ?: 0L)
        if (newestKeys.any { !sameKeys(selectedKeys, it.keys, it.keysSig) }) {
            highWater = persisted
            conflictFence = ConflictFence(indexFloor, newestKeysSequence)
            current = null
            val last = rawCandidates.maxWithOrNull(compareBy<Generation>({ it.index.sequence }, { it.fetchedAtMs }))?.index
            return publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, last))
        }

        val targetFloor = HighWater(indexFloor, newestKeysSequence, selectedKeys.keys.copyOf(), selectedKeys.keysSig.copyOf())
        if (persisted == null || !sameHighWater(persisted, targetFloor)) {
            try { saveHighWater(targetFloor) } catch (e: IOException) {
                highWater = persisted
                current = null
                val last = rawCandidates.maxWithOrNull(compareBy<Generation>({ it.index.sequence }, { it.fetchedAtMs }))?.index
                return publish(IndexState.Failed(null, null, last))
            }
        }
        highWater = targetFloor
        conflictFence = null

        val accepted = mutableListOf<Generation>()
        val failures = mutableListOf<IndexFailure>()
        for (candidate in rawCandidates) {
            when (val verdict = verifier.verify(
                candidate.bundle.index,
                candidate.bundle.indexSig,
                targetFloor.keys,
                targetFloor.keysSig,
                indexFloor.takeIf { it > 0L },
                MinisignIndexVerifier.VERIFY_IGNORING_EXPIRY,
            )) {
                is IndexVerdict.Bad -> failures += verdict.failure
                is IndexVerdict.Ok -> accepted += Generation(
                    IndexBundle(candidate.bundle.index, candidate.bundle.indexSig, targetFloor.keys, targetFloor.keysSig),
                    verdict.index,
                    targetFloor.keysSequence,
                    candidate.fetchedAtMs,
                )
            }
        }
        val best = accepted.maxWithOrNull(compareBy<Generation>({ it.index.sequence }, { it.fetchedAtMs }))
        current = best
        if (best != null) return publish(ready(best))

        val displayOnly = rawCandidates.maxWithOrNull(compareBy<Generation>({ it.index.sequence }, { it.fetchedAtMs }))?.index
        val seedFailure = (seeded as? Checked.Bad)?.verdict?.failure
        val failure = when {
            IndexFailure.REVOKED_KEY in failures -> IndexFailure.REVOKED_KEY
            IndexFailure.ROLLBACK in failures -> IndexFailure.ROLLBACK
            failures.isNotEmpty() -> failures.first()
            seedFailure != null -> seedFailure
            else -> IndexFailure.ROLLBACK
        }
        return publish(IndexState.Failed(null, failure, displayOnly))
    }

    private fun verifyKeys(bytes: ByteArray, signature: ByteArray): KeysFile? {
        rootKeysVerifier?.let { return it.verify(bytes, signature) }
        (verifier as? RootKeysVerifier)?.let { return it.verify(bytes, signature) }
        val minisignVerifier = verifier as? MinisignIndexVerifier ?: return null
        return (minisignVerifier.verifyKeys(bytes, signature) as? MinisignIndexVerifier.KeysVerdict.Ok)?.keys
    }

    private fun toHighWater(gen: Generation) = HighWater(
        gen.index.sequence,
        gen.keysSequence,
        gen.bundle.keys.copyOf(),
        gen.bundle.keysSig.copyOf(),
    )

    private fun sameKeys(floor: HighWater, keys: ByteArray, keysSig: ByteArray): Boolean =
        floor.keys.contentEquals(keys) && floor.keysSig.contentEquals(keysSig)

    private fun sameHighWater(a: HighWater, b: HighWater): Boolean =
        a.indexSequence == b.indexSequence && a.keysSequence == b.keysSequence && sameKeys(a, b.keys, b.keysSig)

    private suspend fun readHighWater(): HighWaterRead {
        val result = try {
            store.read(HIGH_WATER_PATH) { bytes ->
                try {
                    if (bytes.size > HIGH_WATER_MAX_BYTES) return@read HighWaterRead.Invalid
                    val doc = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                        ?: return@read HighWaterRead.Invalid
                    if (doc["schema"]?.jsonPrimitive?.long != HIGH_WATER_SCHEMA) return@read HighWaterRead.Invalid
                    val indexSequence = doc["index_sequence"]?.jsonPrimitive?.long ?: return@read HighWaterRead.Invalid
                    val keysSequence = doc["keys_sequence"]?.jsonPrimitive?.long ?: return@read HighWaterRead.Invalid
                    if (indexSequence < 0L || keysSequence < 1L) return@read HighWaterRead.Invalid
                    val b64 = Base64.getDecoder()
                    val keys = b64.decode(doc["keys"]?.jsonPrimitive?.content ?: return@read HighWaterRead.Invalid)
                    val keysSig = b64.decode(doc["keys_sig"]?.jsonPrimitive?.content ?: return@read HighWaterRead.Invalid)
                    if (keys.size > KeysFile.MAX_BYTES || keysSig.size > IndexLimits.SIG_BYTES) return@read HighWaterRead.Invalid
                    val verified = verifyKeys(keys, keysSig) ?: return@read HighWaterRead.Invalid
                    if (verified.sequence != keysSequence) return@read HighWaterRead.Invalid
                    HighWaterRead.Present(HighWater(indexSequence, keysSequence, keys, keysSig))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HighWaterRead.Invalid
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HighWaterRead.Invalid
        }
        return result ?: HighWaterRead.Missing
    }

    private suspend fun saveHighWater(floor: HighWater) {
        val b64 = Base64.getEncoder()
        val doc = buildJsonObject {
            put("schema", HIGH_WATER_SCHEMA)
            put("index_sequence", floor.indexSequence)
            put("keys_sequence", floor.keysSequence)
            put("keys", b64.encodeToString(floor.keys))
            put("keys_sig", b64.encodeToString(floor.keysSig))
        }
        store.write(HIGH_WATER_PATH, doc.toString().toByteArray(Charsets.UTF_8))
    }

    private fun ready(gen: Generation): IndexState.Ready =
        IndexState.Ready(gen.index, stale = clock.nowMs() >= gen.index.expiresAtMs, fetchedAtMs = gen.fetchedAtMs)

    private fun publish(s: IndexState): IndexState { state.value = s; return s }

    // The index to install from now (IndexState.installableIndex against this registry's clock).
    fun installableIndex(): VerifiedIndex? = state.value.installableIndex(clock.nowMs())

    private sealed interface Checked { data class Ok(val gen: Generation) : Checked; data class Bad(val verdict: IndexVerdict.Bad) : Checked }

    private fun check(bundle: IndexBundle, fetchedAtMs: Long): Checked =
        when (val v = verifier.verify(bundle.index, bundle.indexSig, bundle.keys, bundle.keysSig, null, MinisignIndexVerifier.VERIFY_IGNORING_EXPIRY)) {
            is IndexVerdict.Bad -> Checked.Bad(v)
            is IndexVerdict.Ok -> keysSequenceOf(bundle.keys)?.let { Checked.Ok(Generation(bundle, v.index, it, fetchedAtMs)) }
                ?: Checked.Bad(IndexVerdict.Bad(IndexFailure.BAD_KEYS_FILE, "keys file unreadable"))
        }

    private suspend fun download(url: String, max: Int): ByteArray {
        val r = http.get(url, null, null)
        val body = r.body
        if (r.code != 200 || body == null) { body?.close(); throw FetchException(FetchFailure.HTTP_ERROR, r.code, "HTTP ${r.code}") }
        if (r.length != null && r.length > max) { body.close(); throw FetchException(FetchFailure.TOO_LARGE, null, "$url is ${r.length} bytes") }
        body.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw FetchException(FetchFailure.TOO_LARGE, null, "$url is larger than $max bytes")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // The replaceable generation cache is atomic; monotonic trust state lives in HIGH_WATER_PATH.
    private suspend fun save(gen: Generation) {
        val b64 = Base64.getEncoder()
        val doc = buildJsonObject {
            put("schema", 1)
            put("sequence", gen.index.sequence)
            put("keys_sequence", gen.keysSequence)
            put("fetched_at_ms", gen.fetchedAtMs)
            put("index", b64.encodeToString(gen.bundle.index))
            put("index_sig", b64.encodeToString(gen.bundle.indexSig))
            put("keys", b64.encodeToString(gen.bundle.keys))
            put("keys_sig", b64.encodeToString(gen.bundle.keysSig))
        }
        store.write(CACHE_PATH, doc.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun loadStored(): Generation? {
        val doc = try {
            store.read(CACHE_PATH) { Json.parseToJsonElement(it.toString(Charsets.UTF_8)) as? JsonObject }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val b64 = Base64.getDecoder()
            fun field(k: String) = b64.decode((doc[k] as JsonPrimitive).content)
            val bundle = IndexBundle(field("index"), field("index_sig"), field("keys"), field("keys_sig"))
            val fetchedAt = doc["fetched_at_ms"]!!.jsonPrimitive.long
            (check(bundle, fetchedAt) as? Checked.Ok)?.gen
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CACHE_PATH = "registry/index-cache.json"
        const val HIGH_WATER_PATH = "registry/index-high-water.json"
        private const val HIGH_WATER_SCHEMA = 1L
        private const val HIGH_WATER_MAX_BYTES = 128 * 1024
    }
}

object IndexLimits {
    const val INDEX_BYTES = 1024 * 1024
    const val KEYS_BYTES = 64 * 1024
    const val SIG_BYTES = 4096
}

// The index the phone may install from at nowMs: verified and not past its expiry. A stale list,
// or the last list behind a failed refresh, is shown (shownIndex) but this returns null for it.
// Ready.stale is computed when the state is published and does not change while the process lives,
// so the expiry is checked again here against the caller's clock: a Ready published before the
// expiry must not stay installable after it.
fun IndexState.installableIndex(nowMs: Long): VerifiedIndex? = when (this) {
    is IndexState.Ready -> index.takeIf { !stale && nowMs < it.expiresAtMs }
    else -> null
}

// The index to show, fresh or not (Ready, or the last good one behind a failure).
fun IndexState.shownIndex(): VerifiedIndex? = when (this) {
    is IndexState.Ready -> index
    is IndexState.Failed -> last
    IndexState.Empty -> null
}
