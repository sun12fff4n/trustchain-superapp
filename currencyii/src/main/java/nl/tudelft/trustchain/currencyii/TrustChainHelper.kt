package nl.tudelft.trustchain.currencyii

import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.ipv8.attestation.trustchain.store.UserInfo

/**
 * A helper class for interacting with TrustChain.
 */
class TrustChainHelper(
    private val trustChainCommunity: TrustChainCommunity
) {
    /**
     * Returns a list of users and their chain lengths.
     */
    fun getUsers(): List<UserInfo> {
        return trustChainCommunity.database.getUsers()
    }

    /**
     * Returns the number of blocks stored for the given public key.
     */
    fun getStoredBlockCountForUser(publicKeyBin: ByteArray): Long {
        return trustChainCommunity.database.getBlockCount(publicKeyBin)
    }

    /**
     * Returns a peer by its public key if found.
     */
    fun getPeerByPublicKeyBin(publicKeyBin: ByteArray): Peer? {
        return trustChainCommunity.network.getVerifiedByPublicKeyBin(publicKeyBin)
    }

    /**
     * Crawls the chain of the specified peer.
     */
    suspend fun crawlChain(peer: Peer) {
        trustChainCommunity.crawlChain(peer)
    }

    /**
     * Creates a new proposal block, using a text message as the transaction content.
     */
    fun createProposalBlock(
        message: String,
        publicKey: ByteArray,
        blockType: String = "demo_block"
    ) {
        val transaction = mapOf("message" to message)
        trustChainCommunity.createProposalBlock(blockType, transaction, publicKey)
    }

    /**
     * Creates a new proposal block, using a text message as the transaction content.
     */
    fun createProposalBlock(
        transaction: TrustChainTransaction,
        publicKey: ByteArray,
        blockType: String = "demo_block"
    ): TrustChainBlock {
        return trustChainCommunity.createProposalBlock(blockType, transaction, publicKey)
    }

    /**
     * Create a new Frost Block, using a broadcasted message as transaction context
     */
    fun createFrostBroadcastBlock(
        message: String,
        publicKey: ByteArray,
        blockType: String = "v1DAO_FROST_BROADCASTING"
    ) {
        val transaction = mapOf("message" to message)
        trustChainCommunity.createBroadcastingBlock(transaction, publicKey, blockType)
    }

    /**
     * Create a new Frost Block, using a broadcasted message as transaction context
     */
    fun createFrostBroadcastBlock(
        transaction: TrustChainTransaction,
        publicKey: ByteArray,
        blockType: String = "v1DAO_FROST_BROADCASTING"
    ): TrustChainBlock  {
        return trustChainCommunity.createBroadcastingBlock(transaction, publicKey, blockType)
    }
        /**
     * Creates an agreement block to a specified proposal block, using a custom transaction.
     */
    fun createAgreementBlock(
        link: TrustChainBlock,
        transaction: TrustChainTransaction
    ) {
        trustChainCommunity.createAgreementBlock(link, transaction)
    }

    /**
     * Returns a list of blocks in which the specified user is participating as a sender or
     * a receiver.
     */
    fun getChainByUser(publicKeyBin: ByteArray): List<TrustChainBlock> {
        return trustChainCommunity.database.getMutualBlocks(publicKeyBin, 1000)
    }
}
