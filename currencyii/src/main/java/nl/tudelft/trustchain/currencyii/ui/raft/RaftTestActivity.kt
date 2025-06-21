package nl.tudelft.trustchain.currencyii.ui.raft

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.currencyii.CoinCommunity
import nl.tudelft.trustchain.currencyii.R
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule.NodeState

/**
 * An activity for testing and observing the Raft election protocol within the CoinCommunity.
 *
 * This screen provides:
 * - Information about the local device and its network status.
 * - The current state of the Raft module (State, Term, Leader).
 * - A list of discovered peers participating in the Raft cluster.
 * - Controls to initialize Raft and force a new election.
 */
class RaftTestActivity : AppCompatActivity() {

    // --- Properties ---

    // UI Components
    private lateinit var textDeviceId: TextView
    private lateinit var textNetworkAddress: TextView
    private lateinit var textRaftState: TextView
    private lateinit var textCurrentLeader: TextView
    private lateinit var textCurrentTerm: TextView
    private lateinit var recyclerPeers: RecyclerView
    private lateinit var buttonInitRaft: Button
    private lateinit var buttonForceElection: Button

    // Adapters and Handlers
    private lateinit var peerAdapter: PeerAdapter
    private val uiUpdateHandler = Handler(Looper.getMainLooper())

    // IPv8 and Community
    private val coinCommunity: CoinCommunity by lazy {
        IPv8Android.getInstance().getOverlay<CoinCommunity>()
            ?: throw IllegalStateException("CoinCommunity not found")
    }

    // --- Lifecycle Methods ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_raft_test)

        initViews()
        setupRecyclerView()
        setupClickListeners()

        updateDeviceInfo()

        // Start a periodic task to ensure connection to bootstrap nodes.
        uiUpdateHandler.post(bootstrapConnectionRunnable)

        // Bind navigation logic
        val localTestButton: Button = findViewById(R.id.buttonLocalTest)
        localTestButton.setOnClickListener {
            val intent = Intent(this@RaftTestActivity, RaftTestLocalActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Start periodic UI updates.
        uiUpdateHandler.post(uiUpdateRunnable)
        // uiUpdateHandler.post(peerDiscoveryRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Stop all periodic tasks to prevent background work and memory leaks.
        uiUpdateHandler.removeCallbacksAndMessages(null)
    }

    // --- UI Setup ---

    /**
     * Initializes all UI views from the layout file.
     */
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

    /**
     * Sets up the RecyclerView with its adapter and layout manager.
     */
    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter()
        recyclerPeers.apply {
            layoutManager = LinearLayoutManager(this@RaftTestActivity)
            adapter = peerAdapter
        }
    }

    /**
     * Configures the click listeners for the buttons.
     */
    private fun setupClickListeners() {
        buttonInitRaft.setOnClickListener {
            initializeRaft()
        }

        buttonForceElection.setOnClickListener {
            forceNewElection()
        }
    }

    // --- Raft Logic ---

    /**
     * Initializes the Raft election module in the CoinCommunity if it hasn't been already.
     * For this test setup, it attempts to form a cluster using a predefined list of peers.
     */
    private fun initializeRaft() {
        if (coinCommunity.isRaftInitialized()) {
            Log.d("RaftTest", "Raft is already initialized.")
            return
        }

        Log.d("RaftTest", "Attempting to form Raft cluster with predefined members...")
        Log.d("RaftTest", "Current mid: ${coinCommunity.myPeer.mid}")
        // This will check if all hardcoded peers are found and initialize Raft if they are.
        coinCommunity.tryToFormRaftCluster()

        // Register a callback to update the UI whenever the leader changes.
        coinCommunity.onFrostCoordinatorChanged { isLeader, newLeader ->
            runOnUiThread {
                Log.d("RaftTest", "Leader changed. Is self leader: $isLeader, New leader: ${newLeader?.mid ?: "None"}")
                updateRaftStatus()
            }
        }
        updateRaftStatus() // Update UI immediately after initialization.
    }

    /**
     * Triggers a new Raft election. This is useful for testing the election process.
     */
    private fun forceNewElection() {
        if (!coinCommunity.isRaftInitialized()) {
            Log.w("RaftTest", "Cannot force election: Raft is not initialized.")
            return
        }

        try {
            Log.d("RaftTest", "Forcing a new election...")
            // Call the public method on the module to start an election.
            // This avoids using reflection to call a private method.
            coinCommunity.raftElectionModule.forceNewElection()
            updateRaftStatus() // Update UI immediately.
        } catch (e: Exception) {
            Log.e("RaftTest", "Failed to force election", e)
        }
    }

    // --- UI Updates ---

    /**
     * Displays static information about the local device's peer.
     */
    private fun updateDeviceInfo() {
        val myPeer = coinCommunity.myPeer
        textDeviceId.text = "Device ID: ${myPeer.mid}"
        textNetworkAddress.text = "Address: ${myPeer.wanAddress}"
    }

    /**
     * Fetches the current status from the Raft module and updates the UI text views.
     * This function now uses public getters, avoiding reflection.
     */
    private fun updateRaftStatus() {
        if (!coinCommunity.isRaftInitialized()) {
            runOnUiThread {
                textRaftState.text = "State: Not Initialized"
                textCurrentLeader.text = "Leader: None"
                textCurrentTerm.text = "Term: 0"
            }
            return
        }

        val raftModule = coinCommunity.raftElectionModule
        val state = raftModule.getCurrentState()
        val term = raftModule.getCurrentTerm()
        val isLeader = raftModule.isLeader()
        val leader = raftModule.getCurrentLeader()

        runOnUiThread {
            textRaftState.text = "State: $state"
            textCurrentTerm.text = "Term: $term"
            textCurrentLeader.text = if (isLeader) {
                "Leader: This Device"
            } else {
                "Leader: ${leader?.mid ?: "None"}"
            }

            // Update the list of peers known to the Raft module.
            // This provides a more accurate view of the cluster than getting random peers.
            val raftPeers = raftModule.getPeers()
            val allKnownPeers = (raftPeers + coinCommunity.myPeer).distinctBy { it.mid }
            allKnownPeers.forEach { peer ->
                peerAdapter.updatePeer(
                    peer = peer,
                    isInRaft = true,
                    raftState = if (leader?.mid == peer.mid) NodeState.LEADER else NodeState.FOLLOWER
                )
            }
        }
    }

    // --- Peer Discovery and Management ---

    /**
     * Discovers verified peers from the IPv8 network and registers them with the Raft module.
     * This allows the Raft cluster to form dynamically as peers connect.
     */
     private fun discoverAndRegisterRaftPeers() {
         if (!coinCommunity.isRaftInitialized()) return

         val allPeers = IPv8Android.getInstance().network.verifiedPeers
         Log.d("RaftTest", "Peer discovery found ${allPeers.size} verified peers.")

         allPeers.forEach { peer ->
             // Add all connected peers to the Raft module.
             // The module will handle them as part of the cluster.
             if (peer.isConnected()) {
                 Log.d("RaftTest", "Registering peer ${peer.mid} with Raft.")
                 coinCommunity.addRaftPeer(peer)
             }
         }
     }

    // --- Handlers and Runnables ---

    /**
     * A runnable task that periodically updates the UI with the latest Raft status.
     */
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateRaftStatus()
            uiUpdateHandler.postDelayed(this, 2000L) // Update every 2 seconds
        }
    }

    /**
     * A runnable task that periodically discovers and registers new peers with the Raft module.
     */
     private val peerDiscoveryRunnable = object : Runnable {
         override fun run() {
             discoverAndRegisterRaftPeers()
             uiUpdateHandler.postDelayed(this, 10000L) // Discover every 10 seconds
         }
     }

    /**
     * A runnable task that periodically attempts to connect to bootstrap nodes
     * and logs network information for debugging.
     */
    private val bootstrapConnectionRunnable = object : Runnable {
        override fun run() {
            Log.d("NetworkDiscovery", "Attempting to connect to bootstrap nodes...")
            coinCommunity.addBootstrapNodes()

            val peers = IPv8Android.getInstance().network.verifiedPeers
            Log.d("NetworkDiscovery", "Found ${peers.size} verified peers.")
            peers.forEach { peer ->
                Log.d("NetworkDiscovery", "Peer: ${peer.mid}, Connected: ${peer.isConnected()}")
            }

            uiUpdateHandler.postDelayed(this, 5000L) // Retry every 5 seconds
        }
    }
}
