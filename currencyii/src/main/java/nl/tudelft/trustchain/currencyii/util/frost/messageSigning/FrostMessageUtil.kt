package nl.tudelft.trustchain.currencyii.util.frost.messageSigning
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*

object FrostMessageUtil {
    fun serializeMessageWithCommitments(
        message: ByteArray,
        commitments: List<CommitmentB>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(message.size)
        output.write(message)
        output.write(commitments.size)
        for (commitment in commitments) {
            val dBytes = commitment.D.toByteArray()
            val eBytes = commitment.E.toByteArray()

            output.write(commitment.index) // 1 byte index
            output.write(dBytes.size)
            output.write(dBytes)
            output.write(eBytes.size)
            output.write(eBytes)
        }

        return output.toByteArray()
    }

    fun deserializeMessageWithCommitments(bytes: ByteArray): Pair<ByteArray, List<CommitmentB>> {
        val input = bytes.inputStream()

        val messageSize = input.read()
        val message = ByteArray(messageSize)
        input.read(message)

        val bSize = input.read()
        val commitments = mutableListOf<CommitmentB>()

        repeat(bSize) {
            val index = input.read()
            val dLen = input.read()
            val dBytes = ByteArray(dLen)
            input.read(dBytes)

            val eLen = input.read()
            val eBytes = ByteArray(eLen)
            input.read(eBytes)

            commitments.add(
                CommitmentB(
                    index = index,
                    D = BigInteger(dBytes),
                    E = BigInteger(eBytes)
                )
            )
        }

        return Pair(message, commitments)
    }


    fun serializePartial(partial: PartialSignature): ByteArray {
        val peerIdBytes = Base64.getDecoder().decode(partial.peerId)
        val zBytes = partial.z.toByteArray()

        val buffer = ByteBuffer.allocate(4 + peerIdBytes.size + 4 + zBytes.size)
        buffer.putInt(peerIdBytes.size)
        buffer.put(peerIdBytes)
        buffer.putInt(zBytes.size)
        buffer.put(zBytes)
        return buffer.array()
    }

    fun deserializePartial(data: ByteArray): PartialSignature {
        val buffer = ByteBuffer.wrap(data)

        val peerIdLen = buffer.int
        val peerIdBytes = ByteArray(peerIdLen)
        buffer.get(peerIdBytes)

        val zLen = buffer.int
        val zBytes = ByteArray(zLen)
        buffer.get(zBytes)

        return PartialSignature(
            peerId = Base64.getEncoder().encodeToString(peerIdBytes),
            z = BigInteger(zBytes)
        )
    }

    fun serializeSignedMessage(msg: SignedMessage): ByteArray {
        val rBytes = msg.R.toByteArray()
        val zBytes = msg.z.toByteArray()
        val mBytes = msg.msg

        val buffer = ByteBuffer.allocate(4 + rBytes.size + 4 + zBytes.size + 4 + mBytes.size)
        buffer.putInt(rBytes.size)
        buffer.put(rBytes)
        buffer.putInt(zBytes.size)
        buffer.put(zBytes)
        buffer.putInt(mBytes.size)
        buffer.put(mBytes)
        return buffer.array()
    }

    fun deserializeSignedMessage(data: ByteArray): SignedMessage {
        val buffer = ByteBuffer.wrap(data)

        val rLen = buffer.int
        val rBytes = ByteArray(rLen)
        buffer.get(rBytes)

        val zLen = buffer.int
        val zBytes = ByteArray(zLen)
        buffer.get(zBytes)

        val mLen = buffer.int
        val mBytes = ByteArray(mLen)
        buffer.get(mBytes)

        return SignedMessage(
            R = BigInteger(rBytes),
            z = BigInteger(zBytes),
            msg = mBytes
        )
    }


}
