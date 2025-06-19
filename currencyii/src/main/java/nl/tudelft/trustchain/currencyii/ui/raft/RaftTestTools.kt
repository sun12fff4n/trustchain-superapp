//package nl.tudelft.trustchain.currencyii.ui.raft
//
//import android.util.Log
//import nl.tudelft.ipv8.IPv4Address
//import nl.tudelft.ipv8.Peer
//import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
//import nl.tudelft.trustchain.currencyii.RaftSendDelegate
//import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionMessage
//import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//
//
///**
// * Simluate on single device
// * SimulatedRaftNode represents a node in a simulated Raft cluster.
// */
//class SimulatedRaftNode(
//    val nodeId: String,
//    val address: IPv4Address,
//    private val messageBroker: RaftMessageBroker
//) {
//    val peer = Peer(AndroidCryptoProvider.generateKey(), address)
//    lateinit var raftModule: RaftElectionModule
//
//    fun handleMessage(from: Peer, messageBytes: ByteArray) {
//        val message = RaftElectionMessage.deserialize(messageBytes)
//        when (message) {
//            is RaftElectionMessage.RequestVote -> {
//                val granted = raftModule.handleRequestVote(from, message.term, message.candidateId)
//                val response = RaftElectionMessage.VoteResponse(raftModule.getCurrentTerm(), granted)
//                Log.d("SimulatedRaftNode", "Received vote request from ${from.mid.substring(0, 8)}: term=${message.term}, candidateId=${message.candidateId}, granted=$granted")
//
//                // Manually construct the packet format for the response
//                val responsePayloadBytes = response.serialize()
//                val buffer = ByteBuffer.allocate(4 + responsePayloadBytes.size)
//                buffer.order(ByteOrder.LITTLE_ENDIAN)
//                buffer.putInt(RaftElectionMessage.VOTE_RESPONSE_ID)
//                buffer.put(responsePayloadBytes)
//                messageBroker.routeMessage(peer, from, buffer.array())
//            }
//            is RaftElectionMessage.Heartbeat -> {
//                Log.d("SimulatedRaftNode", "Received heartbeat from ${from.mid.substring(0, 8)}: term=${message.term}")
//                raftModule.handleHeartbeat(from, message.term, message.leaderId)
//            }
//            is RaftElectionMessage.VoteResponse -> {
//                Log.d("SimulatedRaftNode", "Received vote response from ${from.mid.substring(0, 8)}: term=${message.term}, granted=${message.voteGranted}")
//                raftModule.handleVoteResponse(from, message.term, message.voteGranted)
//            }
//            else -> {
//                Log.d("SimulatedRaftNode", "Unknown message type from ${from.mid.substring(0, 8)}")
//            }
//        }
//    }
//}
//
//class RaftMessageBroker {
//    private val nodes = mutableMapOf<String, SimulatedRaftNode>()
//
//    fun registerNode(node: SimulatedRaftNode) {
//        nodes[node.peer.mid] = node
//    }
//
//    fun unregisterNode(node: SimulatedRaftNode) {
//        nodes.remove(node.peer.mid)
//    }
//
//    fun routeMessage(from: Peer, to: Peer, message: ByteArray) {
//        // TODO: Add simulations for network delay, packet loss, etc.
//        Log.d("RaftMessageBroker", "Routing message from ${from.mid.substring(0, 8)} to ${to.mid.substring(0, 8)}")
//        nodes[to.mid]?.handleMessage(from, message)
//    }
//}
//
//class NodeCommunicationDelegate(
//    override val myPeer: Peer,
//    private val messageBroker: RaftMessageBroker
//) : RaftSendDelegate {
//    override fun raftSend(
//        peer: Peer,
//        messageId: Int,
//        payload: nl.tudelft.ipv8.messaging.Serializable
//    ) {
//        // Manually construct the packet format that the test harness expects.
//        // [4-byte message ID][payload bytes]
//        val payloadBytes = payload.serialize()
//        val buffer = ByteBuffer.allocate(4 + payloadBytes.size)
//        buffer.order(ByteOrder.LITTLE_ENDIAN)
//        buffer.putInt(messageId)
//        buffer.put(payloadBytes)
//        messageBroker.routeMessage(myPeer, peer, buffer.array())
//    }
//}
