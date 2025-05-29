package nl.tudelft.trustchain.currencyii.util.frost

import java.math.BigInteger

object FrostConstants {
    // A large prime (in a production environment, use a cryptographically secure prime)
    val p: BigInteger = BigInteger("170141183460469231731687303715884105727") // prime
    // A generator of the group (in a production environment, use a proper generator)
    val g: BigInteger = BigInteger.valueOf(3)
    // Modulus
    val n: BigInteger = p.subtract(BigInteger.ONE) // n = p - 1
    // Timeout for waiting for responses (in ms)
    const val DEFAULT_TIMEOUT = 30000L // 30 seconds
}
