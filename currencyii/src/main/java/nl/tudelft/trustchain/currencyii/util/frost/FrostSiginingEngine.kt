package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Serializable
import java.math.BigInteger
import java.util.Base64
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class FrostSiginingEngine(
    private val isLeader: Boolean,
    private val leaderId: String,
    private val participantIndex: Int,
    private val walletId: String,
    private val sessionId: String,
    private val threshold: Int,
    private val pi: Int,
    val broadcast: (Serializable) -> Unit,
    val myPeer: Peer,
    )
{
    val storedNonces = ConcurrentHashMap<String, MutableList<Pair<BigInteger, BigInteger>>>();
    val nonceIndex = ConcurrentHashMap<String, Int>();

    fun setStorednNonces(nonces: ConcurrentHashMap<String, MutableList<Pair<BigInteger, BigInteger>>>) {
        this.storedNonces.clear()
        this.nonceIndex.clear()
        this.storedNonces.putAll(nonces)
        for (peerId in storedNonces.keys) {
            nonceIndex[peerId] = 0
        }
    }

    fun sign(joinerId: String) {
        Log.i("Frost", "I am signing with ${joinerId}")
        val nonceMap = ConcurrentHashMap<String, Pair<BigInteger, BigInteger>>()
        for ((peerId, nonceList) in storedNonces) {
            val index = nonceIndex[peerId]
            val noncePair = nonceList[index!!]
            nonceMap[peerId] = noncePair
            Log.i("Frost", "I really sent ${noncePair.first} and ${noncePair.second} to ${peerId}")
        }
        broadcast(FrostNonceToParticipantMessage(walletId, sessionId, joinerId, nonceMap).toFrostPayload())
    }

    val collectedZi = ConcurrentHashMap<Int, BigInteger>()
    var signed = false
    var finalSignature = BigInteger.ZERO

    fun onReceivedZiFromParticipant(
        pariticipantIndex: Int,
        z_i: BigInteger,
        joinerId: String,
    ) {
        Log.i("FrostSigining", "I received z_i from participant $pariticipantIndex: $z_i")
        collectedZi[pariticipantIndex] = z_i
        finalSignature += z_i
        if (collectedZi.size * 100 >= threshold * storedNonces.size) {
            Log.i("FrostSigining", "I broadcast the final signature $finalSignature on chain for $joinerId")
            broadcast(FrostSigningResponseToJoinerMessage(walletId, sessionId, finalSignature, joinerId).toFrostPayload())
            signed = true
        }
    }

    fun onReceivedNonceFromSA(
        m: ByteArray,
        B: Map<String, Pair<BigInteger, BigInteger>>,
        mySecretShare: BigInteger,
        allVerificationShares: Map<String, BigInteger>
    ) {
        val myPeerId = Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin())

        Log.i("FrostSigining", "My peer ID is $myPeerId")

        // 1. Compute binding values rho_j = H1(j, m, B)
        val rho = B.mapValues { (peerId, _) ->
            hashToZq(
                "FROST-bind".toByteArray(),
                peerId.toByteArray(),
                m,
                serializeB(B)
            )
        }

        Log.i("FrostSiginning", "My rho values are $rho")

        // 2. Group commitment R = prod_j D_j * E_j^rho_j mod p
        var R = BigInteger.ONE
        B.forEach { (peerId, pair) ->
            val (D, E) = pair
            R = R.multiply(D.multiply(E.modPow(rho.getValue(peerId), FrostConstants.p))).mod(FrostConstants.p)
        }

        Log.i("FrostSigining", "My R value is $R")

        // 3. Challenge c = H2(R, Y, m)
        var Y = BigInteger.ONE
        allVerificationShares.values.forEach { Y = Y.multiply(it).mod(FrostConstants.p) }
        val c = hashToZq(
            "FROST-chal".toByteArray(),
            R.toByteArray(),
            Y.toByteArray(),
            m
        )

        Log.i("FrostSigining", "My c value is $c")

        // 4. Lagrange coefficient lambda_i
        val lambdaI = lagrangeCoefficient(myPeerId, B.keys.toList(), FrostConstants.n)

        Log.i("FrostSigining", "My lambda_i value is $lambdaI")

        // 5. Own nonce (d_i, e_i)
        val idx = nonceIndex.getOrDefault(myPeerId, 0)
        val (d_i, e_i) = storedNonces.getValue(myPeerId)[idx]

        Log.i("FrostSigining", "My d_i and e_i values are $d_i and $e_i")

        // 6. Compute z_i = d_i + e_i * rho_i + lambda_i * s_i * c mod n
        val z_i = d_i
            .add(e_i.multiply(rho.getValue(myPeerId)))
            .add(lambdaI.multiply(mySecretShare).multiply(c))
            .mod(FrostConstants.n)

        Log.i("Frost", "Response z_i computed: $z_i")

        // 7. Cleanup
        storedNonces.getValue(myPeerId).removeAt(idx)
        nonceIndex[myPeerId] = idx + 1
        // 8. Send to SA
        Log.i("Frost", "My session Id is $sessionId")
        broadcast(FrostSigningResponseToSAMessage(walletId, sessionId, participantIndex, z_i).toFrostPayload())
    }

    private fun serializeB(B: Map<String, Pair<BigInteger, BigInteger>>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        B.forEach { (id, pair) ->
            baos.write(id.toByteArray())
            baos.write(pair.first.toByteArray())
            baos.write(pair.second.toByteArray())
        }
        return baos.toByteArray()
    }

    private fun hashToZq(vararg data: ByteArray): BigInteger {
        val md = MessageDigest.getInstance("SHA-256")
        data.forEach { md.update(it) }
        return BigInteger(1, md.digest()).mod(FrostConstants.n)
    }

    private fun lagrangeCoefficient(
        id: String,
        allIds: List<String>,
        modulus: BigInteger
    ): BigInteger {
        val xi = BigInteger(id.toByteArray())
        var num = BigInteger.ONE
        var den = BigInteger.ONE
        allIds.forEach { jId ->
            if (jId == id) return@forEach
            val xj = BigInteger(jId.toByteArray())
            num = num.multiply(xj.negate()).mod(modulus)
            den = den.multiply(xi.subtract(xj)).mod(modulus)
        }
        return num.multiply(den.modInverse(modulus)).mod(modulus)
    }
}
