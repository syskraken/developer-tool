package dev.franklin.devbridge

import dev.franklin.devbridge.update.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChecksumTest {

    @Test
    fun parsesSha256sumLines() {
        val hash = "a".repeat(64)
        assertEquals(hash, Checksum.parseSha256("$hash  devbridge-1.1.0.apk\n"))
        assertNull(Checksum.parseSha256("not a hash"))
        assertEquals(hash, Checksum.parseSha256("\n\n${hash.uppercase()}  f"))
    }

    @Test
    fun hashesAFile() {
        val file = File.createTempFile("devbridge", ".bin")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksum.sha256(file))
        } finally {
            file.delete()
        }
    }
}
