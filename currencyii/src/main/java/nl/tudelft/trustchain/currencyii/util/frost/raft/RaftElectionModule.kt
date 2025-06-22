package nl.tudelft.trustchain.currencyii.util.frost.raft

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import java.util.Random
import java.util.UUID

// Define all possible events that can change the module's state.
private sealed class RaftEvent {
    data class VoteRequested(val peer: Peer, val message: RaftElectionMessage.RequestVote) : RaftEvent()
    data class VoteResponded(val peer: Peer, val term: Int, val voteGranted: Boolean) : RaftEvent()
    data class HeartbeatReceived(val from: Peer, val term: Int, val leaderId: String) : RaftEvent()
    object ElectionTimeout : RaftEvent()
    object ForceElection: RaftEvent()
}

class RaftElectionModule(
    private val community: RaftSendDelegate,
    initialPeers: Set<Peer>,
    private val nodeId: String = UUID.randomUUID().toString(),
    private val parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val random: Random = Random()
) {
    companion object {
        private const val TAG = "RaftElectionModule"
    }

    private lateinit var moduleScope: CoroutineScope

    // Create a Channel to act as an event queue.
    private val eventChannel = Channel<RaftEvent>(Channel.UNLIMITED)

    enum class NodeState {
        FOLLOWER, CANDIDATE, LEADER
    }

    private var currentState = NodeState.FOLLOWER
    private var currentTerm  = 0
    private var votedFor: String? = null
    private var currentLeader: Peer? = null

    private var electionTimeOut: Job? = null
    private var minElectionTimeoutMs = 4000L
    private var maxElectionTimeoutMs = 8000L
    private var heartbeatIntervalMs = 1000L
    private var heartbeatJob: Job? = null
    private var lastHeartbeatTime: Long = 0

    private var currentVotes = 0
    private val raftPeers: Set<Peer> = initialPeers

    private var onLeaderChangedCallback: ((Peer?) -> Unit)? = null

    fun onLeaderChanged(callback: (Peer?) -> Unit) {
        onLeaderChangedCallback = callback
    }

    fun start() {
        // Prevent starting if already running
        if (this::moduleScope.isInitialized && moduleScope.isActive) {
            Log.w(TAG, "Module ${getSelfNodeIdDisplay()} is already running.")
            return
        }
        // Create a new scope every time we start. This makes the module restartable.
        moduleScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
        Log.d(TAG, "Starting Raft election module with node ID: ${getSelfNodeIdDisplay()}")
        // Launch the single event processing loop.
        runEventLoop()
        becomeFollower(currentTerm)
    }

    fun stop() {
        // Check if the scope has been initialized and is active before cancelling.
        if (this::moduleScope.isInitialized && moduleScope.isActive) {
            moduleScope.cancel()
            Log.d(TAG, "Stopped Raft election module with node ID: ${getSelfNodeIdDisplay()}")
        }
    }

    // The core of the Actor model: a single coroutine that processes all events sequentially.
    private fun runEventLoop() {
        moduleScope.launch {
            for (event in eventChannel) {
                when (event) {
                    is RaftEvent.VoteRequested -> processRequestVote(event.peer, event.message)
                    is RaftEvent.VoteResponded -> processVoteResponse(event.peer, event.term, event.voteGranted)
                    is RaftEvent.HeartbeatReceived -> processHeartbeat(event.from, event.term, event.leaderId)
                    is RaftEvent.ElectionTimeout -> startElection()
                    is RaftEvent.ForceElection -> startElection()
                }
            }
        }
    }

    private fun restartElectionTimeout() {
        electionTimeOut?.cancel()
        electionTimeOut = moduleScope.launch {
            val timeout = minElectionTimeoutMs + Math.abs(random.nextLong()) % (maxElectionTimeoutMs - minElectionTimeoutMs)
            delay(timeout)
            // Instead of calling a function directly, send an event to the channel.
            eventChannel.trySend(RaftEvent.ElectionTimeout)
        }
    }

    private fun startElection() {
        // All state changes are now safe without synchronized block
        currentTerm++
        currentState = NodeState.CANDIDATE
        votedFor = nodeId
        currentVotes = 1 // vote for itself
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Starting election for term $currentTerm")

        restartElectionTimeout()

        val peersCopy = raftPeers.toSet()
        val message = RaftElectionMessage.RequestVote(currentTerm, nodeId)

        Log.d(TAG, "${getSelfNodeIdDisplay()}: Sending RequestVote to ${raftPeers.size}peers for term $currentTerm")

        peersCopy.forEach { peer ->
            community.raftSend(peer, RaftElectionMessage.REQUEST_VOTE_ID, message)
            Log.d(TAG, "Node ${getSelfNodeIdDisplay()} Sent RequestVote to ${getNodeIdDisplay(peer)} for term $currentTerm")
        }
    }

    fun getLastHeartbeatTime(): Long = lastHeartbeatTime
    fun isLeader(): Boolean = currentState == NodeState.LEADER
    fun getCurrentLeader(): Peer? = currentLeader
    fun getCurrentState(): NodeState = currentState

    fun forceNewElection() {
        Log.d(TAG, "${getSelfNodeIdDisplay()}: A new election is being forced.")
        eventChannel.trySend(RaftEvent.ForceElection)
    }

    private fun becomeFollower(term: Int){
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Becoming follower for term $term")
        currentState = NodeState.FOLLOWER
        currentTerm = term
        votedFor = null
        heartbeatJob?.cancel()
        restartElectionTimeout()
    }

    private fun becomeLeader() {
        if(currentState == NodeState.LEADER) return

        Log.d(TAG, "${getSelfNodeIdDisplay()}: Becoming leader for term $currentTerm")
        currentState = NodeState.LEADER
        currentLeader = community.myPeer
        votedFor = null
        electionTimeOut?.cancel()
        onLeaderChangedCallback?.invoke(community.myPeer)
        startHeartbeat()
    }

    // Public-facing handlers now just send events and return immediately.
    fun handleRequestVote(peer: Peer, message: RaftElectionMessage.RequestVote) {
        eventChannel.trySend(RaftEvent.VoteRequested(peer, message))
    }

    fun handleVoteResponse(peer: Peer, term: Int, voteGranted: Boolean) {
        eventChannel.trySend(RaftEvent.VoteResponded(peer, term, voteGranted))
    }

    fun handleHeartbeat(from: Peer, term: Int, leaderId: String) {
        eventChannel.trySend(RaftEvent.HeartbeatReceived(from, term, leaderId))
    }

    // The actual logic is now in private "process" methods, called only from the event loop.
    private fun processRequestVote(peer: Peer, message: RaftElectionMessage.RequestVote) {
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Handling request vote from ${getNodeIdDisplay(peer)} for term ${message.term}, candidateId=${message.candidateId}")

        var voteGranted = false
        if (message.term < currentTerm) {
            Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for ${message.candidateId}: term ${message.term} < currentTerm $currentTerm")
            voteGranted = false
        } else {
            if (message.term > currentTerm) {
                becomeFollower(message.term)
            }
            if (votedFor == null || votedFor == message.candidateId) {
                votedFor = message.candidateId
                restartElectionTimeout()
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Granted vote to ${message.candidateId} for term ${message.term}")
                voteGranted = true
            } else {
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for ${message.candidateId}: already voted for $votedFor")
                voteGranted = false
            }
        }

        val response = RaftElectionMessage.VoteResponse(currentTerm, voteGranted)
        community.raftSend(peer, RaftElectionMessage.VOTE_RESPONSE_ID, response)
    }

    private fun processVoteResponse(peer: Peer, term: Int, voteGranted: Boolean) {
        if(currentState != NodeState.CANDIDATE || term < currentTerm) return
        if(term > currentTerm) {
            becomeFollower(term)
            return
        }

        if(voteGranted) {
            currentVotes++
            Log.d(TAG, "${getSelfNodeIdDisplay()}: Received vote from ${getNodeIdDisplay(peer)}, total votes: $currentVotes")
            if(currentVotes > (raftPeers.size + 1) / 2) {
                becomeLeader()
            }
        }
    }

    private fun processHeartbeat(from: Peer, term: Int, leaderId: String) {
        lastHeartbeatTime = System.currentTimeMillis()
        Log.d(TAG, "[$nodeId] Received heartbeat from ${from.mid.substring(0, 5)} for term $term")

        if (term >= currentTerm) {
            val oldLeader = currentLeader
            val wasNotFollower = currentState != NodeState.FOLLOWER

            if (term > currentTerm) {
                becomeFollower(term)
            } else {
                if (wasNotFollower) {
                    Log.d(TAG, "[$nodeId] Reverting to follower state.")
                    currentState = NodeState.FOLLOWER
                    heartbeatJob?.cancel()
                }
                restartElectionTimeout()
            }

            currentLeader = from
            if (currentLeader != oldLeader || wasNotFollower) {
                Log.d(TAG, "[$nodeId] New leader: ${currentLeader?.mid?.substring(0, 8)} for term $currentTerm")
                onLeaderChangedCallback?.invoke(currentLeader)
            }
        } else {
            Log.w(TAG, "[$nodeId] Ignored heartbeat from old term $term (current is $currentTerm)")
        }
    }

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
        val peersCopy = raftPeers.toSet()

        peersCopy.forEach { peer ->
            sendHeartbeatMessage(peer)
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
    fun getPeers(): Set<Peer> {
        return raftPeers
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
