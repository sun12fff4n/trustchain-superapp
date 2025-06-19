package nl.tudelft.trustchain.currencyii.util.frost.messageSigning

import kotlinx.coroutines.*
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.currencyii.util.frost.FrostConstants
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64


class FrostMessageSigningManager(
    private val signingState: FrostSigningState,
    private val myPeer: Peer,
    private val send: (Peer, ByteArray) -> Unit
) {
    suspend fun signMessage(message: ByteArray): SignedMessage {
        val myPeerId =  Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin())
        val commitmentList = signingState.participants.mapIndexed { index, peer ->
            val (d, e) = signingState.getNonce(peer)
            CommitmentB(index = index + 1, D = d, E = e)
        }

        // Broadcast (m, B) to others
        val broadcastMessage = FrostMessageUtil.serializeMessageWithCommitments(message, commitmentList)
        signingState.participants.filter {
            Base64.getEncoder().encodeToString(it.publicKey.keyToBin()) != myPeerId
        }.forEach { peer ->
            send(peer, broadcastMessage)
        }
        val myPartial = createPartialSignature(myPeer, message)
        signingState.addPartial(myPartial)
        signingState.deleteUsedNonce(myPeer)

        val partials = signingState.waitForEnoughPartials()
        val finalSig = aggregate(partials)

        return SignedMessage(R = finalSig.R, z = finalSig.z, msg = message)
    }

    private fun createPartialSignature(peer: Peer, msg: ByteArray): PartialSignature {
        //compute z_i = d_i + (e_i*rho_i) + lambda_i * s_i *c
        val peerId =  Base64.getEncoder().encodeToString(peer.publicKey.keyToBin())
        val (d, e) = signingState.getNonce(peer)
        val rho = signingState.bindingFactor(peer, msg)
        val s = signingState.getSigningShare(peer)
        // c = H_2(R,Y,m)
        val R = signingState.getCommitmentR(peer)
        val c = signingState.challengeHash(R,signingState.groupPublicKey,msg)
        val lambda = signingState.lambda(peer)
        val z = d.add(e.multiply(rho)).add(lambda.multiply(s).multiply(c)).mod(FrostConstants.n)
        return PartialSignature(peerId = peerId, z = z)
    }

    private fun aggregate(partials: List<PartialSignature>): SignedMessage {
        //7a
        val R = signingState.aggregateCommitmentsWithBindingFactors()
        val c = signingState.challengeHash(R,signingState.groupPublicKey,signingState.currentMessage)
        //7b
        for (partial in partials){
            val peer = signingState.getPeerById(partial.peerId)
            if (!signingState.verifyPartial(peer,partial,c)){
                println("Invalid partial signature from peer ${partial.peerId}\")\n")
            }
        }
        //7c z = sum(z_i)
        val z = partials.map { it.z }.reduce { acc, zi -> acc.add(zi) }.mod(FrostConstants.n)
        //7d
        return SignedMessage(R = R, z = z, msg = signingState.currentMessage)
    }
}


class FrostSigningState(
    val participants: List<Peer>,
    val groupPublicKey: BigInteger,
    val currentMessage: ByteArray,
    private val threshold: Int
) {
    private val signingShares = mutableMapOf<String, BigInteger>()
    private val commitments = mutableMapOf<String, BigInteger>()
    // peerId -> (dᵢ, eᵢ)
    private val nonces = mutableMapOf<String, Pair<BigInteger, BigInteger>>()
    private val partials = mutableListOf<PartialSignature>()
    private val verificationShares = mutableMapOf<String, BigInteger>()


    fun getPeerById(peerId: String): Peer {
        return participants.find { peerId(it) == peerId }
            ?: throw IllegalArgumentException("Peer with ID $peerId not found")
    }

    fun setNonce(peer: Peer, d: BigInteger, e: BigInteger) {
        nonces[peerId(peer)] = Pair(d, e)
    }

    fun getNonce(peer: Peer): Pair<BigInteger, BigInteger> {
        return nonces[peerId(peer)]
            ?: throw IllegalStateException("Nonce for peer ${peerId(peer)} not found.")
    }

    fun deleteUsedNonce(peer: Peer){
        val peerId = peerId(peer)
        nonces.remove(peerId)
        commitments.remove(peerId)
    }
    fun setVerificationShare(peer: Peer, Y: BigInteger) {
        verificationShares[peerId(peer)] = Y
    }

    fun getSigningShare(peer: Peer): BigInteger =
        signingShares[peerId(peer)] ?: error("Missing signing share")


    fun getCommitmentR(peer: Peer): BigInteger =
        commitments[peerId(peer)] ?: error("Missing commitment R")

    //rho = H_1(i, m, B)
    fun bindingFactor(peer: Peer, message: ByteArray): BigInteger{
        val digest = MessageDigest.getInstance("SHA-256")
        val id =  peerId(peer).toByteArray()
        digest.update(id)
        digest.update(message)
        return BigInteger(1,digest.digest()).mod(FrostConstants.n)
    }

    //c = H_2(R,Y,m)
    fun challengeHash(R: BigInteger, Y: BigInteger, msg: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(R.toByteArray())
        digest.update(Y.toByteArray())
        digest.update(msg)
        return BigInteger(1, digest.digest()).mod(FrostConstants.n)
    }

    fun lambda(peer: Peer): BigInteger {
        val i = participants.indexOf(peer) + 1
        val ids = participants.mapIndexed{idx, _ -> BigInteger.valueOf((idx+1).toLong())}
        val xi = BigInteger.valueOf(i.toLong())
        var num = BigInteger.ONE
        var den = BigInteger.ONE
        for (xj in ids){
            if (xj != xi){
                num = num.multiply(xj.negate().mod(FrostConstants.n))
                den = den.multiply(xi.subtract(xj).mod(FrostConstants.n))
            }
        }
        return num.multiply(den.modInverse(FrostConstants.n)).mod(FrostConstants.n)
    }

    suspend fun waitForEnoughPartials(): List<PartialSignature> {
        while (partials.size < threshold) {
            delay(100)
        }
        return partials
    }

    fun getCommitmentPair(peer: Peer): Pair<BigInteger, BigInteger> {
        val (d, e) = getNonce(peer)
        return Pair(d, e)
    }

    fun aggregateCommitments(): BigInteger =
        commitments.values.reduce { acc, r -> acc.multiply(r).mod(FrostConstants.p) }

    fun aggregateCommitmentsWithBindingFactors(): BigInteger {
        val Ris = participants.map { peer ->
            val (d, e) = getNonce(peer)
            val rho = bindingFactor(peer, currentMessage)
            // R_i = D_i * E_i^rho_i mod p
            d.multiply(e.modPow(rho, FrostConstants.p)).mod(FrostConstants.p)
        }
        return Ris.reduce { acc, Ri -> acc.multiply(Ri).mod(FrostConstants.p) }
    }

    fun verifyPartial(peer: Peer, partial: PartialSignature, c: BigInteger): Boolean {
        val (d, e) = getNonce(peer)
        val rho = bindingFactor(peer, currentMessage)
        val R_i = d.multiply(e.modPow(rho, FrostConstants.p)).mod(FrostConstants.p)
        val Y_i = verificationShares[peerId(peer)]
            ?: error("Missing verification share for ${peerId(peer)}")
        val lambda = lambda(peer)
        val exponent = c.multiply(lambda).mod(FrostConstants.n)
        val lhs = FrostConstants.g.modPow(partial.z, FrostConstants.p)
        val rhs = R_i.multiply(Y_i.modPow(exponent, FrostConstants.p)).mod(FrostConstants.p)

        return lhs == rhs
    }

    fun constructCommitmentSet(): List<CommitmentB> {
        return participants.mapIndexed { idx, peer ->
            val (d, e) = getNonce(peer)
            CommitmentB(index = idx + 1, D = d, E = e)
        }
    }

    fun addPartial(partial: PartialSignature) {
        partials.add(partial)
    }

    fun setShare(peer: Peer, share: BigInteger) {
        signingShares[peerId(peer)] = share
    }

    fun setCommitment(peer: Peer, r: BigInteger) {
        commitments[peerId(peer)] = r
    }

    private fun peerId(peer: Peer): String =
        Base64.getEncoder().encodeToString(peer.publicKey.keyToBin())
}

data class PartialSignature(val peerId: String, val z: BigInteger)


data class SignedMessage(val R: BigInteger, val z: BigInteger, val msg: ByteArray)

data class CommitmentB(val index: Int, val D: BigInteger, val E: BigInteger)


data class FrostSignInitMessage(
    val message: ByteArray,
    val commitments: Map<String, Pair<BigInteger, BigInteger>>
)
