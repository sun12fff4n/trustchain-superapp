package nl.tudelft.trustchain.currencyii.sharedWallet

import com.google.gson.Gson
import com.google.gson.JsonObject
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.trustchain.currencyii.CoinCommunity

data class FrostSWSignatureAskBlockTD(
    var SW_UNIQUE_ID: String,
    var SW_UNIQUE_PROPOSAL_ID: String,
    var SW_TRANSACTION_SERIALIZED: String,
    var SW_PREVIOUS_BLOCK_HASH: String,
    var SW_SIGNATURES_REQUIRED: Int
)


class FrostSWSignatureAskTransactionData(data: JsonObject) : SWBlockTransactionData(
    data,
    CoinCommunity.FROST_SIGNATURE_ASK_BLOCK
) {
    fun getData(): FrostSWSignatureAskBlockTD {
        return Gson().fromJson(getJsonString(), FrostSWSignatureAskBlockTD::class.java)
    }

    constructor(
        uniqueId: String,
        transactionSerialized: String,
        previousBlockHash: String,
        requiredSignatures: Int,
        uniqueProposalId: String
    ) : this(
        SWUtil.objectToJsonObject(
            FrostSWSignatureAskBlockTD(
                uniqueId,
                uniqueProposalId,
                transactionSerialized,
                previousBlockHash,
                requiredSignatures
            )
        )
    )

    constructor(transaction: TrustChainTransaction) : this(SWUtil.parseTransaction(transaction))
}
