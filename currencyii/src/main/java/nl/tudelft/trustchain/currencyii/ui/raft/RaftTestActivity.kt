package nl.tudelft.trustchain.currencyii.ui.raft

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.currencyii.CoinCommunity
import nl.tudelft.trustchain.currencyii.R
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule
import java.util.UUID

class RaftTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var peerCountText: TextView
    private lateinit var initRaftButton: Button
    private lateinit var addPeerButton: Button
    private lateinit var removePeerButton: Button
    private lateinit var forceElectionButton: Button

    private var coinCommunity: CoinCommunity? = null
    private var raftModule: RaftElectionModule? = null
    private var hasInitialized = false

    private val messageBroker = RaftMessageBroker()
    private val simulatedNodes = mutableListOf<SimulatedRaftNode>()
//    private val fakePeers = mutableListOf<Peer>()
    private val TAG = "RaftTestActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_raft_test)

        // Initialize UI components
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        peerCountText = findViewById(R.id.peerCountText)
        initRaftButton = findViewById(R.id.initRaftButton)
        addPeerButton = findViewById(R.id.addPeerButton)
        removePeerButton = findViewById(R.id.removePeerButton)
//        forceElectionButton = findViewById(R.id.forceElectionButton)

        // Get the CoinCommunity instance
        coinCommunity = getCoinCommunity()

        // Set up button click listeners
        initRaftButton.setOnClickListener { initializeRaft() }
        addPeerButton.setOnClickListener { addFakePeer() }
        removePeerButton.setOnClickListener { removeFakePeer() }
//        forceElectionButton.setOnClickListener { forceElection() }

        updateUI()
        startPeriodicUIRefresh()
    }

    private fun getCoinCommunity(): CoinCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("CoinCommunity is not configured")
    }

    private fun initializeRaft() {
        if(!hasInitialized){
            synchronized(this){
                if(!hasInitialized){
                    // Create a new simulated node
                    val localNode = SimulatedRaftNode("local", IPv4Address("127.0.0.1", 8444), messageBroker)
                    val delegate = NodeCommunicationDelegate(localNode.peer, messageBroker)
                    localNode.raftModule = RaftElectionModule(delegate, localNode.nodeId)

                    // Add leader change listener
                    localNode.raftModule.onLeaderChanged { newLeader ->
                        runOnUiThread {
                            logMessage("Node ${localNode.nodeId.substring(0, 5)} -> leaderChange: ${newLeader?.mid?.substring(0, 8) ?: "None"}")
                            updateUI()
                        }
                    }
                    logMessage("Initialize Raft: ${localNode.nodeId.substring(0, 5)}, mid: ${localNode.peer.mid.substring(0, 8)}")
                    simulatedNodes.add(localNode)
                    messageBroker.registerNode(localNode)

                    // Start the local node
                    localNode.raftModule.start()
                    hasInitialized = true
                }
            }
        }


    }

    private fun addFakePeer() {
        val node = SimulatedRaftNode(
            UUID.randomUUID().toString(),
            IPv4Address("127.0.0.${simulatedNodes.size + 1}", 8444),
            messageBroker
        )

        // Delegate the message routing to the message broker
        val delegate = NodeCommunicationDelegate(node.peer, messageBroker)
        node.raftModule = RaftElectionModule(delegate, node.nodeId)

        // Add leader change listener
        node.raftModule.onLeaderChanged { newLeader ->
            runOnUiThread {
                logMessage("Node ${node.nodeId.substring(0, 5)} -> leaderChange: ${newLeader?.mid?.substring(0, 8) ?: "None"}")
                updateUI()
            }
        }

        messageBroker.registerNode(node)

        simulatedNodes.forEach{ existing ->
            existing.raftModule.addPeer(node.peer)
            node.raftModule.addPeer(existing.peer)
        }
        simulatedNodes.add(node)
        node.raftModule.start()
        logMessage("Added fake node: ${node.nodeId.substring(0, 5)}, mid: ${node.peer.mid.substring(0, 8)}")
    }

    private fun removeFakePeer() {
        if(!hasInitialized || simulatedNodes.isEmpty()) {
            logMessage("No node to remove")
            return
        }

        val peerToRemove = simulatedNodes.removeAt(simulatedNodes.size - 1)

        // Stop the node
        peerToRemove.raftModule.stop()

        // Remove the node from all other nodes
        simulatedNodes.forEach { node ->
            node.raftModule.removePeer(peerToRemove.peer)
        }

        // Remove the node from the message broker (deligate)
        messageBroker.unregisterNode(peerToRemove)

        logMessage("Removed node: ${peerToRemove.nodeId.substring(0, 5)}, mid: ${peerToRemove.peer.mid.substring(0, 8)}")

        // Update the UI
        runOnUiThread {
            peerCountText.text = "Current node nums: ${simulatedNodes.size}"
            updateUI()
        }

        if(simulatedNodes.isEmpty()) {
            logMessage("No more nodes left")
            hasInitialized = false
        }
    }

//    private fun forceElection() {
//        if (!hasInitialized) {
//            logMessage("First initialize Raft before forcing an election")
//            return
//        }
//
//        // Use reflection -> startElection()
//        try {
//            val method = RaftElectionModule::class.java.getDeclaredMethod("startElection")
//            method.isAccessible = true
//            method.invoke(raftModule)
//            logMessage("Forced election started")
//        } catch (e: Exception) {
//            logMessage("Failed to force election: ${e.message}")
//        }
//    }

    private fun updatePeerCount() {
        if (raftModule != null) {
            val peerCount = raftModule!!.getPeers().size
            peerCountText.text = "Current node nums: $peerCount"
        }
    }

    private fun startPeriodicUIRefresh() {
        CoroutineScope(Dispatchers.Main).launch {
            while(isActive) {
                updateUI()
                delay(1000)
            }
        }
    }

    private fun updateUI() {
        // Show local node status
        if (simulatedNodes.isNotEmpty()) {
            val localNode = simulatedNodes[0]
            val currentState = when {
                localNode.raftModule.isLeader() -> "Leader"
                else -> "Follower"
            }

            val currentLeader = localNode.raftModule.getCurrentLeader()?.mid?.substring(0, 8) ?: "None"
            val currentTerm = localNode.raftModule.getCurrentTerm()
            val currentTime = System.currentTimeMillis()

            // Heartbeat status
            val lastHeartbeat = localNode.raftModule.getLastHeartbeatTime()
            val heartbeatStatus = if (lastHeartbeat > 0) {
                val timeSince = currentTime - lastHeartbeat
                if (localNode.raftModule.isLeader()) {
                    if (timeSince > 2000) "Sending heartbeat has stopped. (${timeSince}ms)" else "Sending normal: (${timeSince}ms ago)"
                } else {
                    if (timeSince > 2000) "Receiving heartbeat has stopped. (${timeSince}ms)" else "Reception is normal: (${timeSince}ms ago)"
                }
            } else "No heartbeat received yet"

            statusText.text = """
                Status: $currentState
                Current Term: $currentTerm
                Leader: $currentLeader
                Node number: ${simulatedNodes.size}
                Heartbeat status: $heartbeatStatus
            """.trimIndent()

            // Highlight the status text if heartbeat is not received for a while
            if (localNode.raftModule.isLeader() && lastHeartbeat > 0 &&
                System.currentTimeMillis() - lastHeartbeat > 2000) {
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            } else {
                statusText.setTextColor(resources.getColor(android.R.color.black))
            }
        } else {
            statusText.text = "Raft not initialized"
        }
    }

    private fun logMessage(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logText.append("${message}\n")
            // Automatically scroll to the bottom
            val scrollAmount = logText.layout?.getLineTop(logText.lineCount) ?: 0
            logText.scrollTo(0, scrollAmount)
        }
    }
}
