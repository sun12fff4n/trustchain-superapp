package nl.tudelft.trustchain.currencyii.util.frost

import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.trustchain.currencyii.CoinCommunity
import java.math.BigInteger


// I want to discard this
class FrostService(
    private val initParticipants: List<Peer>,
    private val initThreshold: Int,
    private val send: (Peer, Serializable) -> Unit,
    private val broadcast: (Serializable) -> Unit,
) {
    var keyGenJob: Job? = null
    var participantId: String = UUID.randomUUID().toString()
    var participants: List<Peer> = initParticipants
    var threshold: Int = initThreshold
    val sessionId = UUID.randomUUID().toString()
    var engine: FrostKeyGenEngine? = null

    private fun getCoinCommunity(): CoinCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("CoinCommunity is not configured")
    }

    fun startKeyGen(onResult: (KeyGenResult) -> Unit) {
        Log.i("Frost", "restart!!")
        keyGenJob = CoroutineScope(Dispatchers.Default).launch {
            Log.i("Frost", "really really restart!!")
            val result = doKeyGen()
            // Unregister the engine when done
//            getCoinCommunity().unregisterFrostKeyGenEngine(sessionId)
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
        engine = FrostKeyGenEngine(threshold, participantId, participants, sessionId, send, broadcast)


        return engine!!.generate()
    }

    fun stopKeyGen() {
        keyGenJob?.cancel()
        // Unregister the engine when stopping
        if (engine != null) {
//            getCoinCommunity().unregisterFrostKeyGenEngine(sessionId)
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
