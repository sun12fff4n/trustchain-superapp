package nl.tudelft.trustchain.debug

class PeerItem(
    val ip: String,
    val lastResponseTime: String,
    val isTimeOut: Boolean,
    val publicKey: String
)
