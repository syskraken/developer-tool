package dev.franklin.devbridge.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The RSA identity this app presents to a phone. A phone that has already
 * authorised a key (for example the `adbkey` from a computer it was connected
 * to before the screen broke) will accept it with no on-screen prompt, which is
 * the whole point of [fromPem] for a phone nobody can tap.
 */
class AdbKey(private val privateKey: RSAPrivateCrtKey) {

    val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey

    /**
     * ADB's token is already a SHA-1 digest, so it is wrapped in a SHA-1
     * DigestInfo and padded without being hashed a second time.
     */
    fun signToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(privateKey)
        signature.update(SHA1_DIGEST_INFO)
        signature.update(token)
        return signature.sign()
    }

    /** The key in Android's `RSAPublicKey` struct layout, base64 encoded, NUL terminated. */
    fun publicKeyBytes(comment: String = "devbridge@android"): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(androidPublicKeyStruct())
        return "$encoded $comment".toByteArray(Charsets.US_ASCII) + 0
    }

    internal fun androidPublicKeyStruct(): ByteArray {
        val n = publicKey.modulus
        val e = publicKey.publicExponent
        val words = KEY_BITS / 32
        val two32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = -1 / n[0] mod 2^32
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toInt()
        // rr = (2^KEY_BITS)^2 mod n, used by the device for Montgomery multiplication
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val out = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(words)
        out.putInt(n0inv)
        out.put(littleEndian(n, KEY_BYTES))
        out.put(littleEndian(rr, KEY_BYTES))
        out.putInt(e.toInt())
        return out.array()
    }

    fun toPem(): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    companion object {
        const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8

        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun generate(): AdbKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4))
            return AdbKey(generator.generateKeyPair().private as RSAPrivateCrtKey)
        }

        /** Accepts the PKCS#8 PEM that `adb keygen` and `~/.android/adbkey` use. */
        fun fromPem(text: String): AdbKey {
            val body = text.lineSequence()
                .filter { !it.startsWith("-----") }
                .joinToString("") { it.trim() }
            if (text.contains("BEGIN RSA PRIVATE KEY")) {
                throw AdbException("This is a PKCS#1 key; convert it with: openssl pkcs8 -topk8 -nocrypt -in adbkey -out adbkey.pk8")
            }
            val der = try {
                Base64.getDecoder().decode(body)
            } catch (e: IllegalArgumentException) {
                throw AdbException("Not a valid PEM key", e)
            }
            val key = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (e: Exception) {
                throw AdbException("Not a valid RSA private key", e)
            }
            if (key !is RSAPrivateCrtKey) throw AdbException("RSA key is missing CRT parameters")
            if (key.modulus.bitLength() != KEY_BITS) {
                throw AdbException("ADB needs a $KEY_BITS-bit RSA key, this one is ${key.modulus.bitLength()}")
            }
            return AdbKey(key)
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val big = value.toByteArray()           // big-endian, possibly with a leading zero
            val out = ByteArray(size)
            for (i in 0 until size) {
                val src = big.size - 1 - i
                out[i] = if (src >= 0) big[src] else 0
            }
            return out
        }
    }
}
