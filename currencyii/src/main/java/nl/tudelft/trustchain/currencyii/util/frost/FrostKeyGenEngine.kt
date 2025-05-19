package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.util.toHex
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap

class FrostKeyGenEngine(
    private val threshold: Int, 
    private val participants: List<Peer>, 
    private val sessionId: String,
    private val send: (Peer, ByteArray) -> Unit
) {
    // Round 1 variables (only for this participant)
    private lateinit var a: List<Long> // Polynomial coefficients a_j for this participant
    private var commitment: List<Long>? = null // Public commitment for this participant
    private var proof: Pair<Long, Long>? = null // Zero-knowledge proof (R, z) for this participant

    // Round 2 variables (only for this participant)
    private lateinit var shares: MutableMap<String, Long> // Shares this participant sends to others (or receives)
    private var signingShare: Long? = null // This participant's signing share s_i
    private var verificationShare: Long? = null // This participant's public verification share Y_i
    private var groupPublicKey: Long? = null // Group public key Y (can be computed by all)
    
    // Received commitments and verification shares from other participants
    private val commitments = ConcurrentHashMap<String, List<Long>>()
    private val proofs = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val verificationShares = ConcurrentHashMap<String, Long>()
    
    // Locks for synchronization
    private val commitmentsMutex = Mutex()
    private val verificationSharesMutex = Mutex()
    
    // Peer ID mapping
    private val peerIdMapping = HashMap<String, Peer>()
    
    // Secure random for better security
    private val secureRandom = SecureRandom()

    companion object FrostConstants {
        // A large prime (in a production environment, use a cryptographically secure prime)
        const val q: Long = 0x7FFFFFFFFFFFFFFF // 2^63-1, which is a Mersenne prime
        // A generator of the group (in a production environment, use a proper generator)
        const val g: Long = 2L
        // Timeout for waiting for responses (in ms)
        const val RESPONSE_TIMEOUT = 30000L // 30 seconds
    }

    init {
        // Initialize peer ID mapping
        for (peer in participants) {
            val peerId = peer.publicKey.keyToBin().toHex()
            peerIdMapping[peerId] = peer
        }
    }

    fun generate(): KeyGenResult {
        try {
            round1()
            // Wait for all commitments to be received
            if (!waitForCommitments()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Timeout waiting for commitments from all participants"
                )
            }
            
            // Verify all received commitments and proofs
            if (!verifyAllCommitments()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Failed to verify commitments from all participants"
                )
            }
            
            round2()
            // Wait for all verification shares to be received
            if (!waitForVerificationShares()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Timeout waiting for verification shares from all participants"
                )
            }
            
            // Compute the final group public key
            computeGroupPublicKey()
            
            return KeyGenResult(
                success = true,
                signingShare = signingShare,
                verificationShare = verificationShare,
                groupPublicKey = groupPublicKey,
                participants = participants.map { it.publicKey.keyToBin().toHex() },
                threshold = threshold
            )
        } catch (e: Exception) {
            Log.e("Frost", "Error in FROST key generation: ${e.message}", e)
            return KeyGenResult(
                success = false,
                errorMessage = "Error in FROST key generation: ${e.message}"
            )
        }
    }

    // Round 1: Polynomial sampling, commitment, proof, broadcast
    private fun round1() {
        // Round 1.1: Sample a polynomial (a) of degree (threshold – 1) (for this participant only)
        a = List(threshold) { Math.abs(secureRandom.nextLong()) % FrostConstants.q }

        // Round 1.2: Compute a commitment (C) as [g^a_0, g^a_1, ..., g^a_{t-1}] (mod q)
        commitment = a.map { coeff -> modPow(FrostConstants.g, coeff, FrostConstants.q) }

        // Round 1.3: Compute a zero-knowledge proof (R, z)
        val ai0 = a[0] // secret coefficient (a_0)
        val g_ai0 = modPow(FrostConstants.g, ai0, FrostConstants.q) // g^a_0 mod q
        val k = Math.abs(secureRandom.nextLong()) % FrostConstants.q // random k in Zq
        val r = modPow(FrostConstants.g, k, FrostConstants.q) // R = g^k mod q
        val c = hashToLong("FROST-KeyGen", g_ai0, r, FrostConstants.q) // c = H(context, g^a_0, R) mod q
        val z = (k + ai0 * c) % FrostConstants.q // z = k + a_0 * c mod q
        proof = Pair(r, z)

        // Round 1.4: Broadcast commitment and proof to all participants
        val commitmentMessage = FrostCommitmentMessage(sessionId, commitment!!, proof!!)
        val message = commitmentMessage.toFrostMessage()
        
        for (peer in participants) {
            send(peer, message.serialize())
        }
        
        // Also store our own commitment
        val myPeerId = participants.first { it.publicKey.keyToBin().contentEquals(myPeer().publicKey.keyToBin()) }
            .publicKey.keyToBin().toHex()
        commitments[myPeerId] = commitment!!
        proofs[myPeerId] = proof!!
    }

    // Round 2: Share distribution, verification, signing share and public key share calculation
    private fun round2() {
        // Initialize shares map
        shares = mutableMapOf()
        
        // Round 2.1: Compute signing share (s_i) and verification share (Y_i)
        // The signing share is just a[0], the constant term of our polynomial
        signingShare = a[0]
        
        // The verification share is g^s_i mod q
        verificationShare = modPow(FrostConstants.g, signingShare!!, FrostConstants.q)
        
        // Round 2.2: Broadcast verification share to all participants
        val verificationShareMessage = FrostVerificationShareMessage(sessionId, verificationShare!!)
        val message = verificationShareMessage.toFrostMessage()
        
        for (peer in participants) {
            send(peer, message.serialize())
        }
        
        // Also store our own verification share
        val myPeerId = participants.first { it.publicKey.keyToBin().contentEquals(myPeer().publicKey.keyToBin()) }
            .publicKey.keyToBin().toHex()
        verificationShares[myPeerId] = verificationShare!!
    }
    
    // Compute group public key as the product of all verification shares
    private fun computeGroupPublicKey() {
        var product = 1L
        for (share in verificationShares.values) {
            product = (product * share) % FrostConstants.q
        }
        groupPublicKey = product
    }
    
    // Wait for all commitments to be received
    private suspend fun waitForCommitments(): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT) {
            commitmentsMutex.withLock {
                if (commitments.size == participants.size) {
                    return true
                }
            }
            Thread.sleep(100) // Check every 100ms
        }
        return false
    }
    
    // Wait for all verification shares to be received
    private suspend fun waitForVerificationShares(): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT) {
            verificationSharesMutex.withLock {
                if (verificationShares.size == participants.size) {
                    return true
                }
            }
            Thread.sleep(100) // Check every 100ms
        }
        return false
    }
    
    // Verify all commitments and proofs
    private fun verifyAllCommitments(): Boolean {
        for ((peerId, commitment) in commitments) {
            val proof = proofs[peerId] ?: return false
            if (!verifyCommitment(commitment, proof)) {
                return false
            }
        }
        return true
    }
    
    // Verify a single commitment and proof
    private fun verifyCommitment(commitment: List<Long>, proof: Pair<Long, Long>): Boolean {
        val g_ai0 = commitment[0] // g^a_0 mod q
        val r = proof.first
        val z = proof.second
        
        val c = hashToLong("FROST-KeyGen", g_ai0, r, FrostConstants.q)
        val lhs = modPow(FrostConstants.g, z, FrostConstants.q)
        val rhs = (r * modPow(g_ai0, c, FrostConstants.q)) % FrostConstants.q
        
        return lhs == rhs
    }
    
    // Process a received commitment message
    suspend fun processCommitmentMessage(peerId: String, commitment: List<Long>, proof: Pair<Long, Long>) {
        commitmentsMutex.withLock {
            commitments[peerId] = commitment
            proofs[peerId] = proof
        }
    }
    
    // Process a received verification share message
    suspend fun processVerificationShareMessage(peerId: String, verificationShare: Long) {
        verificationSharesMutex.withLock {
            verificationShares[peerId] = verificationShare
        }
    }

    // Helper: modular exponentiation (b^e mod m)
    private fun modPow(base: Long, exp: Long, mod: Long): Long {
        var result = 1L
        var b = base % mod
        var e = exp
        while (e > 0) {
            if ((e and 1L) == 1L) result = (result * b) % mod
            b = (b * b) % mod
            e = e shr 1
        }
        return result
    }

    // Helper: hash function to convert inputs to a Long (within range q)
    private fun hashToLong(context: String, g_ai0: Long, r: Long, q: Long): Long {
        val input = "$context|$g_ai0|$r"
        return (input.hashCode().toLong() and Long.MAX_VALUE) % q
    }
    
    // Helper to get the current peer from the participant list
    private fun myPeer(): Peer {
        return participants.first { it.publicKey.keyToBin().contentEquals(participants[0].publicKey.keyToBin()) }
    }
}
