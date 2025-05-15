package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.currencyii.FrostSendDelegate

class FrostKeyGenEngine(private val threshold: Int, private val participants: List<Peer>, private val send: (Peer, ByteArray) -> Unit) {
    // Round 1 variables (only for this participant)
    private lateinit var a: List<Long> // Polynomial coefficients a_j for this participant
    private var commitment: List<Long>? = null // Public commitment for this participant
    private var proof: Pair<Long, Long>? = null // Zero-knowledge proof (R, z) for this participant

    // Round 2 variables (only for this participant)
    private lateinit var shares: List<Long> // Shares this participant sends to others (or receives)
    private var signingShare: Long? = null // This participant's signing share s_i
    private var verificationShare: Any? = null // This participant's public verification share Y_i
    private var groupPublicKey: Any? = null // Group public key Y (can be computed by all)

    fun generate(): KeyGenResult {
        round1()
        round2()
        return KeyGenResult(success = true)
    }

    // Round 1: Polynomial sampling, commitment, proof, broadcast
    private fun round1() {
        // Round 1.1: Polynomial sampling (for this participant only)
        val q = 0x7FFFFFFFFFFFFFFF // a large prime, as Zq (example)
        val random = java.util.Random()
        // Only sample this participant's coefficients
        a = List(threshold) { Math.abs(random.nextLong()) % q }

        // Round 1.2: Proof of knowledge of the corresponding secret a[0]
        // Simulate group generator g (for demonstration, use 2L)
        val g = 2L
        val context = CONTEXT_STRING // use the static context string
        val k = Math.abs(random.nextLong()) % q // random k in Zq
        val R = modPow(g, k, q) // R = g^k mod q
        val ai0 = a[0] // secret coefficient
        val g_ai0 = modPow(g, ai0, q) // g^ai0 mod q
        val c = hashToLong(context, g_ai0, R, q) // c = H(context, g^ai0, R) mod q
        val z = (k + ai0 * c) % q // z = k + ai0 * c mod q
        // Store proof as a pair (R, z)
        proof = Pair(R, z)

        // Round 1.3: compute a public commitment
        // C_j = [g^a_j0, g^a_j1, ..., g^a_j(t-1)]
        commitment = a.map { coeff -> modPow(g, coeff, q) }

        // Round 1.4: Broadcast commitment and proof to all participants
        for (peer in participants) {
            // Send commitment and proof to each peer
            // This is a placeholder for actual network communication
            Log.i("Frost", "Sending commitment to ${peer.publicKey.keyToBin().toHex()}")
            Log.i("Frost", "Sending commitment to ${peer.publicKey.keyToBin().toHex()}")
            // TODO: Replace with actual network communication
        }
    }

    companion object {
        const val CONTEXT_STRING = "FROST-KeyGen"
        // Round 1.5: verify commitment
        fun verifyProof(
            commitment0: Long, // C_0 = g^a_0
            proof: Pair<Long, Long>, // (R, z)
            g: Long,
            q: Long
        ): Boolean {
            val (R, miu) = proof
            val c = hashToLongStatic(CONTEXT_STRING, commitment0, R, q)
            val g_z = modPowStatic(g, miu, q)
            val C0_c = modPowStatic(commitment0, c, q)
            val C0_c_inv = modInverse(C0_c, q)
            val R_prime = (g_z * C0_c_inv) % q
            return R_prime == R // proof's supposed to be deleted if this is true
        }

        // Static version for use in companion object
        private fun modPowStatic(base: Long, exp: Long, mod: Long): Long {
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

        private fun hashToLongStatic(context: String, g_ai0: Long, R: Long, q: Long): Long {
            val input = "$context|$g_ai0|$R"
            return (input.hashCode().toLong() and Long.MAX_VALUE) % q
        }

        private fun modInverse(a: Long, mod: Long): Long {
            var t = 0L
            var newt = 1L
            var r = mod
            var newr = a
            while (newr != 0L) {
                val quotient = r / newr
                t = newt.also { newt = t - quotient * newt }
                r = newr.also { newr = r - quotient * newr }
            }
            if (r > 1) throw IllegalArgumentException("a is not invertible")
            if (t < 0) t += mod
            return t
        }
    }

    // Helper: modular exponentiation
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

    // Helper: simple hash to Long (for demonstration, not cryptographically secure)
    private fun hashToLong(context: String, g_ai0: Long, R: Long, q: Long): Long {
        val input = "$context|$g_ai0|$R"
        return (input.hashCode().toLong() and Long.MAX_VALUE) % q
    }

    // Round 2: Share distribution, verification, signing share and public key share calculation
    private fun round2() {
        // TODO: Implement share distribution, verification, signing share and public key share calculation
    }
}
