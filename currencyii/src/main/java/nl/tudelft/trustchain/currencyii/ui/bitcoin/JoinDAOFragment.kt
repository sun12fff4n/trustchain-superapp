package nl.tudelft.trustchain.currencyii.ui.bitcoin

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.currencyii.CoinCommunity
import nl.tudelft.trustchain.currencyii.CoinCommunity.Companion.FROST_BROADCASTING_BLOCK
import nl.tudelft.trustchain.currencyii.R
import nl.tudelft.trustchain.currencyii.coin.WalletManagerAndroid
import nl.tudelft.trustchain.currencyii.databinding.FragmentJoinNetworkBinding
import nl.tudelft.trustchain.currencyii.sharedWallet.FrostBroadcastingTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.FrostSWResponseSignatureBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.FrostSWSignatureAskBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWJoinBlockTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWResponseSignatureBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWSignatureAskBlockTD
import nl.tudelft.trustchain.currencyii.ui.BaseFragment
import nl.tudelft.trustchain.currencyii.util.frost.FrostMessageType
import nl.tudelft.trustchain.currencyii.util.frost.FrostPayload
import nl.tudelft.trustchain.currencyii.util.frost.FrostSigningResponseToJoinerMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostSigningResponseToSAMessage
import java.math.BigInteger

/**
 * A simple [Fragment] subclass.
 * Use the [BitcoinFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class JoinDAOFragment : BaseFragment(R.layout.fragment_join_network) {
    @Suppress("ktlint:standard:property-naming") // False positive
    private var _binding: FragmentJoinNetworkBinding? = null
    private val binding get() = _binding!!

    private var adapter: SharedWalletListAdapter? = null
    private var fetchedWallets: ArrayList<TrustChainBlock> = ArrayList()
    private var isFetching: Boolean = false

    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        super.onActivityCreated(savedInstanceState)
        initListeners()
        this.refresh()
    }

    private fun initListeners() {
        binding.joinDaoRefreshSwiper.setOnRefreshListener {
            this.refresh()
        }
    }

    private fun refresh() {
        enableRefresher()
        lifecycleScope.launchWhenStarted {
            fetchSharedWalletsAndUpdateUI()
        }
    }

    private fun enableRefresher() {
        try {
            this.isFetching = true
            binding.joinDaoRefreshSwiper.isRefreshing = true
        } catch (_: Exception) {
        }
    }

    private fun disableRefresher() {
        try {
            binding.joinDaoRefreshSwiper.isRefreshing = false
        } catch (_: Exception) {
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launchWhenStarted {
            fetchSharedWalletsAndUpdateUI()
        }
    }

    private fun fetchSharedWalletsAndUpdateUI() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                setAlertText("Crawling blocks for DAOs...")

                val discoveredWallets = getCoinCommunity().discoverSharedWallets()
                updateSharedWallets(discoveredWallets)
                updateSharedWalletsUI()
                crawlAvailableSharedWallets()
                updateSharedWalletsUI()

                if (fetchedWallets.isEmpty()) {
                    setAlertText("No DAOs found.")
                } else {
                    activity?.runOnUiThread {
                        binding.alertTf.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateSharedWallets(newWallets: List<TrustChainBlock>) {
        // This copy prevents the ConcurrentModificationException
        val walletsCopy = arrayListOf<TrustChainBlock>()
        walletsCopy.addAll(fetchedWallets)
        val walletIds =
            walletsCopy.map {
                SWJoinBlockTransactionData(it.transaction).getData().SW_UNIQUE_ID
            }
        val distinctById =
            newWallets
                .filter {
                    // Make sure that the trust chain block has the correct type
                    it.type == CoinCommunity.JOIN_BLOCK
                }.distinctBy {
                    SWJoinBlockTransactionData(it.transaction).getData().SW_UNIQUE_ID
                }

        Log.i("Coin", "${distinctById.size} unique wallets founds. Adding if not present already.")

        for (wallet in distinctById) {
            val currentId = SWJoinBlockTransactionData(wallet.transaction).getData().SW_UNIQUE_ID
            if (!walletIds.contains(currentId)) {
                fetchedWallets.add(wallet)
            }
        }
    }

    /**
     * Load shared wallet trust chain blocks. Blocks are crawled from trust chain users and loaded
     * from the local database.
     */
    private fun updateSharedWalletsUI() {
        lifecycleScope.launchWhenStarted {
            val publicKey = getTrustChainCommunity().myPeer.publicKey.keyToBin().toHex()
            val uniqueWallets: ArrayList<TrustChainBlock> = ArrayList()
            // This copy prevents the ConcurrentModificationException
            val walletCopy = arrayListOf<TrustChainBlock>()
            walletCopy.addAll(fetchedWallets)
            for (wallet in walletCopy) {
                if (!uniqueWallets.contains(wallet)) uniqueWallets.add(wallet)
            }
            // Update the list view with the found shared wallets
            adapter =
                SharedWalletListAdapter(
                    this@JoinDAOFragment,
                    uniqueWallets,
                    publicKey,
                    "Click to join via Frost",
                    disableOnUserJoined = true
                )

            binding.listView.adapter = adapter
            binding.listView.setOnItemClickListener { _, view, position, id ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        Log.i("Coin", "Clicked: $view, $position, $id")
                        joinSharedWalletFrostClicked(uniqueWallets[position])
                    }
                }
            }

            if (fetchedWallets.isEmpty()) {
                setAlertText("No DAOs found.")
            }
        }
    }

    /**
     * Crawl all shared wallet blocks of users in the trust chain.
     */
    private suspend fun crawlAvailableSharedWallets() {
        val allUsers = getDemoCommunity().getPeers()
        Log.i("Coin", "Found ${allUsers.size} peers, crawling")

        for (peer in allUsers) {
            try {
                // TODO: Commented this line out, it causes the app to crash
//                withTimeout(SW_CRAWLING_TIMEOUT_MILLI) {
                trustchain.crawlChain(peer)
                val crawlResult =
                    trustchain
                        .getChainByUser(peer.publicKey.keyToBin())

                updateSharedWallets(crawlResult)
//                }
            } catch (t: Throwable) {
                val message = t.message ?: "No further information"
                Log.e("Coin", "Crawling failed for: ${peer.publicKey}. $message.")
            }
        }
        disableRefresher()
    }

    /**
     * Join a shared bitcoin wallet.
     */
    private fun joinSharedWalletClicked(block: TrustChainBlock) {
        val mostRecentSWBlock =
            getCoinCommunity().fetchLatestSharedWalletBlock(block.calculateHash())
                ?: block

        // Add a proposal to trust chain to join a shared wallet
        val proposeBlockData =
            try {
                getCoinCommunity().proposeJoinWallet(
                    mostRecentSWBlock
                ).getData()
            } catch (t: Throwable) {
                Log.e("Coin", "Join wallet proposal failed. ${t.message ?: "No further information"}.")
                setAlertText(t.message ?: "Unexpected error occurred. Try again")
                return
            }

        val context = requireContext()
        // Wait and collect signatures
        var signatures: List<SWResponseSignatureBlockTD>? = null
        while (signatures == null) {
            Thread.sleep(1000)
            signatures = collectJoinWalletResponses(proposeBlockData)
        }

        // Create a new shared wallet using the signatures of the others.
        // Broadcast the new shared bitcoin wallet on trust chain.
        try {
            getCoinCommunity().joinBitcoinWallet(
                mostRecentSWBlock.transaction,
                proposeBlockData,
                signatures,
                context
            )
            // Add new nonceKey after joining a DAO
            WalletManagerAndroid.getInstance()
                .addNewNonceKey(proposeBlockData.SW_UNIQUE_ID, context)
        } catch (t: Throwable) {
            Log.e("Coin", "Joining failed. ${t.message ?: "No further information"}.")
            setAlertText(t.message ?: "Unexpected error occurred. Try again")
        }

        // Update wallets UI list
        fetchSharedWalletsAndUpdateUI()
        setAlertText("You joined ${proposeBlockData.SW_UNIQUE_ID}!")
    }

    /**
     * Join a shared bitcoin wallet via Frost.
     */
    private fun joinSharedWalletFrostClicked(block: TrustChainBlock) {
        val mostRecentSWBlock =
            getCoinCommunity().fetchLatestSharedWalletBlock(block.calculateHash())
                ?: block

        // Add a proposal to trust chain to join a shared wallet
        val proposeBlockData =
            try {
                getCoinCommunity().proposeJoinWalletFrost(
                    mostRecentSWBlock
                ).getData()
            } catch (t: Throwable) {
                Log.e(
                    "Coin",
                    "Join wallet proposal failed. ${t.message ?: "No further information"}."
                )
                setAlertText(t.message ?: "Unexpected error occurred. Try again")
                return
            }

        val context = requireContext()
        // Wait for Frost signature
        var frostSignature: BigInteger? = null
        while (frostSignature == null) {
            Thread.sleep(1000)
            frostSignature = BigInteger.ZERO
            val blockTDs = getTrustChainCommunity().database.getBlocksWithType(
                FROST_BROADCASTING_BLOCK
            )
                .filter {
                    val blockData = FrostBroadcastingTransactionData(it.transaction)
                    blockData.getData().SW_UNIQUE_ID == proposeBlockData.SW_UNIQUE_ID
                }
                .map {
                    FrostBroadcastingTransactionData(it.transaction).getData()
                }
            for (msg in blockTDs) {
                val frostPayload = FrostPayload.deserialize(msg.SW_FROST_DATA, 0).first
                if (frostPayload.messageType != FrostMessageType.FROST_SIGINING_TO_JOINER) {
                    continue
                }
                val frostSignatureMessage = FrostSigningResponseToJoinerMessage.deserialize(frostPayload.data)
                Log.i("Frost", "Fetched frost signature ${frostSignatureMessage.aggregateSignature} for ${frostSignatureMessage.joinerId}")
                if (frostSignatureMessage.joinerId != java.util.Base64.getEncoder().encodeToString(getTrustChainCommunity().myPeer.publicKey.keyToBin())) {
                    continue
                }
                frostSignature = frostSignatureMessage.aggregateSignature
                break
            }
        }

        // Create a new shared wallet using the frost signature
        // Broadcast the new shared bitcoin wallet on trust chain.
        try {
            getCoinCommunity().joinBitcoinWalletFrost(
                mostRecentSWBlock.transaction,
                proposeBlockData,
                frostSignature,
                context
            )
            // Add new nonceKey after joining a DAO
            WalletManagerAndroid.getInstance()
                .addNewNonceKey(proposeBlockData.SW_UNIQUE_ID, context)
        } catch (t: Throwable) {
            Log.e("Coin", "Joining failed. ${t.message ?: "No further information"}.")
            setAlertText(t.message ?: "Unexpected error occurred. Try again")
        }

        // Update wallets UI list
        fetchSharedWalletsAndUpdateUI()
        setAlertText("You joined ${proposeBlockData.SW_UNIQUE_ID}!")
    }


    /**
     * Collect the signatures of a join proposal
     */
    private fun collectJoinWalletResponses(blockData: SWSignatureAskBlockTD): List<SWResponseSignatureBlockTD>? {
        val responses =
            getCoinCommunity().fetchProposalResponses(
                blockData.SW_UNIQUE_ID,
                blockData.SW_UNIQUE_PROPOSAL_ID
            )
        Log.i(
            "Coin",
            "Waiting for signatures. ${responses.size}/${blockData.SW_SIGNATURES_REQUIRED} received!"
        )

        setAlertText(
            "Collecting signatures: ${responses.size}/${blockData.SW_SIGNATURES_REQUIRED} received!"
        )

        if (responses.size >= blockData.SW_SIGNATURES_REQUIRED) {
            return responses
        }
        return null
    }

    /**
     * check if exists the frost signature of a join proposal
     */
    private fun getFrostJoinWalletResponseIfExists(blockData: FrostSWSignatureAskBlockTD): FrostSWResponseSignatureBlockTD? {

        val response =
            getCoinCommunity().fetchProposalFrostResponse(
                blockData.SW_UNIQUE_ID,
                blockData.SW_UNIQUE_PROPOSAL_ID
            )
        if (response != null) {
            Log.i(
                "Frost",
                "fetched frost signature ${"hello"}"
            )
        }
        return response
    }

    private fun setAlertText(text: String) {
        activity?.runOnUiThread {
            binding.alertTf.visibility = View.VISIBLE
            binding.alertTf.text = text
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJoinNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance() = JoinDAOFragment()

        public const val SW_CRAWLING_TIMEOUT_MILLI: Long = 1_000 * 10
    }
}
