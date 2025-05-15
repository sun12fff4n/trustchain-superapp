package nl.tudelft.trustchain.currencyii.util.frost

import kotlinx.coroutines.*

import nl.tudelft.ipv8.Peer

class FrostService(private val initParticipants: List<Peer>, private val initThreshold: Int, private val send: (Peer, ByteArray) -> Unit) {

    private var keyGenJob: Job? = null
    private var participants: List<Peer> = initParticipants
    private var threshold: Int = initThreshold


    fun startKeyGen(onResult: (KeyGenResult) -> Unit) {
        keyGenJob = CoroutineScope(Dispatchers.Default).launch {
            val result = doKeyGen()
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

    private fun doKeyGen(): KeyGenResult {
        // actual key generation logic
        val keyGenEngine = FrostKeyGenEngine(threshold, participants, send)
        return keyGenEngine.generate()
    }

    fun stopKeyGen() {
        keyGenJob?.cancel()
    }
}

data class KeyGenResult(val success: Boolean = true)
