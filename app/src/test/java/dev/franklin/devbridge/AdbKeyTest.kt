package dev.franklin.devbridge

import dev.franklin.devbridge.adb.AdbException
import dev.franklin.devbridge.adb.AdbKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature

class AdbKeyTest {

    private fun leInt(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun leBig(b: ByteArray, off: Int, len: Int) =
        BigInteger(1, b.copyOfRange(off, off + len).reversedArray())

    @Test
    fun publicKeyStructMatchesAndroidLayout() {
        val key = AdbKey.generate()
        val s = key.androidPublicKeyStruct()
        assertEquals(4 + 4 + 256 + 256 + 4, s.size)
        assertEquals(64, leInt(s, 0))

        val n = key.publicKey.modulus
        assertEquals(n, leBig(s, 8, 256))

        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = BigInteger.valueOf(leInt(s, 4).toLong() and 0xFFFFFFFFL)
        // n0inv * n[0] == -1 (mod 2^32)
        assertEquals(two32.subtract(BigInteger.ONE), n0inv.multiply(n.mod(two32)).mod(two32))

        val rr = leBig(s, 8 + 256, 256)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        assertEquals(65537, leInt(s, 8 + 512))
    }

    @Test
    fun tokenSignatureVerifiesAsSha1DigestInfo() {
        val key = AdbKey.generate()
        val token = ByteArray(20) { (it + 1).toByte() }
        val sig = key.signToken(token)
        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(key.publicKey)
        verifier.update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14))
        verifier.update(token)
        assertTrue(verifier.verify(sig))
    }

    @Test
    fun pemRoundTripKeepsIdentity() {
        val key = AdbKey.generate()
        val again = AdbKey.fromPem(key.toPem())
        assertEquals(key.publicKey.modulus, again.publicKey.modulus)
    }

    @Test
    fun rejectsGarbageAndPkcs1Keys() {
        try { AdbKey.fromPem("hello"); fail() } catch (e: AdbException) { /* expected */ }
        try { AdbKey.fromPem("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"); fail() } catch (e: AdbException) {
            assertTrue(e.message!!.contains("PKCS#1"))
        }
    }
}
