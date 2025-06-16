package nl.tudelft.trustchain.currencyii.util.frost.raft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RaftElectionMessageTest {

    @Test
    fun testRequestVoteSerializeDeserialize() {
        // Create test data
        val term = 42
        val candidateId = "node123"
        val originalMessage = RaftElectionMessage.RequestVote(term, candidateId)

        // Serialize
        val serialized = originalMessage.serialize()

        // Directly deserialize (skipping the 4 bytes of type identifier)
        val (deserializedMessage, consumed) = RaftElectionMessage.RequestVote.deserialize(serialized, 4)

        // Verify
        assertEquals(term, deserializedMessage.term, "Term value should match")
        assertEquals(candidateId, deserializedMessage.candidateId, "CandidateId should match")

        // Verify the number of bytes consumed
        assertEquals(4 + candidateId.toByteArray(Charsets.UTF_8).size, consumed, "Number of bytes consumed should be correct")
    }

    @Test
    fun testRequestVoteGeneralDeserialization() {
        // Create test data
        val term = 42
        val candidateId = "node123"
        val originalMessage = RaftElectionMessage.RequestVote(term, candidateId)

        // Serialize
        val serialized = originalMessage.serialize()

        // General deserialization method
        val deserializedMessage = RaftElectionMessage.deserialize(serialized)

        // Verify type and values
        assertTrue(deserializedMessage is RaftElectionMessage.RequestVote)
        val requestVote = deserializedMessage as RaftElectionMessage.RequestVote
        assertEquals(term, requestVote.term)
        assertEquals(candidateId, requestVote.candidateId)
    }

    @Test
    fun testDeserializeWiresharkCapture() {
        // Wireshark captured hex data
        val hexString = "010000008c00000062346361353031392d373333382d343336622d383034392d363334663436613332393161"

        // Convert hex string to byte array
        val bytes = hexStringToByteArray(hexString)

        println("Original byte array length: ${bytes.size}")

        try {
            // Use deserialization method
            val message = RaftElectionMessage.deserialize(bytes)

            // Print message type
            println("Message type: ${message.javaClass.simpleName}")

            // Print detailed information based on message type
            when (message) {
                is RaftElectionMessage.RequestVote -> {
                    println("Request Vote message:")
                    println("- Term: ${message.term}")
                    println("- Candidate ID: ${message.candidateId}")
                }
                is RaftElectionMessage.VoteResponse -> {
                    println("Vote Response message:")
                    println("- Term: ${message.term}")
                    println("- Vote granted: ${message.voteGranted}")
                }
                is RaftElectionMessage.Heartbeat -> {
                    println("Heartbeat message:")
                    println("- Term: ${message.term}")
                    println("- Leader ID: ${message.leaderId}")
                }
            }

            // Verify results
            assertTrue(message is RaftElectionMessage.RequestVote)
            val requestVote = message as RaftElectionMessage.RequestVote
//            assertEquals(42, requestVote.term)
//            assertEquals("node123", requestVote.candidateId)
        } catch (e: Exception) {
            println("Parsing failed: ${e.message}")
            e.printStackTrace()
            throw e  // Rethrow exception to fail the test
        }
    }

    // Helper method: convert hex string to byte array
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val s = hexString.replace(" ", "").replace("\n", "")
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                            Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
