package nl.tudelft.trustchain.currencyii.ui.raft

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Runnable
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.trustchain.currencyii.CoinCommunity
import nl.tudelft.trustchain.currencyii.R
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionMessage
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule.NodeState

class RaftTestActivity : AppCompatActivity() {

    private lateinit var textDeviceId: TextView
    private lateinit var textNetworkAddress: TextView
    private lateinit var textRaftState: TextView
    private lateinit var textCurrentLeader: TextView
    private lateinit var textCurrentTerm: TextView
    private lateinit var recyclerPeers: RecyclerView
    private lateinit var buttonInitRaft: Button
    private lateinit var buttonForceElection: Button

    private lateinit var peerAdapter: PeerAdapter
    private val peerUpdateHandler = Handler(Looper.getMainLooper())
    private val updateInterval = 2000L // 2 seconds

    private val coinCommunity: CoinCommunity by lazy {
        val community = IPv8Android.getInstance().getOverlay<CoinCommunity>()
            ?: throw IllegalStateException("CoinCommunity not found")

        // 添加引导节点
        community.addBootstrapNodes()

        // 记录自己的网络信息
        Log.d("NetworkInfo", "My peer ID: ${community.myPeer.mid}")
        Log.d("NetworkInfo", "My LAN address: ${community.myPeer.lanAddress}")
        Log.d("NetworkInfo", "My WAN address: ${community.myPeer.wanAddress}")

        community
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateRaftStatus()
            peerUpdateHandler.postDelayed(this, updateInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_raft_test)

        initViews()
        setupRecyclerView()
        updateDeviceInfo()

        buttonInitRaft.setOnClickListener {
            initializeRaft()
        }

        buttonForceElection.setOnClickListener {
            forceElection()
        }

        val connectHandler = Handler(Looper.getMainLooper())
        connectHandler.postDelayed(object : Runnable {
            override fun run() {
                // 重新尝试添加引导节点
                coinCommunity.addBootstrapNodes()

                // 打印当前网络状态
                val peers = IPv8Android.getInstance().network.verifiedPeers
                Log.d("NetworkDiscovery", "Found ${peers.size} verified peers")
                peers.forEach { peer ->
                    Log.d("NetworkDiscovery", "Peer: ${peer.mid}, Connected: ${peer.isConnected()}")
                }

                connectHandler.postDelayed(this, 3000) // 每3秒执行一次
            }
        }, 1000) // 1秒后开始
    }

    override fun onResume() {
        super.onResume()
        peerDiscoveryHandler.postDelayed(peerDiscoveryRunnable, 5000)
        // Start the regular UI updates
        peerUpdateHandler.postDelayed(updateRunnable, 1000) // Start after 1 second
    }

    override fun onPause() {
        super.onPause()
        peerDiscoveryHandler.removeCallbacks(peerDiscoveryRunnable)
        // Remove the UI update callbacks
        peerUpdateHandler.removeCallbacks(updateRunnable)
    }

    private fun initViews() {
        textDeviceId = findViewById(R.id.textDeviceId)
        textNetworkAddress = findViewById(R.id.textNetworkAddress)
        textRaftState = findViewById(R.id.textRaftState)
        textCurrentLeader = findViewById(R.id.textCurrentLeader)
        textCurrentTerm = findViewById(R.id.textCurrentTerm)
        recyclerPeers = findViewById(R.id.recyclerPeers)
        buttonInitRaft = findViewById(R.id.buttonInitRaft)
        buttonForceElection = findViewById(R.id.buttonForceElection)
    }

    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter()
        recyclerPeers.apply {
            layoutManager = LinearLayoutManager(this@RaftTestActivity)
            adapter = peerAdapter
        }
    }

    private fun updateDeviceInfo() {
        val myPeer = coinCommunity.myPeer
        textDeviceId.text = "Device ID: ${myPeer.mid}"
        textNetworkAddress.text = "Address: ${myPeer.address}"
    }

    private fun initializeRaft() {
        if (!coinCommunity.isRaftInitialized()) {
            coinCommunity.initializeRaftElection()
            coinCommunity.onFrostCoordinatorChanged { isLeader, newLeader ->
                runOnUiThread {
                    if (isLeader) {
                        Log.d("RaftTest", "This device became leader!")
                    } else {
                        Log.d("RaftTest", "Leader changed to: ${newLeader?.mid ?: "None"}")
                    }
                    updateRaftStatus()
                }
            }
            updateRaftStatus() // Update UI immediately after initialization

            // Add all known peers from CoinCommunity
            val peers = IPv8Android.getInstance().network.getRandomPeers(10)
            for (peer in peers) {
                coinCommunity.addRaftPeer(peer)
            }

            val messageInterceptor = { packet: Packet ->
                val messageType = when {
                    packet.data.size >= 4 && packet.data[0].toInt() == RaftElectionMessage.REQUEST_VOTE_ID -> "Request Vote"
                    packet.data.size >= 4 && packet.data[0].toInt() == RaftElectionMessage.VOTE_RESPONSE_ID -> "Vote Response"
                    packet.data.size >= 4 && packet.data[0].toInt() == RaftElectionMessage.HEARTBEAT_ID -> "Heartbeat"
                    else -> "Unknown message"
                }

                Log.d("RaftInterceptor", "Intercept $messageType message, from: ${packet.source}")
                false // Continue processing the packet
            }

            try {
                val field = Community::class.java.getDeclaredField("packetInterceptors")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val interceptors = field.get(coinCommunity) as ArrayList<(Packet) -> Boolean>
                interceptors.add(messageInterceptor)
            } catch (e: Exception) {
                Log.e("RaftTest", "Failed to add intercepter", e)
            }
        }
    }

    private fun forceElection() {
        if (coinCommunity.isRaftInitialized()) {
            // Access the raft module and force election
            val raftModule = coinCommunity.raftElectionModule
            try {
                // This is a hypothetical method - you would need to add this to your RaftElectionModule
                // raftModule.forceNewElection()

                // If you don't have direct access, you can simulate a timeout
                // which would usually trigger an election
                val electionMethod = raftModule.javaClass.getDeclaredMethod("startElection")
                electionMethod.isAccessible = true
                electionMethod.invoke(raftModule)

                Log.d("RaftTest", "Forced new election")
                updateRaftStatus() // Update UI immediately after forcing an election
            } catch (e: Exception) {
                Log.e("RaftTest", "Failed to force election", e)
            }
        }
    }

    private fun updateRaftStatus() {
        if (coinCommunity.isRaftInitialized()) {
            val raftModule = coinCommunity.raftElectionModule

            val state = try {
                // Using reflection to get private field - you may want to expose these properly
                val stateField = raftModule.javaClass.getDeclaredField("state")
                stateField.isAccessible = true
                stateField.get(raftModule) as NodeState
            } catch (e: Exception) {
                null
            }

            val term = try {
                val termField = raftModule.javaClass.getDeclaredField("currentTerm")
                termField.isAccessible = true
                termField.get(raftModule) as Int
            } catch (e: Exception) {
                0
            }

            val isLeader = coinCommunity.isFrostCoordinator()
            val leader = coinCommunity.getFrostCoordinator()

            runOnUiThread {
                textRaftState.text = "State: ${state ?: "Unknown"}"
                textCurrentTerm.text = "Term: $term"
                textCurrentLeader.text = if (isLeader) {
                    "Leader: This Device"
                } else {
                    "Leader: ${leader?.mid ?: "None"}"
                }

                // Update known peers in the recycler view
                val peers = IPv8Android.getInstance().network.getRandomPeers(10)
                for (peer in peers) {
                    // For now, we don't know peers' Raft state, so just mark if they're in Raft
                    peerAdapter.updatePeer(
                        peer = peer,
                        isInRaft = true,
                        raftState = if (leader?.mid == peer.mid) NodeState.LEADER else NodeState.FOLLOWER
                    )
                }
            }
        } else {
            runOnUiThread {
                textRaftState.text = "State: Not Initialized"
                textCurrentLeader.text = "Leader: None"
                textCurrentTerm.text = "Term: 0"
            }
        }
    }

    private fun discoverAndRegisterRaftPeers() {
        // Get all verified peers from the network
        val allPeers = IPv8Android.getInstance().network.verifiedPeers

        Log.d("RaftTest", "Found ${allPeers.size} total peers")

        // Since we can't directly filter by service, add all peers to Raft
        // The Raft module will handle validating peers during the protocol
        allPeers.forEach { peer ->
            if (peer.isConnected()) {
                Log.d("RaftTest", "Registering peer ${peer.mid} (${peer.address}) with Raft")
                coinCommunity.addRaftPeer(peer)
            } else {
                Log.d("RaftTest", "Skipping disconnected peer ${peer.mid}")
            }
        }

        // Also log details about all peers to help debug the 0.0.0.0 issue
//        Log.d("RaftTest", "=== PEER DETAILS ===")
//        allPeers.forEach { peer ->
//            Log.d("RaftTest", "Peer ${peer.mid}:")
//            Log.d("RaftTest", "  - Address: ${peer.address}")
//            Log.d("RaftTest", "  - LAN Address: ${peer.lanAddress}")
//            Log.d("RaftTest", "  - WAN Address: ${peer.wanAddress}")
//            Log.d("RaftTest", "  - Connected: ${peer.isConnected()}")
//            Log.d("RaftTest", "  - Last Response: ${peer.lastResponse}")
//        }
    }

    // Call this method periodically or after initialization
    private val peerDiscoveryHandler = Handler(Looper.getMainLooper())
    private val peerDiscoveryRunnable = object : Runnable {
        override fun run() {
            discoverAndRegisterRaftPeers()
            peerDiscoveryHandler.postDelayed(this, 10000) // Every 10 seconds
        }
    }
}
