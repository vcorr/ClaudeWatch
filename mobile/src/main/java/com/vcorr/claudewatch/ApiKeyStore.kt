package com.vcorr.claudewatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The API key lives in a private file provisioned over adb (see README), never in the APK.
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
}
