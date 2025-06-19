package nl.tudelft.trustchain.currencyii.ui.raft

import android.util.Log
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.trustchain.currencyii.RaftSendDelegate
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionMessage
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
* Simluate on single device
* SimulatedRaftNode represents a node in a simulated Raft cluster.
*/
class SimulatedRaftNode(
   val nodeId: String,
   val address: IPv4Address,
   private val messageBroker: RaftMessageBroker
) {
   val peer = Peer(AndroidCryptoProvider.generateKey(), address)
   lateinit var raftModule: RaftElectionModule
   // Add a reference to the sender delegate to avoid manual message construction.
   lateinit var sender: NodeCommunicationDelegate

   fun handleMessage(from: Peer, messageBytes: ByteArray) {
       // Manually parse the message ID and payload to align with modern overlay patterns.
       val buffer = ByteBuffer.wrap(messageBytes).order(ByteOrder.LITTLE_ENDIAN)
       val messageId = buffer.int
       val payloadBytes = ByteArray(buffer.remaining())
       buffer.get(payloadBytes)

       when (messageId) {
           RaftElectionMessage.REQUEST_VOTE_ID -> {
               val (message, _) = RaftElectionMessage.RequestVote.deserialize(payloadBytes, 0)
               // The RaftElectionModule handles sending the vote response itself.
               // We just need to pass the message to it.
               Log.d("SimulatedRaftNode", "Received vote request from ${from.mid.substring(0, 8)}: term=${message.term}, candidateId=${message.candidateId}. Passing to module.")
               raftModule.handleRequestVote(from, message)
           }
           RaftElectionMessage.HEARTBEAT_ID -> {
               val (message, _) = RaftElectionMessage.Heartbeat.deserialize(payloadBytes, 0)
               Log.d("SimulatedRaftNode", "Received heartbeat from ${from.mid.substring(0, 8)}: term=${message.term}")
               raftModule.handleHeartbeat(from, message.term, message.leaderId)
           }
           RaftElectionMessage.VOTE_RESPONSE_ID -> {
               val (message, _) = RaftElectionMessage.VoteResponse.deserialize(payloadBytes, 0)
               Log.d("SimulatedRaftNode", "Received vote response from ${from.mid.substring(0, 8)}: term=${message.term}, granted=${message.voteGranted}")
               raftModule.handleVoteResponse(from, message.term, message.voteGranted)
           }
           else -> {
               Log.d("SimulatedRaftNode", "Unknown message ID $messageId from ${from.mid.substring(0, 8)}")
           }
       }
   }
}

class RaftMessageBroker {
   private val nodes = mutableMapOf<String, SimulatedRaftNode>()

   fun registerNode(node: SimulatedRaftNode) {
       nodes[node.peer.mid] = node
   }

   fun unregisterNode(node: SimulatedRaftNode) {
       nodes.remove(node.peer.mid)
   }

   fun routeMessage(from: Peer, to: Peer, message: ByteArray) {
       // TODO: Add simulations for network delay, packet loss, etc.
       Log.d("RaftMessageBroker", "Routing message from ${from.mid.substring(0, 8)} to ${to.mid.substring(0, 8)}")
       nodes[to.mid]?.handleMessage(from, message)
   }
}

class NodeCommunicationDelegate(
   override val myPeer: Peer,
   private val messageBroker: RaftMessageBroker
) : RaftSendDelegate {
   override fun raftSend(
       peer: Peer,
       messageId: Int,
       payload: nl.tudelft.ipv8.messaging.Serializable
   ) {
       // Manually construct the packet format that the test harness expects.
       // [4-byte message ID][payload bytes]
       val payloadBytes = payload.serialize()
       val buffer = ByteBuffer.allocate(4 + payloadBytes.size)
       buffer.order(ByteOrder.LITTLE_ENDIAN)
       buffer.putInt(messageId)
       buffer.put(payloadBytes)
       messageBroker.routeMessage(myPeer, peer, buffer.array())
   }
}
