package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.util.toHex
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import java.util.Base64
import java.math.BigInteger
import java.security.MessageDigest

class FrostKeyGenEngine(
    private val threshold: Int, 
    private val participantId: String,
    private val participants: List<Peer>, 
    private val sessionId: String,
    private val send: (Peer, ByteArray) -> Unit
) {
    // Round 1 variables (only for this participant)
    private lateinit var a: List<BigInteger> // Polynomial coefficients a_j for this participant
    private var commitment: List<BigInteger>? = null // Public commitment for this participant
    private var proof: Pair<BigInteger, BigInteger>? = null // Zero-knowledge proof (R, z) for this participant

    // Round 2 variables (only for this participant)
    private lateinit var shares: MutableMap<String, BigInteger> // Shares this participant sends to others (or receives)
    private var signingShare: BigInteger? = null // This participant's signing share s_i
    private var verificationShare: BigInteger? = null // This participant's public verification share Y_i
    private var groupPublicKey: BigInteger? = null // Group public key Y (can be computed by all)
    
    // Received commitments and verification shares from other participants
    private val commitments = ConcurrentHashMap<String, List<BigInteger>>()
    private val proofs = ConcurrentHashMap<String, Pair<BigInteger, BigInteger>>()
    private val verificationShares = ConcurrentHashMap<String, BigInteger>()
    
    // Locks for synchronization
    private val commitmentsMutex = Mutex()
    private val verificationSharesMutex = Mutex()
    
    // Peer ID mapping
    private val peerIdMapping = HashMap<String, Peer>()
    
    // Secure random for better security
    private val secureRandom = SecureRandom()

    companion object FrostConstants {
        // A large prime (in a production environment, use a cryptographically secure prime)
        val p: BigInteger = BigInteger("170141183460469231731687303715884105727") // prime
        // A generator of the group (in a production environment, use a proper generator)
        val g: BigInteger = BigInteger.valueOf(3)
        // Modulus
        val n: BigInteger = p.subtract(BigInteger.ONE) // n = p - 1
        // Timeout for waiting for responses (in ms)
        const val RESPONSE_TIMEOUT = 30000L // 30 seconds
    }

    init {
        // Initialize peer ID mapping
        for (peer in participants) {
            val peerId = Base64.getEncoder().encodeToString(peer.publicKey.keyToBin())
            peerIdMapping[peerId] = peer
        }
    }

    suspend fun generate(): KeyGenResult {
        try {
            round1()
        
            println("Waiting for commitments...")
            if (!waitForCommitments()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Timeout waiting for commitments from all participants"
                )
            }

            println("Commitments received. Verifying...")
            if (!verifyAllCommitments()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Failed to verify commitments from all participants"
                )
            }

            println("round2() starting")
            round2()

            println("Waiting for verification shares...")
            if (!waitForVerificationShares()) {
                return KeyGenResult(
                    success = false,
                    errorMessage = "Timeout waiting for verification shares from all participants"
                )
            }
            
            println("Verification shares received. Calculating group public key...")
            computeGroupPublicKey()

            println("Key generation succeeded")
            return KeyGenResult(
                success = true,
                signingShare = signingShare,
                verificationShare = verificationShare,
                groupPublicKey = groupPublicKey,
                participants = participants.map { Base64.getEncoder().encodeToString(it.publicKey.keyToBin()) },
                threshold = threshold
            )
        } catch (e: Exception) {
            println("❗ Exception in generate(): ${e.message}")
            return KeyGenResult(
                success = false,
                errorMessage = "Error in FROST key generation: ${e.message}"
            )
        }
    }

    // Round 1: Polynomial sampling, commitment, proof, broadcast
    private fun round1() {
        // Round 1.1: Sample a polynomial (a) of degree (threshold – 1) (for this participant only)
        a = List(threshold) { randomZp() }

        // Round 1.2: Compute a commitment (C) as [g^a_0, g^a_1, ..., g^a_{t-1}] (mod q)
        commitment = a.map { coeff -> FrostConstants.g.modPow(coeff, FrostConstants.p) }
        println("peerId: ${participantId} --- commitment: ${commitment}")

        // Round 1.3: Compute a zero-knowledge proof (R, z)
        val ai0 = a[0]
        val g_ai0 = FrostConstants.g.modPow(ai0, FrostConstants.p)
        val k = randomZp()
        val r = FrostConstants.g.modPow(k, FrostConstants.p)
        val c = hashToBigInt("FROST-KeyGen", g_ai0, r, FrostConstants.n)
        val z = k.add(ai0.multiply(c)).mod(FrostConstants.n)
        proof = Pair(r, z)

        // Round 1.4: Broadcast commitment and proof to all participants
        val commitmentMessage = FrostCommitmentMessage(sessionId, commitment!!, proof!!)
        val message = commitmentMessage.toFrostMessage()
        
        val self = peerIdMapping[participantId]
        send(self!!, message.serialize())
        
        // Also store our own commitment
        commitments[participantId] = commitment!!
        proofs[participantId] = proof!!
    }

    // Round 2: Share distribution, verification, signing share and public key share calculation
    private fun round2() {
        // Initialize shares map
        shares = mutableMapOf()
        
        // Round 2.1: Compute signing share (s_i) and verification share (Y_i)
        // The signing share is just a[0], the constant term of our polynomial
        signingShare = a[0]
        
        // The verification share is g^s_i mod q
        verificationShare = FrostConstants.g.modPow(signingShare!!, FrostConstants.p)
        println("peerId: ${participantId} --- verificationShare: ${verificationShare}")
        
        // Round 2.2: Broadcast verification share to all participants
        val verificationShareMessage = FrostVerificationShareMessage(sessionId, verificationShare!!)
        val message = verificationShareMessage.toFrostMessage()
        
        val self = peerIdMapping[participantId]
        send(self!!, message.serialize())

        // Also store our own verification share
        verificationShares[participantId] = verificationShare!!
    }
    
    private fun computeGroupPublicKey() {
        var product = BigInteger("1")
        for ((_, commit) in commitments) {
            product = product.multiply(commit[0]).mod(FrostConstants.p)
        }
        println("peerId: ${participantId} calculated the following group public key: $product")
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
            delay(100) // Check every 100ms
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
            delay(100) // Check every 100ms
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
    private fun verifyCommitment(commitment: List<BigInteger>, proof: Pair<BigInteger, BigInteger>): Boolean {
        val g_ai0 = commitment[0] // g^a_0 mod q
        val r = proof.first
        val z = proof.second
        
        val c = hashToBigInt("FROST-KeyGen", g_ai0, r, FrostConstants.n)
        val lhs = FrostConstants.g.modPow(z, FrostConstants.p)
        val rhs = r.multiply(g_ai0.modPow(c, FrostConstants.p)).mod(FrostConstants.p)

        return lhs == rhs
    }
    
    // Process a received commitment message
    suspend fun processCommitmentMessage(peerId: String, commitment: List<BigInteger>, proof: Pair<BigInteger, BigInteger>) {
        commitmentsMutex.withLock {
            commitments[peerId] = commitment
            proofs[peerId] = proof
        }
    }
    
    // Process a received verification share message
    suspend fun processVerificationShareMessage(peerId: String, verificationShare: BigInteger) {
        verificationSharesMutex.withLock {
            verificationShares[peerId] = verificationShare
        }
    }

    private fun hashToBigInt(context: String, g_ai0: BigInteger, r: BigInteger, modulus: BigInteger): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(context.toByteArray())
        digest.update(g_ai0.toByteArray().stripLeadingZero())
        digest.update(r.toByteArray().stripLeadingZero())

        val hash = digest.digest()
        return BigInteger(1, hash).mod(modulus)
    }

    // Helper to strip sign byte
    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.size > 1 && this[0] == 0.toByte()) this.copyOfRange(1, this.size) else this
    }

    // Helper to generate a random integer in Zp
    private fun randomZp(): BigInteger {
        var r: BigInteger
        do {
            r = BigInteger(FrostConstants.p.bitLength(), secureRandom)
        } while (r >= FrostConstants.p || r == BigInteger.ZERO)
        return r
    }
}
