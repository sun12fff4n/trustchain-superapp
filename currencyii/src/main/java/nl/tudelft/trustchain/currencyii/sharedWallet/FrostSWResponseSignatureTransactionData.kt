package nl.tudelft.trustchain.currencyii.sharedWallet

import com.google.gson.Gson
import com.google.gson.JsonObject
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.trustchain.currencyii.CoinCommunity

data class FrostSWResponseSignatureBlockTD(
    var SW_UNIQUE_ID: String,
    var SW_UNIQUE_PROPOSAL_ID: String,
    var SW_FROSTSIGNATURE_SERIALIZED: String,
    var SW_BITCOIN_PK: String,
    var SW_NONCE: String
)

class FrostSWResponseSignatureTransactionData (data: JsonObject) : SWBlockTransactionData(
    data,
    CoinCommunity.FROST_SIGNATURE_AGREEMENT_BLOCK
) {
    fun getData(): FrostSWResponseSignatureBlockTD {
        return Gson().fromJson(getJsonString(), FrostSWResponseSignatureBlockTD::class.java)
    }

    fun matchesProposal(
        walletId: String,
        proposalId: String
    ): Boolean {
        val data = getData()
        return data.SW_UNIQUE_ID == walletId && data.SW_UNIQUE_PROPOSAL_ID == proposalId
    }

    constructor(
        uniqueId: String,
        uniqueProposalId: String,
        frostSignatureSerialized: String,
        bitcoinPk: String,
        nonce: String
    ) : this(
        SWUtil.objectToJsonObject(
            FrostSWResponseSignatureBlockTD(
                uniqueId,
                uniqueProposalId,
                frostSignatureSerialized,
                bitcoinPk,
                nonce
            )
        )
    )

    constructor(transaction: TrustChainTransaction) : this(SWUtil.parseTransaction(transaction))
}
