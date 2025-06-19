package nl.tudelft.trustchain.currencyii.util.frost

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeVarLen
import nl.tudelft.ipv8.messaging.serializeVarLen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import java.math.BigInteger


val FrostMessageID = 310

/**
 * Message types for FROST protocol communication
 */
enum class FrostMessageType {
    COMMITMENT,        // Frost DKG Round 1: Broadcast commitment and proof
    VERIFICATION_SHARE, // Frost DKGRound 2: Broadcast verification share
    LEADER_BROADCAST,   // For broadcasting the leader of a shared wallet
    FROST_NONCEPAIRS_TO_SA, //Frost Preprocessing Round 2: Broadcast nonces to SA
    FROST_NONCEPAIR_TO_PARTICIPANT, //Frost Sigining Step1: Send next pair from SA to corresponding participant
    FROST_JOINPROPOSAL_TO_SA, //Frost Sigining Step2: Send join proposal to SA
    FROST_SIGNING_ZI_TO_SA, //Frost Sigining Step3: Send z_i to SA
    FROST_SIGINING_TO_JOINER, // Frost Sigining Step4: Send signature to joiner
}

class FrostPayload (
    val messageType: FrostMessageType,
    val sessionId: String,
    val data: ByteArray,
) : Serializable {
    override fun serialize(): ByteArray {
        return byteArrayOf(messageType.ordinal.toByte()) +
            serializeVarLen(sessionId.toByteArray(Charsets.UTF_8)) +
            serializeVarLen(data)
    }

    companion object Deserializer : Deserializable<FrostPayload> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<FrostPayload, Int> {
            var local_offset = 0
            val messageTypeOrinal = buffer[offset + local_offset].toInt()
            local_offset += 1
            var (sessionId, sessionIdSize) = deserializeVarLen(buffer, offset + local_offset)
            local_offset += sessionIdSize

            var (data, dataSize) = deserializeVarLen(buffer, offset + local_offset)
            local_offset += dataSize

            var payload = FrostPayload(
                FrostMessageType.values()[messageTypeOrinal],
                sessionId.toString(Charsets.UTF_8),
                data
            )
            return Pair(payload, local_offset)
        }

    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FrostPayload
        if (!this.messageType.equals(other.messageType)) return false
        if (!sessionId.equals(other.sessionId)) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }
}

class FrostMessage(
    val messageType: FrostMessageType,
    val sessionId: String,
    val data: ByteArray
) : Serializable {

    companion object {
        // Message ID for FrostMessage handling in IPv8
        const val ID = 310

        /**
         * Deserialize a FrostMessage from raw bytes
         */
        fun deserialize(buffer: ByteArray): Pair<FrostMessage, ByteArray> {
            val typeOrdinal = buffer[0].toInt()
            val messageType = FrostMessageType.values()[typeOrdinal]

            // Read session ID (as a UUID string)
            val sessionIdBytes = ByteArray(36) // UUID string format has 36 characters
            System.arraycopy(buffer, 1, sessionIdBytes, 0, 36)
            val sessionId = String(sessionIdBytes)
            // Get the data payload
            val dataSize = buffer.size - 37 // Total size minus type byte and session ID
            val data = ByteArray(dataSize)
            System.arraycopy(buffer, 37, data, 0, dataSize)

            return Pair(FrostMessage(messageType, sessionId, data), ByteArray(0))
        }
    }

    override fun serialize(): ByteArray {
        val outputBuffer = ByteArrayOutputStream()

        // Write message type
        outputBuffer.write(messageType.ordinal)

        // Write session ID (as a string)
        outputBuffer.write(sessionId.toByteArray())

        // Write data payload
        outputBuffer.write(data)

        return outputBuffer.toByteArray()
    }
}

/**
 * Message for Round 1: Contains commitment and proof
 */
class FrostCommitmentMessage(
    val sessionId: String,
    val commitment: List<BigInteger>,
    val proof: Pair<BigInteger, BigInteger>
) : Serializable {

    companion object {
        fun deserialize(sessionId: String, buffer: ByteArray): FrostCommitmentMessage {
            // Read the number of commitment values
            val commitmentSize = buffer[0].toInt()

            // Read the commitment values
            val commitment = ArrayList<BigInteger>()
            var offset = 1
            for (i in 0 until commitmentSize) {
                val len = buffer[offset].toInt()
                offset += 1
                val bytes = buffer.copyOfRange(offset, offset + len)
                commitment.add(BigInteger(bytes))
                offset += len
            }

            // Read the proof (R, z)
            val rLen = buffer[offset].toInt()
            offset += 1
            val r = BigInteger(buffer.copyOfRange(offset, offset + rLen))
            offset += rLen

            val zLen = buffer[offset].toInt()
            offset += 1
            val z = BigInteger(buffer.copyOfRange(offset, offset + zLen))

            return FrostCommitmentMessage(sessionId, commitment, Pair(r, z))
        }
    }

    override fun serialize(): ByteArray {
        val outputBuffer = ByteArrayOutputStream()

        // Write the number of commitment values
        outputBuffer.write(commitment.size)

        // Write the commitment values
        for (value in commitment) {
            val bytes = value.toByteArray()
            outputBuffer.write(bytes.size)         // Write length prefix (1 byte)
            outputBuffer.write(bytes)              // Then actual bytes
        }

        // Write the proof (R, z)
        val rBytes = proof.first.toByteArray()
        outputBuffer.write(rBytes.size)
        outputBuffer.write(rBytes)

        val zBytes = proof.second.toByteArray()
        outputBuffer.write(zBytes.size)
        outputBuffer.write(zBytes)

        return outputBuffer.toByteArray()
    }

//    fun toFrostMessage(): FrostMessage {
//        return FrostMessage(FrostMessageType.COMMITMENT, sessionId, this.serialize())
//    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.COMMITMENT, sessionId, this.serialize())
    }
}

/**
 * Message for Round 2: Contains verification share
 */
class FrostVerificationShareMessage(
    val sessionId: String,
    val verificationShare: BigInteger
) : Serializable {

    companion object {
         fun deserialize(sessionId: String, buffer: ByteArray): FrostVerificationShareMessage {
             // Read the verification share
             val verificationShareBytes = ByteArray(8)
             System.arraycopy(buffer, 0, verificationShareBytes, 0, 8)
             val verificationShare = BigInteger.valueOf(verificationShareBytes.toLong())

             return FrostVerificationShareMessage(sessionId, verificationShare)
         }
    }

    override fun serialize(): ByteArray {
        val outputBuffer = ByteArrayOutputStream()

        // Write the verification share
        outputBuffer.write(verificationShare.toByteArray())

        return outputBuffer.toByteArray()
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.VERIFICATION_SHARE, sessionId, this.serialize())
    }
}


/**
 * Message for broadcasting the leader of a shared wallet
 */
class FrostLeaderMessage(
    val walletId: String,
    val sessionId: String,
    val leaderId: String,
) : Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)
        val leaderBytes = leaderId.toByteArray(Charsets.UTF_8)

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(leaderBytes.size)
        dos.write(leaderBytes)

        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostLeaderMessage {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val leaderLen = dis.readInt()
            val leaderBytes = ByteArray(leaderLen)
            dis.readFully(leaderBytes)

            val walletId = String(walletBytes, Charsets.UTF_8)
            val sessionId = String(sessionBytes, Charsets.UTF_8)
            val leaderId = String(leaderBytes, Charsets.UTF_8)

            return FrostLeaderMessage(walletId, sessionId, leaderId)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.LEADER_BROADCAST, sessionId, this.serialize())
    }
}

/**
 * Message for broadcasting the leader of a shared wallet
 */
class FrostNoncesToSAMessage(
    val walletId: String,
    val sessionId: String,
    val leaderId: String,
    val noncePairs: List<Pair<BigInteger, BigInteger>>,
) : Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)
        val leaderBytes = leaderId.toByteArray(Charsets.UTF_8)

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(leaderBytes.size)
        dos.write(leaderBytes)

        dos.writeInt(noncePairs.size)
        for ((d, e) in noncePairs) {
            val dBytes = d.toByteArray()
            dos.writeInt(dBytes.size)
            dos.write(dBytes)

            val eBytes = e.toByteArray()
            dos.writeInt(eBytes.size)
            dos.write(eBytes)
        }

        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostNoncesToSAMessage {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val leaderLen = dis.readInt()
            val leaderBytes = ByteArray(leaderLen)
            dis.readFully(leaderBytes)

            val walletId = walletBytes.toString(Charsets.UTF_8)
            val sessionId = sessionBytes.toString(Charsets.UTF_8)
            val leaderId = leaderBytes.toString(Charsets.UTF_8)

            // read noncePairs length
            val listSize = dis.readInt()
            val noncePairs = mutableListOf<Pair<BigInteger, BigInteger>>()
            repeat(listSize) {
                // read d
                val dLen = dis.readInt()
                val dBytes = ByteArray(dLen)
                dis.readFully(dBytes)
                val d = BigInteger(1, dBytes)

                // read e
                val eLen = dis.readInt()
                val eBytes = ByteArray(eLen)
                dis.readFully(eBytes)
                val e = BigInteger(1, eBytes)

                noncePairs += Pair(d, e)
            }

            return FrostNoncesToSAMessage(walletId, sessionId, leaderId, noncePairs)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.FROST_NONCEPAIRS_TO_SA, sessionId, serialize())
    }
}



// Helper extension to convert Long to ByteArray
private fun Long.toByteArray(): ByteArray {
    val result = ByteArray(8)
    for (i in 0 until 8) {
        result[i] = ((this shr (i * 8)) and 0xFF).toByte()
    }
    return result
}

// Helper extension to convert ByteArray to Long
private fun ByteArray.toLong(): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((this[i].toLong() and 0xFF) shl (i * 8))
    }
    return result
}

/**
 * Message for the leader to send next nonce pair when signining a proposal to corresponding participant.
 */
class FrostNonceToParticipantMessage(
    val walletId: String,
    val sessionId: String,
    val joinerId: String,
    val noncePairs: MutableMap<String, Pair<BigInteger, BigInteger>>,
) : Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)
        val joinerBytes = joinerId.toByteArray(Charsets.UTF_8)

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(joinerBytes.size)
        dos.write(joinerBytes)


        // write noncePairs length
        dos.writeInt(noncePairs.size)

        for ((peerId, noncePair) in noncePairs) {
            // write peerId
            val peerIdBytes = peerId.toByteArray(Charsets.UTF_8)
            dos.writeInt(peerIdBytes.size)
            dos.write(peerIdBytes)

            // write noncePair
            val dBytes = noncePair.first.toByteArray()
            dos.writeInt(dBytes.size)
            dos.write(dBytes)
            val eBytes = noncePair.second.toByteArray()
            dos.writeInt(eBytes.size)
            dos.write(eBytes)
        }
        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostNonceToParticipantMessage {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val joinerLen = dis.readInt()
            val joinerBytes = ByteArray(joinerLen)
            dis.readFully(joinerBytes)

            val walletId = walletBytes.toString(Charsets.UTF_8)
            val sessionId = sessionBytes.toString(Charsets.UTF_8)
            val joinerId = joinerBytes.toString(Charsets.UTF_8)

            // read noncePairs length
            val listSize = dis.readInt()

            // read noncePairs
            val noncePairs = mutableMapOf<String, Pair<BigInteger, BigInteger>>()
            repeat(listSize) {
                // read peer Id
                val peerLength = dis.readInt()
                val peerBytes = ByteArray(peerLength)
                dis.readFully(peerBytes)
                val peerId = peerBytes.toString(Charsets.UTF_8)

                // read d
                val dLen = dis.readInt()
                val dBytes = ByteArray(dLen)
                dis.readFully(dBytes)
                val d = BigInteger(1, dBytes)

                // read e
                val eLen = dis.readInt()
                val eBytes = ByteArray(eLen)
                dis.readFully(eBytes)
                val e = BigInteger(1, eBytes)

                noncePairs[peerId] = Pair(d, e)
            }
            return FrostNonceToParticipantMessage(walletId, sessionId, joinerId, noncePairs)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.FROST_NONCEPAIR_TO_PARTICIPANT, sessionId, serialize())
    }
}



class FrostJoinProposalToSA(
    val walletId: String,
    val sessionId: String,
    val peerId: String,
) : Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)
        val peerBytes = peerId.toByteArray(Charsets.UTF_8)

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(peerBytes.size)
        dos.write(peerBytes)

        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostJoinProposalToSA {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val peerLen = dis.readInt()
            val peerBytes = ByteArray(peerLen)
            dis.readFully(peerBytes)

            val walletId = walletBytes.toString(Charsets.UTF_8)
            val sessionId = sessionBytes.toString(Charsets.UTF_8)
            val peerId = peerBytes.toString(Charsets.UTF_8)

            return FrostJoinProposalToSA(walletId, sessionId, peerId)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.FROST_JOINPROPOSAL_TO_SA, sessionId, serialize())
    }
}

class FrostSigningResponseToSAMessage(
    val walletId: String,
    val sessionId: String,
    val participantIndex: Int,
    val z_i: BigInteger,
): Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)

        val z_iBytes = z_i.toByteArray()

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(participantIndex)
        dos.writeInt(z_iBytes.size)
        dos.write(z_iBytes)

        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostSigningResponseToSAMessage {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val participantIndex = dis.readInt()

            val z_iLen = dis.readInt()
            val z_iBytes = ByteArray(z_iLen)
            dis.readFully(z_iBytes)

            val walletId = walletBytes.toString(Charsets.UTF_8)
            val sessionId = sessionBytes.toString(Charsets.UTF_8)
            val z_i = BigInteger(1, z_iBytes)

            return FrostSigningResponseToSAMessage(walletId, sessionId, participantIndex, z_i)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.FROST_SIGNING_ZI_TO_SA, sessionId, serialize())
    }
}



class FrostSigningResponseToJoinerMessage(
    val walletId: String,
    val sessionId: String,
    val aggregateSignature: BigInteger,
    val joinerId: String,
): Serializable {

    override fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val walletBytes = walletId.toByteArray(Charsets.UTF_8)
        val sessionBytes = sessionId.toByteArray(Charsets.UTF_8)
        val signatureBytes = aggregateSignature.toByteArray()
        val joinerBytes = joinerId.toByteArray(Charsets.UTF_8)

        dos.writeInt(walletBytes.size)
        dos.write(walletBytes)
        dos.writeInt(sessionBytes.size)
        dos.write(sessionBytes)
        dos.writeInt(signatureBytes.size)
        dos.write(signatureBytes)
        dos.writeInt(joinerBytes.size)
        dos.write(joinerBytes)

        return baos.toByteArray()
    }

    companion object {
        fun deserialize(buffer: ByteArray): FrostSigningResponseToJoinerMessage {
            val bais = ByteArrayInputStream(buffer)
            val dis = DataInputStream(bais)

            val walletLen = dis.readInt()
            val walletBytes = ByteArray(walletLen)
            dis.readFully(walletBytes)

            val sessionLen = dis.readInt()
            val sessionBytes = ByteArray(sessionLen)
            dis.readFully(sessionBytes)

            val z_iLen = dis.readInt()
            val z_iBytes = ByteArray(z_iLen)
            dis.readFully(z_iBytes)

            val signatureLen = dis.readInt()
            val signatureBytes = ByteArray(signatureLen)
            dis.readFully(signatureBytes)

            val joinerLen = dis.readInt()
            val joinerBytes = ByteArray(joinerLen)
            dis.readFully(joinerBytes)

            val walletId = walletBytes.toString(Charsets.UTF_8)
            val sessionId = sessionBytes.toString(Charsets.UTF_8)
            val aggregateSignature = BigInteger(1, signatureBytes)
            val joinerId = joinerBytes.toString(Charsets.UTF_8)

            return FrostSigningResponseToJoinerMessage(walletId, sessionId, aggregateSignature, joinerId)
        }
    }

    fun toFrostPayload(): FrostPayload {
        return FrostPayload(FrostMessageType.FROST_SIGNING_ZI_TO_SA, sessionId, serialize())
    }
}
