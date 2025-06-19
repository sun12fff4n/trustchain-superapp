package nl.tudelft.trustchain.currencyii.util.frost

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.Base64
import java.util.UUID

class FrostTest {
    private lateinit var peers: List<Peer>
    private val threshold = 3 // t-of-n threshold
    private val numParticipants = 5 // total participants
    private val sentMessages = mutableListOf<Triple<Peer, Peer, ByteArray>>() // sender, receiver, data

    @BeforeEach
    fun setUp() {
        // Create test peers
        peers = (0 until numParticipants).map {
            val privateKey = JavaCryptoProvider.generateKey()
            Peer(privateKey)
        }
    }

    /**
     * Test the full key generation process with all participants
     */
    @Test
    fun testFullKeyGeneration() = runBlocking {
        coroutineScope {
            val engineMap = mutableMapOf<String, FrostKeyGenEngine>()
            val peerId: (Peer) -> String = { p ->
                Base64.getEncoder().encodeToString(p.publicKey.keyToBin())
            }
            val send: (Peer, ByteArray) -> Unit = send@{ sender, data ->
                val frostMessage = FrostMessage.deserialize(data).first
                val senderId = peerId(sender)
                peers.forEach { peer ->
                    val receiverId = peerId(peer)
                    if (receiverId == senderId) return@forEach

                    val engine = engineMap[receiverId] ?: return@forEach
                    when (frostMessage.messageType) {
                        FrostMessageType.COMMITMENT -> {
                            val msg = FrostCommitmentMessage.deserialize(frostMessage.sessionId, frostMessage.data)
                            GlobalScope.launch {
                                engine.processCommitmentMessage(senderId, msg.commitment, msg.proof)
                            }
                        }

                        FrostMessageType.VERIFICATION_SHARE -> {
                            val msg = FrostVerificationShareMessage.deserialize(frostMessage.sessionId, frostMessage.data)
                            GlobalScope.launch {
                                engine.processVerificationShareMessage(senderId, msg.verificationShare)
                            }
                        }
                    }
                }
            }
            val broadcast: (ByteArray) -> Unit = send@{ data ->
                val frostMessage = FrostMessage.deserialize(data).first
                peers.forEach { peer ->
                    val receiverId = peerId(peer)
                    val engine = engineMap[receiverId] ?: return@forEach
                }
            }


            val sessionId = UUID.randomUUID().toString()
            println(sessionId.length.toString())
            for (participant in peers) {
                val participantId = peerId(participant)
                engineMap[participantId] = FrostKeyGenEngine(threshold, participantId, peers, sessionId, send)
            }

            val results = engineMap.map { (_, engine) ->
                async {
                    engine.generate()
                }
            }.awaitAll()

            // Verify results
            for ((index, result) in results.withIndex()) {
                assertTrue(result.success, "Key generation should succeed for participant $index")
                assertNotNull(result.signingShare, "Signing share should not be null for participant $index")
                assertNotNull(result.verificationShare, "Verification share should not be null for participant $index")
                assertNotNull(result.groupPublicKey, "Group public key should not be null for participant $index")
                assertEquals(numParticipants, result.participants.size, "Participant count should match for participant $index")
                assertEquals(threshold, result.threshold, "Threshold should match for participant $index")
            }

            // Verify all participants generated the same group public key
            val firstGroupKey = results.first().groupPublicKey
            for ((index, result) in results.drop(1).withIndex()) {
                assertEquals(firstGroupKey, result.groupPublicKey, "Group public key mismatch for participant ${index + 1}")
            }
        }
    }
}
