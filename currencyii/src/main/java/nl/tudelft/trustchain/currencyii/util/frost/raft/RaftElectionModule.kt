package nl.tudelft.trustchain.currencyii.util.frost.raft

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import java.util.Random
import java.util.UUID

class RaftElectionModule(
    private val community: RaftSendDelegate,
    private val nodeId: String = UUID.randomUUID().toString()
) {
    companion object {
        private const val TAG = "RaftElectionModule"
    }

    enum class NodeState {
        FOLLOWER, CANDIDATE, LEADER
    }

    private var currentState = NodeState.FOLLOWER
    private var currentTerm  = 0
    private var votedFor: String? = null
    private var currentLeader: Peer? = null

    private var electionTimeOut: Job? = null
    private var random = Random()
    private var minElectionTimeoutMs = 800L
    private var maxElectionTimeoutMs = 1500L
    private var heartbeatIntervalMs = 300L
    private var heartbeatJob: Job? = null
    private var lastHeartbeatTime: Long = 0

    private var currentVotes = 0
    private val peers = mutableSetOf<Peer>()

    private var onLeaderChangedCallback: ((Peer?) -> Unit)? = null

    fun start() {
        Log.d(TAG, "Starting Raft election module with node ID: ${getSelfNodeIdDisplay()}")
        becomeFollower(currentTerm)
    }

    fun stop() {
        electionTimeOut?.cancel()
        heartbeatJob?.cancel()
    }

    fun getLastHeartbeatTime(): Long = lastHeartbeatTime

    fun isLeader(): Boolean = currentState == NodeState.LEADER

    fun getCurrentLeader(): Peer? = currentLeader

    fun onLeaderChanged(callback: (Peer?) -> Unit) {
        onLeaderChangedCallback = callback
    }

    private fun becomeFollower(term: Int){
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Becoming follower for term $term")
        currentState = NodeState.FOLLOWER
        currentTerm = term
        votedFor = null

        restartElectionTimeout()
    }

    private fun restartElectionTimeout() {
        electionTimeOut?.cancel()
        electionTimeOut = CoroutineScope(Dispatchers.IO).launch {
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

    private fun sendRequestVoteMessage(peer: Peer) {
        // Use Community to send message
        val message = RaftElectionMessage.RequestVote(currentTerm, nodeId)
        community.raftSend(peer, RaftElectionMessage.REQUEST_VOTE_ID, message)
        Log.d(TAG, "Node ${getSelfNodeIdDisplay()} Sent RequestVote to ${getNodeIdDisplay(peer)} for term $currentTerm")
    }

    fun handleRequestVote(peer: Peer, term: Int, candidateId: String): Boolean {
        synchronized(this) {
            if(term < currentTerm){
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for $candidateId: term $term < currentTerm $currentTerm")
                return false
            }

            if(term > currentTerm){
                becomeFollower(term)
            }

            // now, term == currentTerm
            if(votedFor == null || votedFor == candidateId){
                votedFor = candidateId
                restartElectionTimeout()
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Granted vote to $candidateId for term $term")
                return true
            }

            Log.d(TAG, "${getSelfNodeIdDisplay()}: Rejected vote for $candidateId: already voted for $votedFor")
            return false
        }
    }

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

    fun handleHeartbeat(peer: Peer, term: Int, leaderId: String) {
        synchronized(this) {
            Log.d(TAG, "${getSelfNodeIdDisplay()}: Receiver heartbeat from ${getNodeIdDisplay(peer)}，term=$term")
            if(term < currentTerm) {
                return
            }

            if(term > currentTerm) {
                Log.d(TAG, "${getSelfNodeIdDisplay()}: Recv-heart: ${getNodeIdDisplay(peer)}, term $term, while local term $currentTerm, follow it")
                becomeFollower(term)
            }

            // Does not take part in election
            // but received heartbeat
            currentState = NodeState.FOLLOWER

            if(currentLeader != peer) {
                currentLeader = peer
                Log.d(TAG, "${getSelfNodeIdDisplay()}: New leader: ${getNodeIdDisplay(peer)} for term $term")
                onLeaderChangedCallback?.invoke(peer)
            }
            lastHeartbeatTime = System.currentTimeMillis()

            restartElectionTimeout()

        }
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

    private fun startHeartbeat() {
        heartbeatJob?.cancel()

        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
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

    private fun sendHeartbeatMessage(peer: Peer) {
        val message = RaftElectionMessage.Heartbeat(currentTerm, nodeId)
        community.raftSend(peer, RaftElectionMessage.HEARTBEAT_ID, message)
        lastHeartbeatTime = System.currentTimeMillis()
        Log.d(TAG, "${getSelfNodeIdDisplay()}: Send heartbeat to ${getNodeIdDisplay(peer)}, term=$currentTerm")
    }

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
