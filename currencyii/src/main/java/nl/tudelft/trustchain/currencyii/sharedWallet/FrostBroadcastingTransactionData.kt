package nl.tudelft.trustchain.currencyii.sharedWallet

import com.google.gson.Gson
import com.google.gson.JsonObject
import nl.tudelft.ipv8.attestation.trustchain.TrustChainTransaction
import nl.tudelft.trustchain.currencyii.CoinCommunity


data class FrostBradcastingBlockTD(
    var SW_UNIQUE_ID: String,
    var SW_SESSION_ID: String,
    var SW_MESSAGE_ID: String,
    var SW_FROST_DATA: ByteArray,
    var SW_BROADCASTER_ID: String,
    var SW_SEND_TO: String,
)


class FrostBroadcastingTransactionData (data: JsonObject) : SWBlockTransactionData (
    data, CoinCommunity.FROST_BROADCASTING_BLOCK
){
    fun getData(): FrostBradcastingBlockTD {
        return Gson().fromJson(getJsonString(), FrostBradcastingBlockTD::class.java)
    }

    fun matchesSession(
        walletId: String,
        sessionId: String
    ): Boolean {
        val data = getData()
        return data.SW_SESSION_ID == sessionId
//        return data.SW_UNIQUE_ID == walletId && data.SW_SESSION_ID == sessionId
    }

    constructor(
        uniqueId: String,
        sessionId: String,
        data: ByteArray,
        broadcaster: String,
        sendTo: String = "",
    ) : this(
        SWUtil.objectToJsonObject(
            FrostBradcastingBlockTD(
                uniqueId,
                sessionId,
                java.util.UUID.randomUUID().toString(),
                data,
                broadcaster,
                sendTo
            )
        )
    )

    constructor(transaction: TrustChainTransaction) : this(SWUtil.parseTransaction(transaction))
}
