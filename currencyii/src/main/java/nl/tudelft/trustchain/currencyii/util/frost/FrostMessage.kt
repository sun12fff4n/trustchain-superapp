package nl.tudelft.trustchain.currencyii.util.frost

import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.serializeVarLen
import java.io.ByteArrayOutputStream
import java.util.*

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
    sessionId: String,
    val commitment: List<Long>,
    val proof: Pair<Long, Long>
) : Serializable {
    
    companion object {
        fun deserialize(sessionId: String, buffer: ByteArray): FrostCommitmentMessage {
            // Read the number of commitment values
            val commitmentSize = buffer[0].toInt()
            
            // Read the commitment values
            val commitment = ArrayList<Long>()
            var offset = 1
            for (i in 0 until commitmentSize) {
                val longBytes = ByteArray(8)
                System.arraycopy(buffer, offset, longBytes, 0, 8)
                commitment.add(longBytes.toLong())
                offset += 8
            }
            
            // Read the proof (R, z)
            val rBytes = ByteArray(8)
            System.arraycopy(buffer, offset, rBytes, 0, 8)
            val r = rBytes.toLong()
            offset += 8
            
            val zBytes = ByteArray(8)
            System.arraycopy(buffer, offset, zBytes, 0, 8)
            val z = zBytes.toLong()
            
            return FrostCommitmentMessage(sessionId, commitment, Pair(r, z))
        }
    }
    
    override fun serialize(): ByteArray {
        val outputBuffer = ByteArrayOutputStream()
        
        // Write the number of commitment values
        outputBuffer.write(commitment.size)
        
        // Write the commitment values
        for (value in commitment) {
            outputBuffer.write(value.toByteArray())
        }
        
        // Write the proof (R, z)
        outputBuffer.write(proof.first.toByteArray())
        outputBuffer.write(proof.second.toByteArray())
        
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
    sessionId: String,
    val verificationShare: Long
) : Serializable {
    
    companion object {
        fun deserialize(sessionId: String, buffer: ByteArray): FrostVerificationShareMessage {
            // Read the verification share
            val verificationShareBytes = ByteArray(8)
            System.arraycopy(buffer, 0, verificationShareBytes, 0, 8)
            val verificationShare = verificationShareBytes.toLong()
            
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