package dev.brahmkshatriya.echo.extension

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SubDecryptor(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    fun decryptToString(subUrl: String): String {
        val subHeaders = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .build()

        val request = Request.Builder()
            .url(subUrl)
            .headers(subHeaders)
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to fetch subtitle from $subUrl (code ${response.code})")

        val subtitleData = response.body?.string() ?: throw IOException("Empty subtitle body")

        val chunks = subtitleData.split(CHUNK_REGEX)
            .filter { it.isNotBlank() }
            .map { it.trim() }

        var decryptedCount = 0
        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.lines()
            val text = parts.drop(1)
            val d = text.joinToString("\n") { line ->
                val decryptedLine = decrypt(line)
                if (decryptedLine.isNotBlank()) decryptedCount++
                decryptedLine
            }

            "${index + 1}\n${parts.first()}\n$d"
        }.joinToString("\n\n")

        if (chunks.isNotEmpty() && decryptedCount == 0) {
            throw IOException("Failed to decrypt subtitles: no cues could be decrypted")
        }

        return decrypted
    }

    suspend fun getSubtitles(subUrl: String): String {
        val decrypted = decryptToString(subUrl)

        val file = File.createTempFile("kisskh_subs_", ".srt").apply {
            deleteOnExit()
            writeText(decrypted)
        }

        return file.toURI().toString()
    }

    private fun decrypt(encryptedB64: String): String {
        if (encryptedB64.isBlank()) return ""
        for (pair in KEY_IV_PAIRS) {
            try {
                return decryptWithKeyIv(pair.first, pair.second, encryptedB64)
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedB64: String): String {
        val encryptedBytes = Base64.getDecoder().decode(encryptedB64.trim())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    companion object {
        private val CHUNK_REGEX = Regex("^\\d+$", RegexOption.MULTILINE)

        private const val KEY = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"

        private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)

        private fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }

        private val KEY_IV_PAIRS = listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
        )
    }
}
