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

class RaftTestLocalActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var peerCountText: TextView
    private lateinit var initRaftButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var coinCommunity: CoinCommunity? = null
    private var hasInitialized = false

    private val messageBroker = RaftMessageBroker()
    private val allNodes = mutableListOf<SimulatedRaftNode>()
    private val connectedNodes = mutableListOf<SimulatedRaftNode>()
    private val disconnectedNodes = mutableListOf<SimulatedRaftNode>()

    private val TAG = "RaftTestLocalActivity"
    private val TOTAL_NODE_COUNT = 7
    private val MIN_NODE_COUNT = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_raft_test_local)

        // Initialize UI components
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        peerCountText = findViewById(R.id.peerCountText)
        initRaftButton = findViewById(R.id.initRaftButton)
        connectButton = findViewById(R.id.addPeerButton) // ID from XML
        disconnectButton = findViewById(R.id.removePeerButton) // ID from XML

        // Update button text to reflect new functionality
        connectButton.text = "Connect Node"
        disconnectButton.text = "Disconnect Node"

        // Get the CoinCommunity instance
        coinCommunity = getCoinCommunity()

        // Set up button click listeners
        initRaftButton.setOnClickListener { initializeRaft() }
        connectButton.setOnClickListener { connectNode() }
        disconnectButton.setOnClickListener { disconnectNode() }

        updateUI()
        startPeriodicUIRefresh()
    }

    private fun getCoinCommunity(): CoinCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("CoinCommunity is not configured")
    }

    private fun initializeRaft() {
        if (hasInitialized) {
            logMessage("Raft is already initialized.")
            return
        }
        synchronized(this) {
            if (hasInitialized) return

            logMessage("Initializing with $TOTAL_NODE_COUNT nodes...")

            // Clear previous state
            allNodes.clear()
            connectedNodes.clear()
            disconnectedNodes.clear()

            // 1. Create all simulated node instances first
            for (i in 1..TOTAL_NODE_COUNT) {
                val node = SimulatedRaftNode(
                    UUID.randomUUID().toString(),
                    IPv4Address("127.0.0.1", 8000 + i),
                    messageBroker
                )
                allNodes.add(node)
            }

            val allPeers = allNodes.map { it.peer }.toSet()

            // 2. Initialize Raft module for each node with the full peer list
            for (node in allNodes) {
                val otherPeers = allPeers - node.peer // Exclude the node itself
                val delegate = NodeCommunicationDelegate(node.peer, messageBroker)
                node.raftModule = RaftElectionModule(delegate, otherPeers, node.nodeId)
                node.sender = delegate

                node.raftModule.onLeaderChanged { newLeader ->
                    runOnUiThread {
                        logMessage("Node ${node.nodeId.substring(0, 5)} -> leaderChange: ${newLeader?.mid?.substring(0, 8) ?: "None"}")
                        updateUI()
                    }
                }
            }

            // 3. Connect all nodes and start their Raft modules
            allNodes.forEach { node ->
                connectedNodes.add(node)
                messageBroker.registerNode(node)
                node.raftModule.start()
            }

            hasInitialized = true
            logMessage("Initialization complete. All $TOTAL_NODE_COUNT nodes are connected.")
            runOnUiThread { updateUI() }
        }
    }

    private fun connectNode() {
        if (!hasInitialized) {
            logMessage("Initialize Raft first.")
            return
        }

        if (disconnectedNodes.isEmpty()) {
            logMessage("All nodes connected")
            return
        }

        val nodeToConnect = disconnectedNodes.removeAt(disconnectedNodes.size - 1)
        messageBroker.registerNode(nodeToConnect)
        nodeToConnect.raftModule.start()
        connectedNodes.add(nodeToConnect)

        logMessage("Reconnected node: ${nodeToConnect.nodeId.substring(0, 5)}")
        runOnUiThread { updateUI() }
    }

    private fun disconnectNode() {
        if (!hasInitialized) {
            logMessage("Initialize Raft first.")
            return
        }

        if (connectedNodes.size <= MIN_NODE_COUNT) {
            logMessage("Cannot remove more")
            return
        }

        // Find the current leader
        val leaderPeer = connectedNodes.firstOrNull()?.raftModule?.getCurrentLeader()
        if (leaderPeer == null) {
            logMessage("No leader elected yet. Cannot disconnect leader.")
            return
        }

        val leaderNode = connectedNodes.find { it.peer == leaderPeer }
        if (leaderNode == null) {
            logMessage("Error: Leader peer found, but the corresponding node is not in the connected list.")
            return
        }

        // Disconnect the leader node
        connectedNodes.remove(leaderNode)
        leaderNode.raftModule.stop()
        messageBroker.unregisterNode(leaderNode)
        disconnectedNodes.add(leaderNode)

        logMessage("Disconnected leader node: ${leaderNode.nodeId.substring(0, 5)}")
        runOnUiThread { updateUI() }
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
        if (!hasInitialized) {
            statusText.text = "Raft not initialized"
            peerCountText.text = "Connected Nodes: 0 / $TOTAL_NODE_COUNT"
            return
        }

        peerCountText.text = "Connected Nodes: ${connectedNodes.size} / $TOTAL_NODE_COUNT"

        // Show status of the first connected node
        if (connectedNodes.isNotEmpty()) {
            val firstNode = connectedNodes[0]
            val currentState = when {
                firstNode.raftModule.isLeader() -> "Leader"
                else -> "Follower"
            }

            val currentLeader = firstNode.raftModule.getCurrentLeader()?.mid?.substring(0, 8) ?: "None"
            val currentTerm = firstNode.raftModule.getCurrentTerm()
            val currentTime = System.currentTimeMillis()

            // Heartbeat status
            val lastHeartbeat = firstNode.raftModule.getLastHeartbeatTime()
            val heartbeatStatus = if (lastHeartbeat > 0) {
                val timeSince = currentTime - lastHeartbeat
                if (firstNode.raftModule.isLeader()) {
                    if (timeSince > 2000) "Sending heartbeat has stopped. (${timeSince}ms)" else "Sending normal: (${timeSince}ms ago)"
                } else {
                    if (timeSince > 2000) "Receiving heartbeat has stopped. (${timeSince}ms)" else "Reception is normal: (${timeSince}ms ago)"
                }
            } else "No heartbeat received yet"

            statusText.text = """
                Status (Node 0): $currentState
                Current Term: $currentTerm
                Leader: $currentLeader
                Heartbeat status: $heartbeatStatus
            """.trimIndent()

            // Highlight the status text if heartbeat is not received for a while
            if (lastHeartbeat > 0 && System.currentTimeMillis() - lastHeartbeat > 2000) {
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            } else {
                statusText.setTextColor(resources.getColor(android.R.color.black))
            }
        } else {
            statusText.text = "All nodes are disconnected"
        }
    }

    private fun logMessage(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logText.append("${message}\n")
            // Automatically scroll to the bottom
            val layout = logText.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(logText.lineCount) - logText.height
                if (scrollAmount > 0) {
                    logText.scrollTo(0, scrollAmount)
                } else {
                    logText.scrollTo(0, 0)
                }
            }
        }
    }
}
