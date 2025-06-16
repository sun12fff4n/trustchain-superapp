package nl.tudelft.trustchain.currencyii.util.frost.raft

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class RaftElectionMessage : Serializable {

    companion object {
        // Message type identifiers
        const val REQUEST_VOTE_ID = 125
        const val VOTE_RESPONSE_ID = 126
        const val HEARTBEAT_ID = 127

        // Unified Processing Entry
        fun deserialize(buffer: ByteArray): RaftElectionMessage {
            val byteBuffer = ByteBuffer.wrap(buffer)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val messageType = byteBuffer.getInt()

            return when (messageType) {
                REQUEST_VOTE_ID -> {
                    val (message, _) = RequestVote.deserialize(buffer, 4)
                    message
                }
                VOTE_RESPONSE_ID -> {
                    val (message, _) = VoteResponse.deserialize(buffer, 4)
                    message
                }
                HEARTBEAT_ID -> {
                    val (message, _) = Heartbeat.deserialize(buffer, 4)
                    message
                }
                else -> throw IllegalArgumentException("RaftElectionMessage: Unknown type - $messageType")
            }
        }

    }

    class RequestVote(
        val term: Int,
        val candidateId: String
    ) : RaftElectionMessage() {
        override fun serialize(): ByteArray {
            val candidateIdBytes = candidateId.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + 4 + candidateIdBytes.size)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(REQUEST_VOTE_ID) // Message type
            buffer.putInt(term)
            buffer.put(candidateIdBytes)
            return buffer.array()
        }

        companion object : Deserializable<RequestVote> {
            override fun deserialize(buffer: ByteArray, offset: Int): Pair<RequestVote, Int> {
                val byteBuffer = ByteBuffer.wrap(buffer, offset, buffer.size - offset)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val term = byteBuffer.getInt()
                val remaining = ByteArray(byteBuffer.remaining())
                byteBuffer.get(remaining)
                val candidateId = String(remaining, Charsets.UTF_8)

                // Calculate the total byte size consumed:
                // 4 bytes for term + candidateId size
                val consumed = 4 + remaining.size

                return Pair(RequestVote(term, candidateId), consumed)
            }
        }
    }

    class VoteResponse(
        val term: Int,
        val voteGranted: Boolean
    ) : RaftElectionMessage() {
        override fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(4 + 4 + 1)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(VOTE_RESPONSE_ID) // Message type
            buffer.putInt(term)
            buffer.put(if (voteGranted) 1.toByte() else 0.toByte())
            return buffer.array()
        }

        companion object : Deserializable<VoteResponse> {
            override fun deserialize(buffer: ByteArray, offset: Int): Pair<VoteResponse, Int> {
                val byteBuffer = ByteBuffer.wrap(buffer, offset, buffer.size - offset)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val term = byteBuffer.getInt()
                val voteGranted = byteBuffer.get() == 1.toByte()

                val consumed = 4 + 1

                return Pair(VoteResponse(term, voteGranted), consumed)
            }
        }
    }

    class Heartbeat(
        val term: Int,
        val leaderId: String
    ) : RaftElectionMessage() {
        override fun serialize(): ByteArray {
            val leaderIdBytes = leaderId.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + 4 + leaderIdBytes.size)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(HEARTBEAT_ID) // Message type
            buffer.putInt(term)
            buffer.put(leaderIdBytes)
            return buffer.array()
        }

        companion object : Deserializable<Heartbeat> {
            override fun deserialize(buffer: ByteArray, offset: Int): Pair<Heartbeat, Int> {
                val byteBuffer = ByteBuffer.wrap(buffer, offset, buffer.size - offset)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val term = byteBuffer.getInt()
                val remaining = ByteArray(byteBuffer.remaining())
                byteBuffer.get(remaining)
                val leaderId = String(remaining, Charsets.UTF_8)

                val consumed = 4 + remaining.size

                return Pair(Heartbeat(term, leaderId), consumed)
            }
        }
    }
}
