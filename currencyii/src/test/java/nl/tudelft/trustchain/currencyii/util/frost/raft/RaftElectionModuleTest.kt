package nl.tudelft.trustchain.currencyii.util.frost.raft

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random

// A mock delegate to capture sent messages for verification
class MockRaftSendDelegate(override val myPeer: Peer) : RaftSendDelegate {
    val sentMessages = mutableListOf<Triple<Peer, Int, Serializable>>()

    override fun raftSend(peer: Peer, messageId: Int, payload: Serializable) {
        sentMessages.add(Triple(peer, messageId, payload))
    }

    fun clear() {
        sentMessages.clear()
    }
}

class RaftElectionModuleTest {

    private lateinit var selfPeer: Peer
    private lateinit var mockDelegate: MockRaftSendDelegate
    private lateinit var raftModule: RaftElectionModule
    private lateinit var predictableRandom: Random // <- Add this

    @BeforeEach
    fun setUp() {
        selfPeer = Peer(AndroidCryptoProvider.generateKey())
        mockDelegate = MockRaftSendDelegate(selfPeer)
        // Use a fixed seed for predictable "randomness" in all tests
        predictableRandom = Random(12345L)
    }

    @Test
    fun `initial state should be Follower`() {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", random = predictableRandom)
        assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
        assertEquals(0, raftModule.getCurrentTerm())
    }

    @Test
    fun `handleRequestVote should grant vote if not voted yet`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {

            val candidatePeer = Peer(AndroidCryptoProvider.generateKey())
            val request = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-1")

            raftModule.handleRequestVote(candidatePeer, request)

            // Verify that a vote response was sent back
            assertEquals(1, mockDelegate.sentMessages.size)
            val (targetPeer, msgId, payload) = mockDelegate.sentMessages[0]
            assertEquals(candidatePeer, targetPeer)
            assertEquals(RaftElectionMessage.VOTE_RESPONSE_ID, msgId)
            assertTrue((payload as RaftElectionMessage.VoteResponse).voteGranted)
            assertEquals(1, raftModule.getCurrentTerm()) // Term should be updated
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `handleRequestVote should deny vote for older term`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)

        try {
            // First, advance the term of the module
            raftModule.handleHeartbeat(Peer(AndroidCryptoProvider.generateKey()), 2, "leader-1")
            assertEquals(2, raftModule.getCurrentTerm())
            mockDelegate.clear()

            // Now, receive a vote request for an older term
            val candidatePeer = Peer(AndroidCryptoProvider.generateKey())
            val request = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-2")
            raftModule.handleRequestVote(candidatePeer, request)

            // Verify that a negative vote response was sent
            assertEquals(1, mockDelegate.sentMessages.size)
            val (_, _, payload) = mockDelegate.sentMessages[0]
            assertFalse((payload as RaftElectionMessage.VoteResponse).voteGranted)
            assertEquals(2, raftModule.getCurrentTerm()) // Term should not change
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `handleRequestVote should deny vote if already voted in same term`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)

        try {
            val candidate1Peer = Peer(AndroidCryptoProvider.generateKey())
            val request1 = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-1")
            raftModule.handleRequestVote(candidate1Peer, request1) // First vote is granted

            // A second candidate requests a vote in the same term
            val candidate2Peer = Peer(AndroidCryptoProvider.generateKey())
            val request2 = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-2")
            mockDelegate.clear() // Clear messages from the first vote
            raftModule.handleRequestVote(candidate2Peer, request2)

            // Verify that the second vote was denied
            assertEquals(1, mockDelegate.sentMessages.size)
            val (_, _, payload) = mockDelegate.sentMessages[0]
            assertFalse((payload as RaftElectionMessage.VoteResponse).voteGranted)
            assertEquals(1, raftModule.getCurrentTerm()) // Term should remain the same
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `follower should become candidate on election timeout`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            // Add a peer to send vote requests to
            val otherPeer = Peer(AndroidCryptoProvider.generateKey())
            raftModule.addPeer(otherPeer)
            raftModule.start()

            // Advance time past the maximum election timeout
            advanceTimeBy(1600)

            // Verify state transition
            assertEquals(RaftElectionModule.NodeState.CANDIDATE, raftModule.getCurrentState())
            assertEquals(1, raftModule.getCurrentTerm()) // Term is incremented

            // Verify that it voted for itself and sent a RequestVote message
            assertEquals(1, mockDelegate.sentMessages.size)
            val (targetPeer, msgId, payload) = mockDelegate.sentMessages[0]
            assertEquals(otherPeer, targetPeer)
            assertEquals(RaftElectionMessage.REQUEST_VOTE_ID, msgId)
            assertTrue(payload is RaftElectionMessage.RequestVote)
            assertEquals(1, (payload as RaftElectionMessage.RequestVote).term)
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `candidate should become leader after receiving majority votes`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            val peer1 = Peer(AndroidCryptoProvider.generateKey())
            val peer2 = Peer(AndroidCryptoProvider.generateKey())
            // Total 3 nodes in the cluster (self + 2 peers). Majority is 2.
            raftModule.addPeer(peer1)
            raftModule.addPeer(peer2)
            raftModule.start()

            // Trigger an election
            advanceTimeBy(1600)
            assertEquals(RaftElectionModule.NodeState.CANDIDATE, raftModule.getCurrentState())
            // The node already voted for itself, so it has 1 vote. It needs one more.

            // Simulate receiving a positive vote from peer1
            raftModule.handleVoteResponse(peer1, 1, true)

            // Verify state transition to Leader
            assertEquals(RaftElectionModule.NodeState.LEADER, raftModule.getCurrentState())
            assertTrue(raftModule.isLeader())
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `leader should step down if it discovers a higher term`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            // Force the module to become a leader in term 1
            raftModule.start()
            advanceTimeBy(1600) // Become candidate in term 1
            raftModule.handleVoteResponse(Peer(AndroidCryptoProvider.generateKey()), 1, true) // Win election
            assertEquals(RaftElectionModule.NodeState.LEADER, raftModule.getCurrentState())
            assertEquals(1, raftModule.getCurrentTerm())

            // Now, a heartbeat arrives from a new leader in a higher term
            val newLeaderPeer = Peer(AndroidCryptoProvider.generateKey())
            raftModule.handleHeartbeat(newLeaderPeer, 2, "new-leader")

            // Verify that the node stepped down to follower
            assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
            assertEquals(2, raftModule.getCurrentTerm()) // Term is updated
            assertEquals(newLeaderPeer, raftModule.getCurrentLeader())
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `candidate should restart election on timeout (split vote)`() = runTest {
        // Pass the predictable random instance to the constructor
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            // Cluster of 3 nodes, majority is 2.
            val peer1 = Peer(AndroidCryptoProvider.generateKey())
            val peer2 = Peer(AndroidCryptoProvider.generateKey())
            raftModule.addPeer(peer1)
            raftModule.addPeer(peer2)
            raftModule.start()

            // 1. Election timeout, become candidate for term 1
            advanceTimeBy(1600)
            assertEquals(RaftElectionModule.NodeState.CANDIDATE, raftModule.getCurrentState())
            assertEquals(1, raftModule.getCurrentTerm())
            // It sent 2 vote requests for term 1
            assertEquals(2, mockDelegate.sentMessages.size)
            mockDelegate.clear()

            // 2. No majority vote received, election times out again
            advanceTimeBy(1600)

            // 3. Should start a new election for term 2
            assertEquals(RaftElectionModule.NodeState.CANDIDATE, raftModule.getCurrentState())
            assertEquals(2, raftModule.getCurrentTerm()) // Term increments
            assertEquals(2, mockDelegate.sentMessages.size) // Sends new vote requests
            val (_, _, payload) = mockDelegate.sentMessages[0]
            assertEquals(2, (payload as RaftElectionMessage.RequestVote).term)
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `candidate should step down if a valid leader emerges`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            raftModule.addPeer(Peer(AndroidCryptoProvider.generateKey()))
            raftModule.start()

            // Become a candidate for term 1
            advanceTimeBy(1600)
            assertEquals(RaftElectionModule.NodeState.CANDIDATE, raftModule.getCurrentState())
            assertEquals(1, raftModule.getCurrentTerm())

            // A new leader emerges in the same term and sends a heartbeat
            val newLeaderPeer = Peer(AndroidCryptoProvider.generateKey())
            raftModule.handleHeartbeat(newLeaderPeer, 1, "new-leader")

            // Should step down and become a follower
            assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
            assertEquals(newLeaderPeer, raftModule.getCurrentLeader())
        } finally {
            raftModule.stop()
        }
    }

    @Test
    fun `leader should send periodic heartbeats`() = runTest {
        raftModule = RaftElectionModule(mockDelegate, "test-node-1", this, predictableRandom)
        try {
            val peer1 = Peer(AndroidCryptoProvider.generateKey())
            val peer2 = Peer(AndroidCryptoProvider.generateKey())
            raftModule.addPeer(peer1)
            raftModule.addPeer(peer2)
            raftModule.start()

            // Become leader for term 1
            advanceTimeBy(1600)
            raftModule.handleVoteResponse(peer1, 1, true)
            assertEquals(RaftElectionModule.NodeState.LEADER, raftModule.getCurrentState())
            mockDelegate.clear()

            // Advance time by heartbeat interval
            advanceTimeBy(350) // Heartbeat interval is 300ms

            // Verify heartbeats were sent to both peers
            assertEquals(4, mockDelegate.sentMessages.size)
            assertTrue(mockDelegate.sentMessages.all { it.second == RaftElectionMessage.HEARTBEAT_ID })

            // Advance time again
            advanceTimeBy(350)
            assertEquals(6, mockDelegate.sentMessages.size) // Should have sent 2 more
        } finally {
            raftModule.stop()
        }
    }
}
