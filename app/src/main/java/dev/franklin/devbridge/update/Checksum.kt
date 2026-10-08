package dev.franklin.devbridge.update

import java.io.File
import java.security.MessageDigest

object Checksum {

    /** Reads the hash out of a `sha256sum` style line: "<hex>  <filename>". */
    fun parseSha256(text: String): String? {
        for (line in text.lineSequence()) {
            val token = line.trim().substringBefore(' ').trim()
            if (token.length == 64 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return token.lowercase()
            }
        }
        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
