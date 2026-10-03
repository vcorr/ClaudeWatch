package com.vcorr.claudewatch

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The API key lives in a private file on the device, never in the APK. It arrives through the
 * key setup page (KeySetupServer) or over adb (see README).
 */
object ApiKeyStore {

    private const val FILE_NAME = "api_key"

    suspend fun read(context: Context): String? = withContext(Dispatchers.IO) {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Blocking; call off the main thread. Returns false if the key is blank or the write fails. */
    fun write(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        return AtomicFile(File(context.filesDir, FILE_NAME)).writeAll(trimmed.toByteArray(Charsets.UTF_8))
    }
}
