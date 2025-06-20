package nl.tudelft.trustchain.currencyii

//import nl.tudelft.trustchain.currencyii.util.frost.FrostSignatureMessage
import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.currencyii.sharedWallet.SWJoinBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWJoinBlockTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWResponseNegativeSignatureBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWResponseNegativeSignatureTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWResponseSignatureBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWResponseSignatureTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWSignatureAskBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWSignatureAskTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWTransferDoneTransactionData
import nl.tudelft.trustchain.currencyii.sharedWallet.SWTransferFundsAskBlockTD
import nl.tudelft.trustchain.currencyii.sharedWallet.SWTransferFundsAskTransactionData
import nl.tudelft.trustchain.currencyii.util.DAOCreateHelper
import nl.tudelft.trustchain.currencyii.util.DAOJoinHelper
import nl.tudelft.trustchain.currencyii.util.DAOTransferFundsHelper
import nl.tudelft.trustchain.currencyii.util.frost.FrostCommitmentMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostKeyGenEngine
import nl.tudelft.trustchain.currencyii.util.frost.FrostMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostMessageType
import nl.tudelft.trustchain.currencyii.util.frost.FrostVerificationShareMessage
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionMessage
import nl.tudelft.trustchain.currencyii.util.frost.raft.RaftElectionModule
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

interface FrostSendDelegate {
    fun frostSend(peer: Peer, data: ByteArray): Unit
}

interface RaftSendDelegate {
    fun raftSend(peer: Peer, messageId: Int, payload: nl.tudelft.ipv8.messaging.Serializable)
    val myPeer: Peer
}

@Suppress("UNCHECKED_CAST")
class CoinCommunity constructor(serviceId: String = "02313685c1912a141279f8248fc8db5899c5df5b") : Community(), FrostSendDelegate, RaftSendDelegate {
    override val serviceId = serviceId

    override lateinit var myPeer: Peer
    // Map to store active FROST key generation engines by session ID
    private val activeKeyGenEngines = ConcurrentHashMap<String, FrostKeyGenEngine>()

    // send function for frost
    override fun frostSend(peer: Peer, data: ByteArray): Unit {
        return send(peer, data)
    }

    // send function for raft
    override fun raftSend(peer: Peer, messageId: Int, payload: nl.tudelft.ipv8.messaging.Serializable) {
        val packet = serializePacket(messageId, payload)
        send(peer, packet)
    }

    // receive callback for frost
    init {
        messageHandlers[RaftElectionMessage.REQUEST_VOTE_ID] = ::onRequestVote
        messageHandlers[RaftElectionMessage.VOTE_RESPONSE_ID] = ::onVoteResponse
        messageHandlers[RaftElectionMessage.HEARTBEAT_ID] = ::onHeartbeat
    }

    private fun onFrostMessage(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(object : Deserializable<FrostMessage> {
            override fun deserialize(buffer: ByteArray, offset: Int): Pair<FrostMessage, Int> {
                // Just ignore offset for now since our deserializer doesn’t use it
                val (message, _) = FrostMessage.deserialize(buffer.copyOfRange(offset, buffer.size))
                return Pair(message, buffer.size - offset)
            }
        })
        val frostMessage = payload as FrostMessage
        val sessionId = frostMessage.sessionId
        val peerId: (Peer) -> String = { p ->
            Base64.getEncoder().encodeToString(p.publicKey.keyToBin())
        }
        val peerPublicKeyHex = peerId(peer)

        // Find the corresponding key generation engine
        val engine = activeKeyGenEngines[sessionId]

        if (engine != null) {
            CoroutineScope(Dispatchers.Default).launch {
                when (frostMessage.messageType) {
                    FrostMessageType.COMMITMENT -> {
                        val commitmentMessage = FrostCommitmentMessage.deserialize(sessionId, frostMessage.data)
                        engine.processCommitmentMessage(peerPublicKeyHex, commitmentMessage.commitment, commitmentMessage.proof)
                    }
                    FrostMessageType.VERIFICATION_SHARE -> {
                        val verificationShareMessage = FrostVerificationShareMessage.deserialize(sessionId, frostMessage.data)
                        engine.processVerificationShareMessage(peerPublicKeyHex, verificationShareMessage.verificationShare)
                    }
                }
            }
        }
    }

    // Register a new FROST key generation engine
    fun registerFrostKeyGenEngine(sessionId: String, engine: FrostKeyGenEngine) {
        activeKeyGenEngines[sessionId] = engine
    }

    // Unregister a FROST key generation engine
    fun unregisterFrostKeyGenEngine(sessionId: String) {
        activeKeyGenEngines.remove(sessionId)
    }

    private fun getTrustChainCommunity(): TrustChainCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("TrustChainCommunity is not configured")
    }

    private val daoCreateHelper = DAOCreateHelper()
    private val daoJoinHelper = DAOJoinHelper()
    private val daoTransferFundsHelper = DAOTransferFundsHelper()

    /**
     *
     * Raft Election
     *
     */
    // Lazy Allocation
    private var frostCoordinatorCallback: ((isLeader: Boolean, newLeader: Peer?) -> Unit)? = null

    private var _raftElectionModule: RaftElectionModule? = null

    val raftElectionModule: RaftElectionModule
        get() {
            if (_raftElectionModule == null) {
                // Note: Lazy initialization without peers might not be ideal
                // depending on the exact startup flow.
                // Consider initializing only when peers are known.
                initializeRaftElection(emptyList())
            }
            return _raftElectionModule!!
        }

    fun isRaftInitialized(): Boolean = _raftElectionModule != null

    fun initializeRaftElection(clusterPeers: Collection<Peer>) {
        if (_raftElectionModule == null) {
            _raftElectionModule = RaftElectionModule(this)

            // Add all peers to the module
            clusterPeers.forEach { peer ->
                _raftElectionModule?.addPeer(peer)
            }

            // Set up leader change callback
            _raftElectionModule?.onLeaderChanged { newLeader ->
                Log.d(TAG, "Leader changed to: ${newLeader?.mid ?: "None"}")
                frostCoordinatorCallback?.invoke(isRaftInitialized() &&
                    _raftElectionModule?.isLeader() == true, newLeader)
            }

            _raftElectionModule?.start()
            Log.d(TAG, "RaftElectionModule initialized with ${clusterPeers.size} peers.")
        }
    }

    fun onFrostCoordinatorChanged(callback: (isLeader: Boolean, newLeader: Peer?) -> Unit) {
        frostCoordinatorCallback = callback
    }

    fun addRaftPeer(peer: Peer) {
        if (isRaftInitialized()) {
            raftElectionModule.addPeer(peer)
        }
    }

    fun removeRaftPeer(peer: Peer) {
        if (isRaftInitialized()) {
            raftElectionModule.removePeer(peer)
        }
    }

    fun isFrostCoordinator(): Boolean {
        return isRaftInitialized() && raftElectionModule.isLeader()
    }

    fun getFrostCoordinator(): Peer? {
        return if (isRaftInitialized()) {
            raftElectionModule.getCurrentLeader()
        } else null
    }

    // handle RequestVote
    private fun onRequestVote(packet: Packet) {
        val (peer, message) = packet.getAuthPayload(RaftElectionMessage.RequestVote)
        Log.d("RaftMsg", "Received RequestVote from ${peer.mid}, term=${message.term}. Delegating to Raft module.")

        // Simply delegate the entire handling to the Raft module
        raftElectionModule.handleRequestVote(peer, message)
    }

    // Handle VoteResponse
    private fun onVoteResponse(packet: Packet) {
        val (peer, message) = packet.getAuthPayload(RaftElectionMessage.VoteResponse)
        Log.d("RaftMsg", "Received VoteResponse from ${peer.mid}, term=${message.term}, granted=${message.voteGranted}. Delegating to Raft module.")

        // Handle vote response
        raftElectionModule.handleVoteResponse(peer, message.term, message.voteGranted)
    }

    // Handle Heartbeat
    private fun onHeartbeat(packet: Packet) {
        val (peer, message) = packet.getAuthPayload(RaftElectionMessage.Heartbeat)
        Log.d("RaftMsg", "Received Heartbeat from ${peer.mid}, term=${message.term}, leaderId=${message.leaderId}. Delegating to Raft module.")

        // Handle heartbeat message
        raftElectionModule.handleHeartbeat(peer, message.term, message.leaderId)
    }

    fun logRaftStatus() {
        if (!isRaftInitialized()) {
            Log.d("RaftDebug", "Raft not initialized")
            return
        }

        val peers = try {
            val peersField = raftElectionModule.javaClass.getDeclaredField("peers")
            peersField.isAccessible = true
            peersField.get(raftElectionModule) as Set<*>
        } catch (e: Exception) {
            emptySet<Any>()
        }

        Log.d("RaftDebug", "Raft has ${peers.size} registered peers")
        peers.forEach { peer ->
            Log.d("RaftDebug", "Raft peer: $peer")
        }
    }

    /**
     * Create a bitcoin genesis wallet and broadcast the result on trust chain.
     * The bitcoin transaction may take some time to finish.
     * @throws - exception if something goes wrong with creating or broadcasting bitcoin transaction.
     * @param entranceFee - Long, the entrance fee for joining the DAO.
     * @param threshold - Int, the percentage of members that need to vote before allowing someone in the DAO.
     */
    fun createBitcoinGenesisWallet(
        entranceFee: Long,
        threshold: Int,
        context: Context
    ): SWJoinBlockTransactionData {
        return daoCreateHelper.createBitcoinGenesisWallet(
            myPeer,
            entranceFee,
            threshold,
            context
        )
    }

    /**
     * 2.1 Send a proposal on the trust chain to join a shared wallet and to collect signatures.
     * The proposal is a serialized bitcoin join transaction.
     * **NOTE** the latest walletBlockData should be given, otherwise the serialized transaction is invalid.
     * @param walletBlock - the latest (that you know of) shared wallet block.
     */
    fun proposeJoinWallet(walletBlock: TrustChainBlock): SWSignatureAskTransactionData {
        return daoJoinHelper.proposeJoinWallet(myPeer, walletBlock)
    }

    /**
     * 2.2 Commit the join wallet transaction on the bitcoin blockchain and broadcast the result on trust chain.
     *
     * Note:
     * There should be enough sufficient signatures, based on the multisig wallet data.
     * @throws - exceptions if something goes wrong with creating or broadcasting bitcoin transaction.
     * @param walletBlockData - TrustChainTransaction, describes the wallet that is joined
     * @param blockData - SWSignatureAskBlockTD, the block where the other users are voting on
     * @param responses - the positive responses for your request to join the wallet
     */
    fun joinBitcoinWallet(
        walletBlockData: TrustChainTransaction,
        blockData: SWSignatureAskBlockTD,
        responses: List<SWResponseSignatureBlockTD>,
        context: Context
    ) {
        daoJoinHelper.joinBitcoinWallet(
            myPeer,
            walletBlockData,
            blockData,
            responses,
            context
        )
    }

    /**
     * 3.1 Send a proposal block on trustchain to ask for the signatures.
     * Assumed that people agreed to the transfer.
     * @param walletBlock - TrustChainBlock, describes the wallet where the transfer is from
     * @param receiverAddressSerialized - String, the address where the transaction needs to go
     * @param satoshiAmount - Long, the amount that needs to be transferred
     * @return the proposal block
     */
    fun proposeTransferFunds(
        walletBlock: TrustChainBlock,
        receiverAddressSerialized: String,
        satoshiAmount: Long
    ): SWTransferFundsAskTransactionData {
        return daoTransferFundsHelper.proposeTransferFunds(
            myPeer,
            walletBlock,
            receiverAddressSerialized,
            satoshiAmount
        )
    }

    /**
     * 3.2 Transfer funds from an existing shared wallet to a third-party. Broadcast bitcoin transaction.
     * @param walletData - SWJoinBlockTD, the data about the wallet when joining the wallet
     * @param walletBlockData - TrustChainTransaction, describes the wallet where the transfer is from
     * @param blockData - SWTransferFundsAskBlockTD, the block where the other users are voting on
     * @param responses - List<SWResponseSignatureBlockTD>, the list with positive responses on the voting
     * @param receiverAddress - String, the address where the transfer needs to go
     * @param satoshiAmount - Long, the amount that needs to be transferred
     */
    fun transferFunds(
        walletData: SWJoinBlockTD,
        walletBlockData: TrustChainTransaction,
        blockData: SWTransferFundsAskBlockTD,
        responses: List<SWResponseSignatureBlockTD>,
        receiverAddress: String,
        satoshiAmount: Long,
        context: Context,
        activity: Activity
    ) {
        daoTransferFundsHelper.transferFunds(
            myPeer,
            walletData,
            walletBlockData,
            blockData,
            responses,
            receiverAddress,
            satoshiAmount,
            context,
            activity
        )
    }

    /**
     * Discover shared wallets that you can join, return the latest blocks that the user knows of.
     */
    fun discoverSharedWallets(): List<TrustChainBlock> {
        val swBlocks = getTrustChainCommunity().database.getBlocksWithType(JOIN_BLOCK)
        return swBlocks
            .distinctBy { SWJoinBlockTransactionData(it.transaction).getData().SW_UNIQUE_ID }
            .map { fetchLatestSharedWalletBlock(it, swBlocks) ?: it }
    }

    /**
     * Discover shared wallets that you can join, return the latest (known) blocks
     * Fetch the latest block associated with a shared wallet.
     * swBlockHash - the hash of one of the blocks associated with a shared wallet.
     */
    fun fetchLatestSharedWalletBlock(swBlockHash: ByteArray): TrustChainBlock? {
        val swBlock =
            getTrustChainCommunity().database.getBlockWithHash(swBlockHash)
                ?: return null
        val swBlocks = getTrustChainCommunity().database.getBlocksWithType(JOIN_BLOCK)
        return fetchLatestSharedWalletBlock(swBlock, swBlocks)
    }

    /**
     * Fetch the latest shared wallet block, based on a given block 'block'.
     * The unique shared wallet id is used to find the most recent block in
     * the 'sharedWalletBlocks' list.
     */
    private fun fetchLatestSharedWalletBlock(
        block: TrustChainBlock,
        fromBlocks: List<TrustChainBlock>
    ): TrustChainBlock? {
        if (block.type != JOIN_BLOCK) {
            return null
        }
        val walletId = SWJoinBlockTransactionData(block.transaction).getData().SW_UNIQUE_ID

        return fromBlocks
            .filter { it.type == JOIN_BLOCK } // make sure the blocks have the correct type!
            .filter { SWJoinBlockTransactionData(it.transaction).getData().SW_UNIQUE_ID == walletId }
            .maxByOrNull { it.timestamp.time }
    }

    /**
     * Fetch the shared wallet blocks that you are part of, based on your trustchain PK.
     */
    fun fetchLatestJoinedSharedWalletBlocks(): List<TrustChainBlock> {
        return discoverSharedWallets().filter {
            val blockData = SWJoinBlockTransactionData(it.transaction).getData()
            val userTrustchainPks = blockData.SW_TRUSTCHAIN_PKS
            userTrustchainPks.contains(myPeer.publicKey.keyToBin().toHex())
        }
    }

    /**
     * Get the public key of the one that is receiving the request
     * @return string
     */
    private fun fetchSignatureRequestReceiver(block: TrustChainBlock): String {
        if (block.type == SIGNATURE_ASK_BLOCK) {
            return SWSignatureAskTransactionData(block.transaction).getData().SW_RECEIVER_PK
        }

        if (block.type == TRANSFER_FUNDS_ASK_BLOCK) {
            return SWTransferFundsAskTransactionData(block.transaction).getData().SW_RECEIVER_PK
        }

        return "invalid-pk"
    }

    fun fetchSignatureRequestProposalId(block: TrustChainBlock): String {
        if (block.type == SIGNATURE_ASK_BLOCK) {
            return SWSignatureAskTransactionData(block.transaction).getData().SW_UNIQUE_PROPOSAL_ID
        }
        if (block.type == TRANSFER_FUNDS_ASK_BLOCK) {
            return SWTransferFundsAskTransactionData(block.transaction).getData()
                .SW_UNIQUE_PROPOSAL_ID
        }

        return "invalid-proposal-id"
    }

    /**
     * Fetch all join and transfer proposals in descending timestamp order.
     * Speed assumption: each proposal has a unique proposal ID (distinct by unique proposal id,
     * without taking the unique wallet id into account).
     */
    fun fetchProposalBlocks(): List<TrustChainBlock> {
        val joinProposals = getTrustChainCommunity().database.getBlocksWithType(SIGNATURE_ASK_BLOCK)
        val transferProposals =
            getTrustChainCommunity().database.getBlocksWithType(
                TRANSFER_FUNDS_ASK_BLOCK
            )
        return joinProposals
            .union(transferProposals)
            .filter {
                fetchSignatureRequestReceiver(it) ==
                    myPeer.publicKey.keyToBin()
                        .toHex() && !checkEnoughFavorSignatures(it)
            }
            .distinctBy { fetchSignatureRequestProposalId(it) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Fetch all DAO blocks that contain a signature. These blocks are the response of a signature request.
     * Signatures are fetched from [SIGNATURE_AGREEMENT_BLOCK] type blocks.
     */
    fun fetchProposalResponses(
        walletId: String,
        proposalId: String
    ): List<SWResponseSignatureBlockTD> {
        return getTrustChainCommunity().database.getBlocksWithType(SIGNATURE_AGREEMENT_BLOCK)
            .filter {
                val blockData = SWResponseSignatureTransactionData(it.transaction)
                blockData.matchesProposal(walletId, proposalId)
            }.map {
                SWResponseSignatureTransactionData(it.transaction).getData()
            }
    }

    /**
     * Fetch all DAO blocks that contain a negative signature. These blocks are the response of a negative signature request.
     * Signatures are fetched from [SIGNATURE_AGREEMENT_NEGATIVE_BLOCK] type blocks.
     */
    fun fetchNegativeProposalResponses(
        walletId: String,
        proposalId: String
    ): List<SWResponseNegativeSignatureBlockTD> {
        return getTrustChainCommunity().database.getBlocksWithType(
            SIGNATURE_AGREEMENT_NEGATIVE_BLOCK
        )
            .filter {
                val blockData = SWResponseNegativeSignatureTransactionData(it.transaction)
                blockData.matchesProposal(walletId, proposalId)
            }.map {
                SWResponseNegativeSignatureTransactionData(it.transaction).getData()
            }
    }

    /**
     * Given a shared wallet proposal block, calculate the signature and respond with a trust chain block.
     */
    fun joinAskBlockReceived(
        block: TrustChainBlock,
        myPublicKey: ByteArray,
        votedInFavor: Boolean,
        context: Context
    ) {
        val latestHash =
            SWSignatureAskTransactionData(block.transaction).getData()
                .SW_PREVIOUS_BLOCK_HASH
        val mostRecentSWBlock =
            fetchLatestSharedWalletBlock(latestHash.hexToBytes())
                ?: throw IllegalStateException("Most recent DAO block not found")
        val joinBlock = SWJoinBlockTransactionData(mostRecentSWBlock.transaction).getData()
        val oldTransaction = joinBlock.SW_TRANSACTION_SERIALIZED

        DAOJoinHelper.joinAskBlockReceived(oldTransaction, block, joinBlock, myPublicKey, votedInFavor, context)
    }

    /**
     * Given a shared wallet transfer fund proposal block, calculate the signature and respond with a trust chain block.
     */
    fun transferFundsBlockReceived(
        block: TrustChainBlock,
        myPublicKey: ByteArray,
        votedInFavor: Boolean,
        context: Context
    ) {
        val latestHash =
            SWTransferFundsAskTransactionData(block.transaction).getData()
                .SW_PREVIOUS_BLOCK_HASH
        val mostRecentSWBlock =
            fetchLatestSharedWalletBlock(latestHash.hexToBytes())
                ?: throw IllegalStateException("Most recent DAO block not found")
        val transferBlock = SWTransferDoneTransactionData(mostRecentSWBlock.transaction).getData()
        val oldTransaction = transferBlock.SW_TRANSACTION_SERIALIZED

        DAOTransferFundsHelper.transferFundsBlockReceived(
            oldTransaction,
            block,
            transferBlock,
            myPublicKey,
            votedInFavor,
            context
        )
    }

    /**
     * Given a proposal, check if the number of signatures required is met
     */
    fun checkEnoughFavorSignatures(block: TrustChainBlock): Boolean {
        if (block.type == SIGNATURE_ASK_BLOCK) {
            val data = SWSignatureAskTransactionData(block.transaction).getData()
            val signatures =
                ArrayList(
                    fetchProposalResponses(
                        data.SW_UNIQUE_ID,
                        data.SW_UNIQUE_PROPOSAL_ID
                    )
                )
            return data.SW_SIGNATURES_REQUIRED <= signatures.size
        }
        if (block.type == TRANSFER_FUNDS_ASK_BLOCK) {
            val data = SWTransferFundsAskTransactionData(block.transaction).getData()
            val signatures =
                ArrayList(
                    fetchProposalResponses(
                        data.SW_UNIQUE_ID,
                        data.SW_UNIQUE_PROPOSAL_ID
                    )
                )
            return data.SW_SIGNATURES_REQUIRED <= signatures.size
        }

        return false
    }

    /**
     * Check if the number of required votes are more than the number of possible votes minus the negative votes.
     */
    fun canWinJoinRequest(data: SWSignatureAskBlockTD): Boolean {
        val sw =
            discoverSharedWallets().filter { b -> SWJoinBlockTransactionData(b.transaction).getData().SW_UNIQUE_ID == data.SW_UNIQUE_ID }[0]
        val swData = SWJoinBlockTransactionData(sw.transaction).getData()
        val againstSignatures =
            ArrayList(
                fetchNegativeProposalResponses(
                    data.SW_UNIQUE_ID,
                    data.SW_UNIQUE_PROPOSAL_ID
                )
            )
        val totalVoters = swData.SW_BITCOIN_PKS
        val requiredVotes = data.SW_SIGNATURES_REQUIRED

        return requiredVotes <= totalVoters.size - againstSignatures.size
    }

    /**
     * Check if the number of required votes are more than the number of possible votes minus the negative votes.
     */
    fun canWinTransferRequest(data: SWTransferFundsAskBlockTD): Boolean {
        val againstSignatures =
            ArrayList(
                fetchNegativeProposalResponses(
                    data.SW_UNIQUE_ID,
                    data.SW_UNIQUE_PROPOSAL_ID
                )
            )
        val totalVoters = data.SW_BITCOIN_PKS
        val requiredVotes = data.SW_SIGNATURES_REQUIRED

        return requiredVotes <= totalVoters.size - againstSignatures.size
    }

    companion object {
        // Default maximum wait timeout for bitcoin transaction broadcasts in seconds
        const val DEFAULT_BITCOIN_MAX_TIMEOUT: Long = 10

        // Block type for join DAO blocks
        const val JOIN_BLOCK = "v1DAO_JOIN"

        // Block type for transfer funds (from a DAO)
        const val TRANSFER_FINAL_BLOCK = "v1DAO_TRANSFER_FINAL"

        // Block type for basic signature requests
        const val SIGNATURE_ASK_BLOCK = "v1DAO_ASK_SIGNATURE"

        // Block type for transfer funds signature requests
        const val TRANSFER_FUNDS_ASK_BLOCK = "v1DAO_TRANSFER_ASK_SIGNATURE"

        // Block type for responding to a signature request with a (should be valid) signature
        const val SIGNATURE_AGREEMENT_BLOCK = "v1DAO_SIGNATURE_AGREEMENT"

        // Block type for responding with a negative vote to a signature request with a signature
        const val SIGNATURE_AGREEMENT_NEGATIVE_BLOCK = "v1DAO_SIGNATURE_AGREEMENT_NEGATIVE"

        // FROST signature message types
        const val FROST_REQUEST_SIGNATURE = "v1FROST_REQUEST_SIGNATURE"
        const val FROST_SIGNATURE_RESULT = "v1FROST_SIGNATURE_RESULT"
        const val FROST_SIGNATURE_ERROR = "v1FROST_SIGNATURE_ERROR"

        private const val TAG = "CoinCommunity"

        private val RAFT_MEMBER_MIDS = setOf(
            "80d119411e2e6effeebc9f17683be536aed46915",
            "46d1d14be95a0aaff76284149319260819877f69",

        )

        private var raftInitialized = false


    }

    override fun walkTo(address: IPv4Address) {
//        Log.d(TAG, "Walking to address: ${address.ip}:${address.port}")
//
//        // Same IP?
//        if (address.ip == "80.112.133.253") {
//            Log.d(TAG, "Special handling for emulator WAN address with port: ${address.port}")
//        }

        super.walkTo(address)
    }

    fun addBootstrapNodes() {
        val knownAddresses = listOf(
            IPv4Address("80.112.133.253", 60569),
            IPv4Address("80.112.133.253", 41700),
            IPv4Address("10.0.2.16", 8090)
        )

        for (address in knownAddresses) {
            try {
                Log.d(TAG, "Adding bootstrap node: ${address.ip}:${address.port}")
                walkTo(address)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add bootstrap node", e)
            }
        }
    }

//    @OptIn(ExperimentalStdlibApi::class)
//    override fun onPacket(packet: Packet) {
//        val sourceAddress = packet.source
//        val data = packet.data
//
//        Log.d("RaftDebug", "收到数据包: ${data.size}字节, 来源: $sourceAddress")
//
//        if (sourceAddress.toString() == "145.94.199.74:61284"){
//            val hexDump = data.joinToString("") {
//                String.format("%02X", it.toInt() and 0xFF)
//            }
//            Log.d("RaftDebug", "数据包完整内容(十六进制): $hexDump")
//        }
//
//        // 检查前缀
//        val packetPrefix = data.copyOfRange(0, prefix.size)
//        val prefixMatch = packetPrefix.contentEquals(prefix)
//        Log.d("RaftDebug", "前缀匹配: $prefixMatch, 期望: ${prefix.toHexString()}, 实际: ${packetPrefix.toHexString()}")
//
//        if (!prefixMatch) {
//            return
//        }
//
//        // 提取消息ID
//        val msgId = data[prefix.size].toUByte().toInt()
//        Log.d("RaftDebug", "消息ID: $msgId, 已注册处理器: ${messageHandlers.containsKey(msgId)}")
//
//        super.onPacket(packet)
//    }

    /**
     * Checks if all predefined Raft members have been discovered and, if so,
     * initializes and starts the Raft module.
     * This should be called periodically or when a new peer is discovered.
     */
    fun tryToFormRaftCluster() {
        // Prevent multiple initializations
        if (isRaftInitialized()) {
            Log.d(TAG, "Raft is already initialized. Skipping cluster formation.")
            return
        }

        // We only care about peers that are designated Raft members.
        // getPeers() only returns remote peers. We must add our own peer to the list for a complete check.
        val allKnownPeers = getPeers() + myPeer
        val foundRaftPeers = allKnownPeers.filter { it.mid in RAFT_MEMBER_MIDS }

        Log.d(TAG, "Attempting to form Raft cluster. Found ${foundRaftPeers.size}/${RAFT_MEMBER_MIDS.size} members.")

        // Have we discovered all the members?
        if (foundRaftPeers.size == RAFT_MEMBER_MIDS.size) {
            Log.d(TAG, "All Raft members discovered. Initializing Raft module.")

            // The Raft module needs the full list of participants.
            // `myPeer` is implicitly part of the community delegate, but we can be explicit.
            val fullCluster = (foundRaftPeers + myPeer).distinctBy { it.mid }

            // Call the modified initialization function
            initializeRaftElection(fullCluster)
        }
    }
    /****************************************
     * **************************************
     * ***********pseudocode*****************
     * *******Not Runnable Code**************
     * **************************************
     * **************************************
     */
}
