package nl.tudelft.trustchain.currencyii.util.frost

import kotlinx.coroutines.*
import java.util.*
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.currencyii.CoinCommunity
import java.math.BigInteger

class FrostService(
    private val initParticipants: List<Peer>,
    private val initThreshold: Int,
    private val send: (Peer, ByteArray) -> Unit
) {
    private var keyGenJob: Job? = null
    private var participantId: String = UUID.randomUUID().toString()
    private var participants: List<Peer> = initParticipants
    private var threshold: Int = initThreshold
    private val sessionId = UUID.randomUUID().toString()
    private var engine: FrostKeyGenEngine? = null

    private fun getCoinCommunity(): CoinCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("CoinCommunity is not configured")
    }

    fun startKeyGen(onResult: (KeyGenResult) -> Unit) {
        keyGenJob = CoroutineScope(Dispatchers.Default).launch {
            val result = doKeyGen()
            // Unregister the engine when done
            getCoinCommunity().unregisterFrostKeyGenEngine(sessionId)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun updateParticipants(newThreshold: Int, newParticipants: List<Peer>, onResult: (KeyGenResult) -> Unit) {
        participants = newParticipants
        threshold = newThreshold
        // Restart key generation with new parameters
        stopKeyGen()
        startKeyGen(onResult)
    }

    private suspend fun doKeyGen(): KeyGenResult {
        // Instantiate a FrostKeyGenEngine
        engine = FrostKeyGenEngine(threshold, participantId, participants, sessionId, send)
        
        // Register the engine with CoinCommunity
        getCoinCommunity().registerFrostKeyGenEngine(sessionId, engine!!)
        
        // Generate keys
        return engine!!.generate()
    }

    fun stopKeyGen() {
        keyGenJob?.cancel()
        // Unregister the engine when stopping
        if (engine != null) {
            getCoinCommunity().unregisterFrostKeyGenEngine(sessionId)
        }
    }
}

data class KeyGenResult(
    val success: Boolean = false,
    val signingShare: BigInteger? = null,
    val verificationShare: BigInteger? = null,
    val groupPublicKey: BigInteger? = null,
    val participants: List<String> = emptyList(),
    val threshold: Int = 0,
    val errorMessage: String? = null
)
