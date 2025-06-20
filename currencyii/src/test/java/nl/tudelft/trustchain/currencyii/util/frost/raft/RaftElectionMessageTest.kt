package nl.tudelft.trustchain.currencyii.util.frost.raft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RaftElectionMessageTest {

    @Test
    fun `RequestVote payload should serialize and deserialize correctly`() {
        // This test verifies the payload-only serialization and deserialization.
        val originalMessage = RaftElectionMessage.RequestVote(42, "node123")

        // Serialize the payload
        val payloadBytes = originalMessage.serialize()

        // Deserialize the payload starting from offset 0
        val (deserializedMessage, consumed) = RaftElectionMessage.RequestVote.deserialize(payloadBytes, 0)

        // Verify content
        assertEquals(originalMessage.term, deserializedMessage.term)
        assertEquals(originalMessage.candidateId, deserializedMessage.candidateId)

        // Verify consumed bytes matches payload size
        assertEquals(payloadBytes.size, consumed)
    }

    @Test
    fun `RequestVote full packet should deserialize correctly`() {
        // This test verifies the general deserialization for a full packet (ID + payload).
        val originalMessage = RaftElectionMessage.RequestVote(42, "node123")

        // Serialize the payload
        val payloadBytes = originalMessage.serialize()

        // Manually construct the full packet with message ID
        val buffer = ByteBuffer.allocate(4 + payloadBytes.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RaftElectionMessage.REQUEST_VOTE_ID)
        buffer.put(payloadBytes)
        val fullPacket = buffer.array()

        // Use the general deserialize method
        val deserializedMessage = RaftElectionMessage.deserialize(fullPacket)

        // Verify type and values
        assertTrue(deserializedMessage is RaftElectionMessage.RequestVote)
        val requestVote = deserializedMessage as RaftElectionMessage.RequestVote
        assertEquals(originalMessage.term, requestVote.term)
        assertEquals(originalMessage.candidateId, requestVote.candidateId)
    }

    @Test
    fun testVoteResponseSerializeDeserialize() {
        val originalMessage = RaftElectionMessage.VoteResponse(10, true)
        val serialized = originalMessage.serialize()

        // Simulate prepending the message ID for a full packet test
        val buffer = java.nio.ByteBuffer.allocate(4 + serialized.size)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RaftElectionMessage.VOTE_RESPONSE_ID)
        buffer.put(serialized)
        val fullPacket = buffer.array()

        val deserializedMessage = RaftElectionMessage.deserialize(fullPacket)

        assertTrue(deserializedMessage is RaftElectionMessage.VoteResponse)
        val voteResponse = deserializedMessage as RaftElectionMessage.VoteResponse
        assertEquals(10, voteResponse.term)
        assertEquals(true, voteResponse.voteGranted)
    }

    @Test
    fun testHeartbeatSerializeDeserialize() {
        val originalMessage = RaftElectionMessage.Heartbeat(12, "leader-abc")
        val serialized = originalMessage.serialize()

        // Simulate prepending the message ID
        val buffer = java.nio.ByteBuffer.allocate(4 + serialized.size)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RaftElectionMessage.HEARTBEAT_ID)
        buffer.put(serialized)
        val fullPacket = buffer.array()

        val deserializedMessage = RaftElectionMessage.deserialize(fullPacket)

        assertTrue(deserializedMessage is RaftElectionMessage.Heartbeat)
        val heartbeat = deserializedMessage as RaftElectionMessage.Heartbeat
        assertEquals(12, heartbeat.term)
        assertEquals("leader-abc", heartbeat.leaderId)
    }

    @Test
    fun `deserialize should throw exception for unknown message type`() {
        val unknownTypeId = 999
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(unknownTypeId)
        val packet = buffer.array()

        val exception = assertThrows<IllegalArgumentException> {
            RaftElectionMessage.deserialize(packet)
        }
        assertEquals("RaftElectionMessage: Unknown type - $unknownTypeId", exception.message)
    }

    @Test
    fun `deserialize should throw exception for truncated packet`() {
        // A packet that is too short to contain a full message ID
        val truncatedPacket = byteArrayOf(0x7D, 0x00) // 125 (REQUEST_VOTE_ID) is 7D 00 00 00 in little-endian

        assertThrows<java.nio.BufferUnderflowException> {
            RaftElectionMessage.deserialize(truncatedPacket)
        }
    }

    @Test
    fun `RequestVote deserialize should handle empty candidateId`() {
        val original = RaftElectionMessage.RequestVote(5, "")
        val serialized = original.serialize()

        // Prepend message ID for general deserialization
        val buffer = ByteBuffer.allocate(4 + serialized.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RaftElectionMessage.REQUEST_VOTE_ID)
        buffer.put(serialized)
        val packet = buffer.array()

        val deserialized = RaftElectionMessage.deserialize(packet) as RaftElectionMessage.RequestVote
        assertEquals(5, deserialized.term)
        assertEquals("", deserialized.candidateId)
    }
}
