package nl.tudelft.trustchain.currencyii.util.frost

import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.serializeVarLen
import java.io.ByteArrayOutputStream
import java.util.*
import java.math.BigInteger

/**
 * Message types for FROST protocol communication
 */
enum class FrostMessageType {
    COMMITMENT,        // Round 1: Broadcast commitment and proof
    VERIFICATION_SHARE // Round 2: Broadcast verification share
}

/**
 * Base message class for FROST protocol communication
 */
class FrostMessage(
    val messageType: FrostMessageType,
    val sessionId: String,
    val data: ByteArray
) : Serializable {

    companion object {
        // Message ID for FrostMessage handling in IPv8
        const val ID = 1

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
    
    fun toFrostMessage(): FrostMessage {
        return FrostMessage(FrostMessageType.COMMITMENT, sessionId, this.serialize())
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
    
    fun toFrostMessage(): FrostMessage {
        return FrostMessage(FrostMessageType.VERIFICATION_SHARE, sessionId, this.serialize())
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