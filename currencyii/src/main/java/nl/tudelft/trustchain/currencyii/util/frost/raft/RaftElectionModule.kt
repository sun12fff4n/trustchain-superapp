package nl.tudelft.trustchain.currencyii.util.frost.raft

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import java.util.Random
import java.util.UUID

class RaftElectionModule(
    private val community: RaftSendDelegate,
    private val nodeId: String = UUID.randomUUID().toString(),
    externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val random: Random = Random()
) {
    companion object {
        private const val TAG = "RaftElectionModule"
    }

    // Create a dedicated scope for this module that can be cancelled without affecting the externalScope.
    private val moduleScope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())

    enum class NodeState {
        FOLLOWER, CANDIDATE, LEADER
    }

    private var currentState = NodeState.FOLLOWER
    private var currentTerm  = 0
    private var votedFor: String? = null
    private var currentLeader: Peer? = null

    private var electionTimeOut: Job? = null
    private var minElectionTimeoutMs = 800L
    private var maxElectionTimeoutMs = 1500L
    private var heartbeatIntervalMs = 300L
    private var heartbeatJob: Job? = null
    private var lastHeartbeatTime: Long = 0

    private var currentVotes = 0
    private val peers = mutableSetOf<Peer>()

    private var onLeaderChangedCallback: ((Peer?) -> Unit)? = null

    fun onLeaderChanged(callback: (Peer?) -> Unit) {
        onLeaderChangedCallback = callback
    }

    /**
     * Control Raft Algorithm Start / Stop
     * 
     */
    fun start() {
        Log.d(TAG, "Starting Raft election module with node ID: ${getSelfNodeIdDisplay()}")
        becomeFollower(currentTerm)
    }

    fun stop() {
        // Cancel the dedicated scope for this module.
        // This will cancel all coroutines launched within it (electionTimeOut, heartbeatJob).
        moduleScope.cancel()
        Log.d(TAG, "Stopped Raft election module with node ID: ${getSelfNodeIdDisplay()}")
    }

    /**
     * Election Timeout and Election Start
     * 
     */
    private fun restartElectionTimeout() {
        electionTimeOut?.cancel()
        electionTimeOut = moduleScope.launch {
            val timeout = minElectionTimeoutMs + Math.abs(random.nextLong()) % (maxElectionTimeoutMs - minElectionTimeoutMs)
            delay(timeout)
            startElection()
        }
    }

    private fun startElection() {
        synchronized(this) {
            currentTerm++
            currentState = NodeState.CANDIDATE
            votedFor = nodeId
            currentVotes = 1 // vote for itself

            Log.d(TAG, "${getSelfNodeIdDisplay()}: Starting election for term $currentTerm")

            restartElectionTimeout()

            // Avoid ConcurrentModificationException
            val peersCopy = peers.toSet()

            peersCopy.forEach { peer ->
                sendRequestVoteMessage(peer)
            }
        }
    }

    /**
     * Getters for Raft State
     * Helper methods
     */
    fun getLastHeartbeatTime(): Long = lastHeartbeatTime

    fun isLeader(): Boolean = currentState == NodeState.LEADER

    fun getCurrentLeader(): Peer? = currentLeader

    fun getCurrentState(): NodeState = currentState

    fun forceNewElection() {
        Log.d(TAG, "${getSelfNodeIdDisplay()}: A new election is being forced.")
        startElection()
    }

    /**
     * State Changes
     * 
     */
    private fun becomeFollower(term: Int){
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Becoming follower for term $term")
        currentState = NodeState.FOLLOWER
        currentTerm = term
        votedFor = null

        restartElectionTimeout()
    }

    private fun becomeLeader() {
        synchronized(this) {
            if(currentState == NodeState.LEADER)    return

            Log.d(TAG, "${getSelfNodeIdDisplay()}: Becoming leader for term $currentTerm")
            currentState = NodeState.LEADER
            currentLeader = community.myPeer
            votedFor = null

            electionTimeOut?.cancel()

            onLeaderChangedCallback?.invoke(community.myPeer)

            startHeartbeat()
        }
    }


    /**
     * Raft Election Message Handling
     * 
     */
    // RequestVote Sender
    private fun sendRequestVoteMessage(peer: Peer) {
        // Use Community to send message
        val message = RaftElectionMessage.RequestVote(currentTerm, nodeId)
        community.raftSend(peer, RaftElectionMessage.REQUEST_VOTE_ID, message)
        Log.d(TAG, "Node ${getSelfNodeIdDisplay()} Sent RequestVote to ${getNodeIdDisplay(peer)} for term $currentTerm")
    }

    // RequestVote Handler and React Responses
    fun handleRequestVote(peer: Peer, message: RaftElectionMessage.RequestVote) {
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Handling request vote from ${getNodeIdDisplay(peer)} for term ${message.term}, candidateId=${message.candidateId}")

        val voteGranted = synchronized(this) {
            if (message.term < currentTerm) {
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for ${message.candidateId}: term ${message.term} < currentTerm $currentTerm")
                return@synchronized false
            }

            if (message.term > currentTerm) {
                becomeFollower(message.term)
            }

            // now, term == currentTerm
            if (votedFor == null || votedFor == message.candidateId) {
                votedFor = message.candidateId
                restartElectionTimeout()
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Granted vote to ${message.candidateId} for term ${message.term}")
                return@synchronized true
            }

            Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for ${message.candidateId}: already voted for $votedFor")
            return@synchronized false
        }

        // Create and send the response directly from the module
        val response = RaftElectionMessage.VoteResponse(
            getCurrentTerm(),
            voteGranted
        )
        community.raftSend(peer, RaftElectionMessage.VOTE_RESPONSE_ID, response)
    }

    // VoteResponse Handler
    fun handleVoteResponse(peer: Peer, term: Int, voteGranted: Boolean) {
        synchronized(this) {
            if(currentState != NodeState.CANDIDATE || term < currentTerm) {
                // outdated
                return
            }

            if(term > currentTerm) {
                becomeFollower(term)
                return
            }

            // term == currentTerm
            if(voteGranted) {
                currentVotes ++
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Received vote from ${getNodeIdDisplay(peer)}, total votes: $currentVotes")

                if(currentVotes > (peers.size + 1) / 2) {
                    becomeLeader()
                }
            }
        }
    }

    /**
     * Heartbeat Mechanism
     * Heartbeat Message Sender and Receiver
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()

        heartbeatJob = moduleScope.launch {
            while(isActive && currentState == NodeState.LEADER) {
                sendHeartbeats()
                delay(heartbeatIntervalMs)
            }
        }
    }

    private fun sendHeartbeats() {
        val peersCopy = peers.toSet()

        peersCopy.forEach { peer ->
            sendHeartbeatMessage(peer)
        }
    }

    // Heartbeat Receiver
    /**
     * Handles an incoming heartbeat from a peer claiming to be the leader.
     */
    fun handleHeartbeat(from: Peer, term: Int, leaderId: String) {
        lastHeartbeatTime = System.currentTimeMillis()
        Log.d(TAG, "[$nodeId] Received heartbeat from ${from.mid.substring(0, 5)} for term $term")

        if (term >= currentTerm) {
            val oldLeader = currentLeader
            val wasNotFollower = currentState != NodeState.FOLLOWER

            if (term > currentTerm) {
                Log.d(TAG, "[$nodeId] Heartbeat from new leader, updating term to $term")
                currentTerm = term
                votedFor = null
            }

            if (wasNotFollower) {
                Log.d(TAG, "[$nodeId] Reverting to follower state.")
                currentState = NodeState.FOLLOWER
                heartbeatJob?.cancel()
            }

            currentLeader = from

            // Only invoke the callback if the leader has actually changed or the node just became a follower.
            if (currentLeader != oldLeader || wasNotFollower) {
                Log.d(TAG, "[$nodeId] New leader: ${currentLeader?.mid?.substring(0, 8)} for term $currentTerm")
                onLeaderChangedCallback?.invoke(currentLeader)
            }

            // CRITICAL: Reset the election timeout to prevent this node from starting a new election.
            restartElectionTimeout()
        } else {
            Log.w(TAG, "[$nodeId] Ignored heartbeat from old term $term (current is $currentTerm)")
        }
    }

    private fun sendHeartbeatMessage(peer: Peer) {
        val message = RaftElectionMessage.Heartbeat(currentTerm, nodeId)
        community.raftSend(peer, RaftElectionMessage.HEARTBEAT_ID, message)
        lastHeartbeatTime = System.currentTimeMillis()
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Send heartbeat to ${getNodeIdDisplay(peer)}, term=$currentTerm")
    }


    /**
     * Peer Management
     * 
     */
    fun addPeer(peer: Peer) {
        peers.add(peer)
    }

    fun removePeer(peer: Peer) {
        peers.remove(peer)
    }

    fun getPeers(): Set<Peer> {
        return peers.toSet()
    }

    fun getCurrentTerm(): Int {
        return currentTerm
    }

    /**
     * Logger Helper Methods
     * 
     */
    private fun getNodeIdDisplay(peer: Peer): String {
        return if (peer == community.myPeer) {
            "[Local]${nodeId.substring(0, 5)}"
        } else {
            peer.mid.substring(0, 5)
        }
    }

    private fun getSelfNodeIdDisplay(): String {
        return "[Local]${nodeId.substring(0, 5)}"
    }
}
