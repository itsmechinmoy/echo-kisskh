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
    private val baseurl: String,
) {
    fun decryptToString(subUrl: String): String {
        val subHeaders = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", baseurl)
            .add("Referer", "$baseurl/")
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

        return chunks.mapIndexed { index, chunk ->
            val parts = chunk.split("\n")
            val text = parts.slice(1 until parts.size)
            val d = text.joinToString("\n") { runCatching { decrypt(it) }.getOrDefault("") }

            listOf((index + 1).toString(), parts.first(), d).joinToString("\n")
        }.joinToString("\n\n")
    }

    suspend fun getSubtitles(subUrl: String): String {
        val decrypted = decryptToString(subUrl)

        val file = File.createTempFile("kisskh_subs_", ".srt").apply {
            deleteOnExit()
            writeText(decrypted)
        }

        return file.toURI().toString()
    }

    companion object {
        private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

        private const val KEY = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"

        private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    }

    private val keyIvPairs by lazy {
        listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
        )
    }

    private fun decrypt(encryptedB64: String): String {
        if (encryptedB64.isBlank()) return ""
        val encryptedBytes = Base64.getDecoder().decode(encryptedB64.trim())

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
            } catch (_: Exception) {
            }
        }
        throw IOException("Decryption failed: All keys/IVs failed")
    }

    private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    private fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
        forEachIndexed { index, value ->
            bytes[index * 4] = (value shr 24).toByte()
            bytes[index * 4 + 1] = (value shr 16).toByte()
            bytes[index * 4 + 2] = (value shr 8).toByte()
            bytes[index * 4 + 3] = value.toByte()
        }
    }
}
