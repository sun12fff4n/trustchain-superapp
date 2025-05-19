package nl.tudelft.trustchain.currencyii.util.frost

import kotlinx.coroutines.runBlocking
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.ipv8.keyvault.PrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class FrostTest {
    private lateinit var peers: List<Peer>
    private val threshold = 3 // t-of-n threshold
    private val numParticipants = 5 // total participants
    private val sentMessages = mutableListOf<Triple<Peer, Peer, ByteArray>>() // sender, receiver, data
    
    @Before
    fun setUp() {
        // Create test peers
        peers = (0 until numParticipants).map { 
            val privateKey = JavaCryptoProvider().generateKey()
            Peer(privateKey)
        }
    }
    
    /**
     * Test the full key generation process with all participants
     */
    @Test
    fun testFullKeyGeneration() = runBlocking {
        // Track key generation results for each participant
        val results = mutableListOf<KeyGenResult>()
        
        // Create FrostKeyGenEngine instances for each participant
        val engines = peers.map { peer ->
            val sessionId = UUID.randomUUID().toString()
            val otherPeers = peers.filter { it != peer }
            
            // Create send function that simulates network behavior
            val send: (Peer, ByteArray) -> Unit = { receiver, data ->
                // Simulate sending message from current peer to receiver
                sentMessages.add(Triple(peer, receiver, data))
                
                // Simulate receiving the message
                val frostMessage = FrostMessage.deserialize(data).first
                
                // Find the engine for the receiver and process the message
                when (frostMessage.messageType) {
                    FrostMessageType.COMMITMENT -> {
                        val commitmentMessage = FrostCommitmentMessage.deserialize(
                            frostMessage.sessionId, 
                            frostMessage.data
                        )
                        
                        engines.find { it.first == receiver.publicKey.keyToBin().toString() }?.second
                            ?.processCommitmentMessage(
                                peer.publicKey.keyToBin().toString(),
                                commitmentMessage.commitment,
                                commitmentMessage.proof
                            )
                    }
                    FrostMessageType.VERIFICATION_SHARE -> {
                        val verificationShareMessage = FrostVerificationShareMessage.deserialize(
                            frostMessage.sessionId,
                            frostMessage.data
                        )
                        
                        engines.find { it.first == receiver.publicKey.keyToBin().toString() }?.second
                            ?.processVerificationShareMessage(
                                peer.publicKey.keyToBin().toString(),
                                verificationShareMessage.verificationShare
                            )
                    }
                }
            }
            
            // Create key generation engine
            val engine = FrostKeyGenEngine(threshold, peers, sessionId, send)
            peer.publicKey.keyToBin().toString() to engine
        }
        
        // Start key generation for each participant
        for ((peerId, engine) in engines) {
            results.add(engine.generate())
        }
        
        // Verify results
        for (result in results) {
            assertTrue("Key generation should succeed", result.success)
            assertNotNull("Signing share should not be null", result.signingShare)
            assertNotNull("Verification share should not be null", result.verificationShare)
            assertNotNull("Group public key should not be null", result.groupPublicKey)
            assertEquals("Participant count should match", numParticipants, result.participants.size)
            assertEquals("Threshold should match", threshold, result.threshold)
        }
        
        // Verify all participants generated the same group public key
        val firstGroupKey = results.first().groupPublicKey
        for (result in results.drop(1)) {
            assertEquals("All participants should generate the same group public key",
                firstGroupKey, result.groupPublicKey)
        }
    }
} 