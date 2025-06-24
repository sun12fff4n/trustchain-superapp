package nl.tudelft.trustchain.currencyii.util.frost.raft

import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random

class MockRaftSendDelegate(override val myPeer: Peer) : RaftSendDelegate {
    val sentMessages = mutableListOf<Triple<Peer, Int, Serializable>>()
    override fun raftSend(peer: Peer, messageId: Int, payload: Serializable) {
        sentMessages.add(Triple(peer, messageId, payload))
    }
    fun clear() = sentMessages.clear()
}

class RaftElectionModuleTest {

    private lateinit var selfPeer: Peer
    private lateinit var mockDelegate: MockRaftSendDelegate
    private lateinit var raftModule: RaftElectionModule
    private lateinit var predictableRandom: Random

    @BeforeEach
    fun setUp() {
        selfPeer = Peer(AndroidCryptoProvider.generateKey())
        mockDelegate = MockRaftSendDelegate(selfPeer)
        // Use a fixed seed for predictable "randomness" in all tests
        predictableRandom = Random(12345L)
    }

    @Test
    fun `initial state should be Follower`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.testSetTestTimeVariables()
        raftModule.start()
        assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
        assertEquals(0, raftModule.getCurrentTerm())
        raftModule.stop()
    }

    @Test
    fun `testHandleRequestVote should grant vote if not voted yet`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.testSetTestTimeVariables()
        raftModule.start()
        val candidatePeer = Peer(AndroidCryptoProvider.generateKey())
        val request = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-1")

        raftModule.testHandleRequestVote(candidatePeer, request)

        assertEquals(1, mockDelegate.sentMessages.size)
        val (targetPeer, msgId, payload) = mockDelegate.sentMessages[0]
        assertEquals(candidatePeer, targetPeer)
        assertEquals(RaftElectionMessage.VOTE_RESPONSE_ID, msgId)
        assertTrue((payload as RaftElectionMessage.VoteResponse).voteGranted)
        assertEquals(1, raftModule.getCurrentTerm())
        raftModule.stop()
    }

    @Test
    fun `testHandleRequestVote should deny vote for older term`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.testSetTestTimeVariables()
        raftModule.start()

        raftModule.testHhandleHeartbeat(Peer(AndroidCryptoProvider.generateKey()), 2, "leader-1")
        assertEquals(2, raftModule.getCurrentTerm())
        mockDelegate.clear()


        val candidatePeer = Peer(AndroidCryptoProvider.generateKey())
        val request = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-2")
        raftModule.testHandleRequestVote(candidatePeer, request)

        assertEquals(1, mockDelegate.sentMessages.size)
        val (_, _, payload) = mockDelegate.sentMessages[0]
        assertFalse((payload as RaftElectionMessage.VoteResponse).voteGranted)
        assertEquals(2, raftModule.getCurrentTerm())
        raftModule.stop()
    }

    @Test
    fun `testHandleRequestVote should deny vote if already voted in same term`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.testSetTestTimeVariables()
        raftModule.start()
        val candidate1Peer = Peer(AndroidCryptoProvider.generateKey())
        val request1 = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-1")
        raftModule.testHandleRequestVote(candidate1Peer, request1) // 首次投票

        val candidate2Peer = Peer(AndroidCryptoProvider.generateKey())
        val request2 = RaftElectionMessage.RequestVote(term = 1, candidateId = "candidate-2")
        mockDelegate.clear()
        raftModule.testHandleRequestVote(candidate2Peer, request2)

        assertEquals(1, mockDelegate.sentMessages.size)
        val (_, _, payload) = mockDelegate.sentMessages[0]
        assertFalse((payload as RaftElectionMessage.VoteResponse).voteGranted)
        assertEquals(1, raftModule.getCurrentTerm())
        raftModule.stop()
    }

    @Test
    fun `testHhandleHeartbeat should update term and leader`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.testSetTestTimeVariables()
        raftModule.start()
        val leaderPeer = Peer(AndroidCryptoProvider.generateKey())
        raftModule.testHhandleHeartbeat(leaderPeer, 2, "leader-2")
        assertEquals(2, raftModule.getCurrentTerm())
        assertEquals(leaderPeer, raftModule.getCurrentLeader())
        assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
        raftModule.stop()
    }

    @Test
    fun `leader should send heartbeats to all peers`() {
        val peer1 = Peer(AndroidCryptoProvider.generateKey())
        val peer2 = Peer(AndroidCryptoProvider.generateKey())
        raftModule = RaftElectionModule(mockDelegate, setOf(peer1, peer2), "test-node-1", random = predictableRandom)
        raftModule.start()

        // Make sure the module is in LEADER state
        raftModule.testHandleRequestVote(peer1, RaftElectionMessage.RequestVote(1, "candidate-1"))
        raftModule.testHandleVoteResponse(peer2, 1, true)
        // Call the method to send heartbeats
        mockDelegate.clear()
        raftModule.testSetTestTimeVariables()

        // Invoke the method to send heartbeats
        val method = raftModule::class.java.getDeclaredMethod("sendHeartbeats")
        method.isAccessible = true
        method.invoke(raftModule)

        // Verify that heartbeats were sent to both peers
        assertEquals(2, mockDelegate.sentMessages.size)
        assertTrue(mockDelegate.sentMessages.all { it.second == RaftElectionMessage.HEARTBEAT_ID })
        val targets = mockDelegate.sentMessages.map { it.first }.toSet()
        assertTrue(targets.contains(peer1))
        assertTrue(targets.contains(peer2))

        raftModule.stop()
    }

    @Test
    fun `follower should update term and reset votedFor on higher term heartbeat`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.start()
        raftModule.testHandleRequestVote(Peer(AndroidCryptoProvider.generateKey()), RaftElectionMessage.RequestVote(1, "candidate-1"))
        assertEquals(1, raftModule.getCurrentTerm())

        val leaderPeer = Peer(AndroidCryptoProvider.generateKey())
        raftModule.testHhandleHeartbeat(leaderPeer, 2, "leader-2")
        assertEquals(2, raftModule.getCurrentTerm())
        // votedFor should be reset to null
        val votedForField = raftModule.javaClass.getDeclaredField("votedFor").apply { isAccessible = true }
        assertNull(votedForField.get(raftModule))
        raftModule.stop()
    }

    @Test
    fun `candidate should step down to follower on higher term heartbeat`() {
        val peer1 = Peer(AndroidCryptoProvider.generateKey())
        raftModule = RaftElectionModule(mockDelegate, setOf(peer1), "test-node-1", random = predictableRandom)
        raftModule.start()
        // Make node to become candidate
        raftModule.testHandleRequestVote(peer1, RaftElectionMessage.RequestVote(1, "candidate-1"))
        val leaderPeer = Peer(AndroidCryptoProvider.generateKey())
        raftModule.testHhandleHeartbeat(leaderPeer, 2, "leader-2")
        assertEquals(RaftElectionModule.NodeState.FOLLOWER, raftModule.getCurrentState())
        assertEquals(2, raftModule.getCurrentTerm())
        assertEquals(leaderPeer, raftModule.getCurrentLeader())
        raftModule.stop()
    }

    @Test
    fun `heartbeat with same term should not change term but reset timeout`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.start()
        val leaderPeer = Peer(AndroidCryptoProvider.generateKey())
        raftModule.testHhandleHeartbeat(leaderPeer, 1, "leader-1")
        val termBefore = raftModule.getCurrentTerm()
        val lastHeartbeatBefore = raftModule.getLastHeartbeatTime()
        Thread.sleep(10)
        raftModule.testHhandleHeartbeat(leaderPeer, 1, "leader-1")
        assertEquals(termBefore, raftModule.getCurrentTerm())
        assertTrue(raftModule.getLastHeartbeatTime() > lastHeartbeatBefore)
        raftModule.stop()
    }

    @Test
    fun `should only vote once per term`() {
        raftModule = RaftElectionModule(mockDelegate, emptySet(), "test-node-1", random = predictableRandom)
        raftModule.start()
        val candidate1 = Peer(AndroidCryptoProvider.generateKey())
        val candidate2 = Peer(AndroidCryptoProvider.generateKey())
        raftModule.testHandleRequestVote(candidate1, RaftElectionMessage.RequestVote(1, "candidate-1"))
        mockDelegate.clear()
        raftModule.testHandleRequestVote(candidate2, RaftElectionMessage.RequestVote(1, "candidate-2"))
        assertEquals(1, mockDelegate.sentMessages.size)
        val (_, _, payload) = mockDelegate.sentMessages[0]
        assertFalse((payload as RaftElectionMessage.VoteResponse).voteGranted)
        raftModule.stop()
    }

}
