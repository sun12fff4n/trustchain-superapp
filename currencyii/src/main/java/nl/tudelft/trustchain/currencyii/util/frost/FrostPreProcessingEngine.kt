package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import kotlinx.coroutines.sync.withLock
import nl.tudelft.ipv8.messaging.Serializable
import java.math.BigInteger
import java.security.SecureRandom
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine for FROST signing pre-processing.
 * Generates π nonce/commitment pairs prior to signing.
 */
class FrostPreProcessingEngine(
    private val isLeader: Boolean,
    private val leaderId: String,
    private val participantIndex: Int,
    private val walletId: String,
    private val sessionId: String,
    private val pi: Int,
    val broadcast: (Serializable) -> Unit,
) {
    // List of commitment pairs Li = [(Dij, Eij)] for j=1..π
    private val Li: MutableList<Pair<BigInteger, BigInteger>> = mutableListOf()

    // Storage of nonce and commitment for later signing: List of (dij, Dij, eij, Eij)
    data class PreprocessEntry(
        val nonceD: BigInteger, val commitmentD: BigInteger,
        val nonceE: BigInteger, val commitmentE: BigInteger
    )

    private val entries: MutableList<PreprocessEntry> = mutableListOf()

    // Secure RNG
    private val secureRandom = SecureRandom()

    /**
     * Round 1: Sample nonces and compute commitments.
     */
    fun round1() {
        Li.clear()
        entries.clear()
        for (j in 1..pi) {
            // 1.a Sample single-use nonces (dij, eij) ← Z*q × Z*q
            val dij = randomZp()
            val eij = randomZp()
            // 1.b Derive commitment shares (Dij, Eij) = (g^dij, g^eij)
            val Dij = FrostConstants.g.modPow(dij, FrostConstants.p)
            val Eij = FrostConstants.g.modPow(eij, FrostConstants.p)
            // 1.c Append to Li and store for signing
            Li.add(Pair(Dij, Eij))
            entries.add(PreprocessEntry(dij, Dij, eij, Eij))
            Log.i("FrostPreProc", "Generated entry #$j: D=$Dij, E=$Eij")
        }
    }

    /**
     * Placeholder for Round 2: publishing Li.
     */
    private fun round2() {
        // Implementation-specific: publish (i, Li) to a predetermined location
        Log.i("FrostPreProc", "Publishing Li for participant $participantIndex: $Li")
        // e.g., send to network, store in shared memory, etc.
        broadcast(FrostNoncesToSAMessage(
            walletId, sessionId,
            leaderId = leaderId,
            noncePairs = Li.toList(), // immutable
        ).toFrostPayload())
    }

    /**
     * Execute preprocessing phase.
     */
    fun generate() {
        if (isLeader) {
            storedNonces.clear()
        }
        round1()
        Log.i("FrostPreProc", "round1() finished with ${Li.size} pairs")
        round2()
        Log.i("FrostPreProc", "round2() finished publishing commitments")
    }

    /**
     * Retrieve the stored nonce & commitment for index j (1-based).
     */
    fun getEntry(j: Int): PreprocessEntry {
        if (j < 1 || j > entries.size) throw IllegalArgumentException("Index out of range: \$j")
        return entries[j - 1]
    }

    // Helper: random in Zq
    private fun randomZp(): BigInteger {
        var r: BigInteger
        do {
            r = BigInteger(FrostConstants.p.bitLength(), secureRandom)
        } while (r >= FrostConstants.p || r == BigInteger.ZERO)
        return r
    }


    val storedNonces = ConcurrentHashMap<String, MutableList<Pair<BigInteger, BigInteger>>>();

    // Process a received nonce list message sent to SA(leader)
    fun processNonceListMessage(peerId: String, nonceList: List<Pair<BigInteger, BigInteger>>) {
        if (!storedNonces.containsKey(peerId)) {
            storedNonces[peerId] = LinkedList()
        }
        storedNonces[peerId]?.addAll(nonceList)
    }

    fun ifCollectAllNonces(participantNum: Int): Boolean {
        return storedNonces.size == participantNum
    }

    fun getAllStoredNonces(): Map<String, List<Pair<BigInteger, BigInteger>>> {
        val result = mutableMapOf<String, List<Pair<BigInteger, BigInteger>>>()
        for ((peerId, nonceList) in storedNonces) {
            result[peerId] = nonceList.toList()
        }
        return result
    }
}
