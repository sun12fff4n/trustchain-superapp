package nl.tudelft.trustchain.currencyii

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.trustchain.currencyii.sharedWallet.*
import nl.tudelft.trustchain.currencyii.util.DAOCreateHelper
import nl.tudelft.trustchain.currencyii.util.DAOJoinHelper
import nl.tudelft.trustchain.currencyii.util.DAOTransferFundsHelper
import nl.tudelft.trustchain.currencyii.util.frost.FrostCommitmentMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostJoinProposalToSA
import nl.tudelft.trustchain.currencyii.util.frost.FrostKeyGenEngine
import nl.tudelft.trustchain.currencyii.util.frost.FrostMessageType
import nl.tudelft.trustchain.currencyii.util.frost.FrostNoncesToSAMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostPayload
import nl.tudelft.trustchain.currencyii.util.frost.FrostPreProcessingEngine
import nl.tudelft.trustchain.currencyii.util.frost.FrostSiginingEngine
import nl.tudelft.trustchain.currencyii.util.frost.FrostSigningResponseToSAMessage
import nl.tudelft.trustchain.currencyii.util.frost.FrostVerificationShareMessage
import org.ethereum.geth.BigInt
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64
import java.util.LinkedList
import java.util.UUID

interface FrostSendDelegate {
    fun frostSend(peer: Peer, data: ByteArray): Unit
}

@Suppress("UNCHECKED_CAST")
class CoinCommunity constructor(serviceId: String = "02313685c1912a141279f8248fc8db5899c5df5b") : Community(), FrostSendDelegate {
    override val serviceId = serviceId

    // Map to store active FROST key generation engines by session ID
    // wallet Id -> SessionId -> FrostKeyGenEngine
    private val activeKeyGenEngines = ConcurrentHashMap<String, ConcurrentHashMap<String, FrostKeyGenEngine>>()
    private val activePreProcessingEngine = ConcurrentHashMap<String, ConcurrentHashMap<String, FrostPreProcessingEngine>>()

    public var currentFrostKeyGenEngine: FrostKeyGenEngine? = null
    public var currentFrostPreprocessingEngine: FrostPreProcessingEngine? = null

    // record the id of message we have processed
    private var processedMessages = HashSet<String>()

    // send function for frost
    override fun frostSend(peer: Peer, data: ByteArray): Unit {
        return send(peer, data)
    }

    // Register a new FROST key generation engine
    fun registerFrostKeyGenEngine(walletId: String, sessionId: String, engine: FrostKeyGenEngine) {
        if (!activeKeyGenEngines.containsKey(walletId)) {
            activeKeyGenEngines[walletId] = ConcurrentHashMap()
        }
        activeKeyGenEngines[walletId]?.set(sessionId, engine)
    }

    fun registerFrostPreProcessingEngine(walletId: String, sessionId: String, engine: FrostPreProcessingEngine) {
        if (!activePreProcessingEngine.containsKey(walletId)) {
            activePreProcessingEngine[walletId] = ConcurrentHashMap()
        }
        activePreProcessingEngine[walletId]?.set(sessionId, engine)
    }

    // Unregister a FROST key generation engine
    fun unregisterFrostKeyGenEngine(walletId: String, sessionId: String) {
        activeKeyGenEngines[walletId]?.remove(sessionId)
    }

    fun unregisterFrostPreProcessingEngine(walletId: String, sessionId: String) {
        activePreProcessingEngine[walletId]?.remove(sessionId)
    }

    private fun getTrustChainCommunity(): TrustChainCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("TrustChainCommunity is not configured")
    }

    private val daoCreateHelper = DAOCreateHelper()
    private val daoJoinHelper = DAOJoinHelper()
    private val daoTransferFundsHelper = DAOTransferFundsHelper()

    val frostKeyGenEngineSendFunctemplate = { }
    val frostKeyGenEngineBroadcastFunctemplate = { walletId: String, sessionId: String, payload: Serializable ->
        Log.i("Frost", "Broadcasting....")
        frostBroadcasting(walletId, sessionId, payload)
    }
    suspend fun frostKeyGenEngineLaunchDKGFetcher(walletId: String, sessionId: String) {
        while (activeKeyGenEngines[walletId]?.containsKey(sessionId) == true) {
            val frostKeyGenEngine = activeKeyGenEngines[walletId]?.get(sessionId)!!
            try {
                val broadcastMsgs = fetchFrostBroadcastingBlock(walletId, sessionId)
                Log.i("FrostMonitor", "Fetched ${broadcastMsgs.size} broadcasting blocks for session $sessionId")
                for (msg in broadcastMsgs) {
                    if (processedMessages.contains(msg.SW_MESSAGE_ID)) {
                        continue
                    }
                    Log.i("Frost", "carried data is ${msg.SW_FROST_DATA}")
                    val frostPayload = FrostPayload.deserialize(msg.SW_FROST_DATA, 0).first
                    Log.i("Frost", "deserialized data is ${frostPayload.messageType}")
                    if (frostPayload.messageType == FrostMessageType.COMMITMENT) {
                        val commitmentMessage = FrostCommitmentMessage.deserialize(sessionId, frostPayload.data)
                        frostKeyGenEngine.processCommitmentMessage(msg.SW_BROADCASTER_ID, commitmentMessage.commitment, commitmentMessage.proof)
                        processedMessages.add(msg.SW_MESSAGE_ID)
                    } else if (frostPayload.messageType == FrostMessageType.VERIFICATION_SHARE) {
                        val verificationShareMessage = FrostVerificationShareMessage.deserialize(sessionId, frostPayload.data)
                        frostKeyGenEngine.processVerificationShareMessage(msg.SW_BROADCASTER_ID, verificationShareMessage.verificationShare)
                        processedMessages.add(msg.SW_MESSAGE_ID)
                    }
                }
            } catch (e: Exception) {
                Log.e("FrostMonitor", "Failed to fetch broadcasting blocks: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    val frostPreprocessingSendToLeaderFuncTemplate = { walletId: String, sessionId: String, leaderId: String, payload: Serializable ->
        Log.i("Frost", "Send to the leader...")
        frostBroadcasting(walletId, sessionId, payload, leaderId)
    }

    fun frostBroadcasting(walletId: String, sessionId: String, payload: Serializable, leaderId: String = "") {
        val transactionData = FrostBroadcastingTransactionData(
            walletId,
            sessionId,
            payload.serialize(),
            Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin()),
            sendTo = leaderId
        )
        getTrustChainCommunity().createBroadcastingBlock(
            transactionData.getTransactionData(),
            myPeer.publicKey.keyToBin(),
            FROST_BROADCASTING_BLOCK
        )
    }
    fun frostProprocessingEngineLaunchDKGFetcher(walletId: String, sessionId: String, participantNum: Int) {
        val frostPreProcessingEngine = activePreProcessingEngine[walletId]?.get(sessionId)!!
        while (!frostPreProcessingEngine.ifCollectAllNonces(participantNum)) {
            try {
                val broadcastMsgs = fetchFrostBroadcastingBlock(walletId, sessionId)
                Log.i("Frost", "Fetched ${broadcastMsgs.size} broadcasting blocks for session $sessionId")
                for (msg in broadcastMsgs) {
                    if (processedMessages.contains(msg.SW_MESSAGE_ID)) {
                        continue
                    }
                    // processing logic, e.g. desereialize
                    val frostPayload = FrostPayload.deserialize(msg.SW_FROST_DATA, 0).first
                    if (frostPayload.messageType == FrostMessageType.FROST_NONCEPAIRS_TO_SA) {
                        val frostNoncesToSAMessage = FrostNoncesToSAMessage.deserialize(frostPayload.data)
                        frostPreProcessingEngine.processNonceListMessage(msg.SW_BROADCASTER_ID, frostNoncesToSAMessage.noncePairs)
                        processedMessages.add(msg.SW_MESSAGE_ID)
                        Log.i("FrostPreProc", "Received nonce list ${frostNoncesToSAMessage.noncePairs} from ${msg.SW_BROADCASTER_ID}")
                    }
                }
            } catch (e: Exception) {
                Log.e("FrostMonitor", "Failed to fetch broadcasting blocks: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    var frostSigningEngine :FrostSiginingEngine? = null;

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
        Log.i("Frost", "I am ${Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin())}")
        val ret = daoCreateHelper.createBitcoinGenesisWallet(
            myPeer,
            entranceFee,
            threshold,
            context
        )

        val walletId = ret.getData().SW_UNIQUE_ID
        val sessionId = UUID.randomUUID().toString()
        val frostKeyGenEngine = FrostKeyGenEngine(
            threshold = threshold,
            participantId = Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin()),
            participants = LinkedList<Peer>().apply { add(myPeer) },
            sessionId = sessionId,
            send = { peer, data ->  },
            broadcast = {
                payload -> frostKeyGenEngineBroadcastFunctemplate(
                    walletId, sessionId, payload
                )
            }
        )
        this.currentFrostKeyGenEngine = frostKeyGenEngine
        frostKeyGenEngine.initialize()

        // the only participant, namely the creator, is ofc the leader.
        val frostPreprocessingEngine = FrostPreProcessingEngine(
            walletId = walletId,
            sessionId = sessionId,
            leaderId = Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin()),
            isLeader = true,
            participantIndex = 0,
            pi = 10,
            broadcast = { payload ->
                frostPreprocessingSendToLeaderFuncTemplate(
                    walletId, sessionId,
                    Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin()),
                    payload
                )
            }
        )
        registerFrostPreProcessingEngine(walletId, sessionId, frostPreprocessingEngine)
        this.currentFrostPreprocessingEngine = frostPreprocessingEngine

        this.frostSigningEngine = FrostSiginingEngine(
            isLeader = true,
            participantIndex = 0,
            pi = 10,
            walletId = walletId,
            sessionId = sessionId,
            leaderId = Base64.getEncoder().encodeToString(myPeer.publicKey.keyToBin()),
            broadcast = {
                payload -> frostBroadcasting( walletId, sessionId, payload )
            },
            threshold = threshold,
            myPeer = myPeer,
        )

        frostKeyGenEngine.sessionId = ret.getData().SW_UNIQUE_ID
        registerFrostKeyGenEngine(walletId, sessionId, frostKeyGenEngine)
        CoroutineScope(Dispatchers.Default).launch {
            frostKeyGenEngine.generate()
            unregisterFrostKeyGenEngine(walletId, sessionId)
            frostPreprocessingEngine.generate()
        }
        CoroutineScope(Dispatchers.Default).launch {
            frostKeyGenEngineLaunchDKGFetcher(walletId, sessionId)
        }

        CoroutineScope(Dispatchers.Default).launch {
            frostProprocessingEngineLaunchDKGFetcher(walletId, sessionId, 1)
            Log.i("Frost", "I try to migrate this")
            frostSigningEngine!!.setStorednNonces(frostPreprocessingEngine.storedNonces)
            Log.i("Frost", "I finished to migrate this, now i have ${frostSigningEngine!!.storedNonces.size}")
        }

        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                var exitLoop = false
                Thread.sleep(1000)

                Log.i("FrostMonitor", "I am looking for ${walletId}")
                val broadcastMsgs = fetchFrostBroadcastingBlock(walletId, "IDONTKNOW")
                for (msg in broadcastMsgs) {
                    if (msg.SW_UNIQUE_ID != walletId) {
                        continue
                    }
                    if (msg.SW_SESSION_ID != "IDONTKNOW") {
                        continue
                    }
                    val frostPayload = FrostPayload.deserialize(msg.SW_FROST_DATA, 0).first
                    Log.i("FrostMonitor", "Fetched broadcasting blocks for wallet Id ${msg.SW_UNIQUE_ID} and session ${msg.SW_SESSION_ID}")

                    val joinerID = FrostJoinProposalToSA.deserialize(frostPayload.data).peerId
                    frostSigningEngine!!.sign(joinerID)


                    CoroutineScope(Dispatchers.Default).launch {
                        while (!frostSigningEngine!!.signed) {
                            val msgs = fetchFrostBroadcastingBlock(walletId, sessionId)
                            Log.i("FrostSigining", "I fetched ${msgs.size} broadcasting blocks for session $sessionId")
                            for (msg in msgs) {
                                if (msg.SW_UNIQUE_ID != walletId) {
                                    continue
                                }
                                val frostPayload = FrostPayload.deserialize(msg.SW_FROST_DATA, 0).first
                                if (frostPayload.messageType != FrostMessageType.FROST_SIGNING_ZI_TO_SA) {
                                    continue
                                }
                                val frostZiToSA = FrostSigningResponseToSAMessage.deserialize(frostPayload.data)
                                Log.i("FrostSigining", "I received ${frostZiToSA.z_i} from participant: {${frostZiToSA.participantIndex}")
                                frostSigningEngine!!.onReceivedZiFromParticipant(
                                    frostZiToSA.participantIndex,
                                    frostZiToSA.z_i,
                                    joinerID
                                )
                            }
                            Thread.sleep(1000)
                        }
                    }

                    exitLoop = true
                    break
                }
                if (exitLoop) {
                    break
                }
            }
        }

        return ret
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
     * 2.1.frost Send a proposal on the trust chain to join a shared wallet and to collect Frost signatures.
     * The proposal is a serialized bitcoin join transaction.
     * **NOTE** the latest walletBlockData should be given, otherwise the serialized transaction is invalid.
     * @param walletBlock - the latest (that you know of) shared wallet block.
     */
    fun proposeJoinWalletFrost(walletBlock: TrustChainBlock): FrostSWSignatureAskTransactionData {
        return daoJoinHelper.proposeJoinWalletFrost(myPeer, walletBlock)
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
     * 2.2.frost Commit the join wallet transaction on the bitcoin blockchain and broadcast the result on trust chain.
     *
     * Note:
     * There should be enough sufficient signatures, based on the multisig wallet data.
     * @throws - exceptions if something goes wrong with creating or broadcasting bitcoin transaction.
     * @param walletBlockData - TrustChainTransaction, describes the wallet that is joined
     * @param blockData - SWSignatureAskBlockTD, the block where the other users are voting on
     * @param responses - the positive responses for your request to join the wallet
     */
    fun joinBitcoinWalletFrost(
        walletBlockData: TrustChainTransaction,
        blockData: FrostSWSignatureAskBlockTD,
        frostSignature: BigInteger,
        context: Context
    ) {
        daoJoinHelper.joinBitcoinWalletFrost(
            myPeer,
            walletBlockData,
            blockData,
            frostSignature,
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

        if (block.type == FROST_SIGNATURE_ASK_BLOCK) {
            return "any"
        }

        if (block.type == FROST_BROADCASTING_BLOCK) {
            return FrostBroadcastingTransactionData(block.transaction).getData().SW_SEND_TO
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
        if (block.type == FROST_SIGNATURE_ASK_BLOCK) {
            return FrostSWSignatureAskTransactionData(block.transaction).getData()
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
        val frostJoinProposals = getTrustChainCommunity().database.getBlocksWithType(
            FROST_SIGNATURE_ASK_BLOCK
        )
        return joinProposals
            .union(transferProposals)
            .union(frostJoinProposals)
            .filter {
                fetchSignatureRequestReceiver(it) ==
                    myPeer.publicKey.keyToBin()
                        .toHex() && !checkEnoughFavorSignatures(it)
                    ||
                fetchSignatureRequestReceiver(it) == "any"
            }
            .distinctBy { fetchSignatureRequestProposalId(it) }
            .sortedByDescending { it.timestamp }
    }

    fun fetchBroadcastingBlocks(sendToMe: Boolean = false): List<TrustChainBlock> {
//        return getTrustChainCommunity().database.getRecentBlocks(20)

        return getTrustChainCommunity().database.getBlocksWithType(FROST_BROADCASTING_BLOCK)
//        val broadcastingBlocks = getTrustChainCommunity().database.getBlocksWithType(FROST_BROADCASTING_BLOCK)
//
//        return broadcastingBlocks
//        return broadcastingBlocks.filter {
//            !sendToMe || myPeer.publicKey.keyToBin().toHex() == fetchSignatureRequestReceiver(it)
//        }
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
     * Fetch all DAO blocks that contain a Frost signature. These blocks are the response of a signature request.
     * Signatures are fetched from [FROST_SIGNATURE_AGREEMENT_BLOCK] type blocks.
     */
    fun fetchProposalFrostResponse(
        walletId: String,
        proposalId: String
    ): FrostSWResponseSignatureBlockTD? {
        val res = getTrustChainCommunity().database.getBlocksWithType(FROST_SIGNATURE_AGREEMENT_BLOCK)
            .filter {
                val blockData = FrostSWResponseSignatureTransactionData(it.transaction)
                blockData.matchesProposal(walletId, proposalId)
            }
            .map {
                FrostSWResponseSignatureTransactionData(it.transaction).getData()
            }
        if (res.size == 0) {
            return null
        }
        return res[0]
    }

    fun fetchFrostBroadcastingBlock(
        walletId: String,
        sessionId: String,
    ): List<FrostBradcastingBlockTD> {
        return getTrustChainCommunity().database.getBlocksWithType(FROST_BROADCASTING_BLOCK)
            .filter {
                val blockData = FrostBroadcastingTransactionData(it.transaction)
                blockData.matchesSession(walletId, sessionId)
            }
            .map {
                FrostBroadcastingTransactionData(it.transaction).getData()
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

        // Block type for frost signature requests
        const val FROST_SIGNATURE_ASK_BLOCK = "v1DAO_ASK_FROST_SIGNATURE"

        // Block type for transfer funds signature requests
        const val TRANSFER_FUNDS_ASK_BLOCK = "v1DAO_TRANSFER_ASK_SIGNATURE"

        // Block type for responding to a signature request with a (should be valid) signature
        const val SIGNATURE_AGREEMENT_BLOCK = "v1DAO_SIGNATURE_AGREEMENT"

        // Block type for responding to a frost signature request with a (should be valid) signature
        const val FROST_SIGNATURE_AGREEMENT_BLOCK = "v1DAO_FROST_SIGNATURE_AGREEMENT"

        // Block Type for Broadcasting Message for frost.
        const val FROST_BROADCASTING_BLOCK = "v1DAO_FROST_BROADCASTING"

        // Block type for responding with a negative vote to a signature request with a signature
        const val SIGNATURE_AGREEMENT_NEGATIVE_BLOCK = "v1DAO_SIGNATURE_AGREEMENT_NEGATIVE"
    }
}
