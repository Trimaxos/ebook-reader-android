package com.ebookreader.tts

import java.io.File
import java.security.MessageDigest

class AudioCache(private val cacheDir: File) {

    companion object {
        private const val MAX_CACHE_SIZE = 100L * 1024 * 1024  // 100 MB
    }

    init {
        cacheDir.mkdirs()
    }

    fun get(text: String, voice: String, rate: String): ByteArray? {
        val key = hashKey(text, voice, rate)
        val file = File(cacheDir, key)
        return if (file.exists()) {
            updateAccessTime(file)
            file.readBytes()
        } else null
    }

    fun put(text: String, voice: String, rate: String, audio: ByteArray) {
        val key = hashKey(text, voice, rate)
        val file = File(cacheDir, key)
        file.writeBytes(audio)
        evictIfNeeded()
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCacheSize(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun hashKey(text: String, voice: String, rate: String): String {
        val input = "$text|$voice|$rate"
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun updateAccessTime(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun evictIfNeeded() {
        var size = getCacheSize()
        if (size <= MAX_CACHE_SIZE) return

        val files = cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return

        for (file in files) {
            if (size <= MAX_CACHE_SIZE) break
            size -= file.length()
            file.delete()
        }
    }
}
