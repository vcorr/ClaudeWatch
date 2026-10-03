package com.vcorr.claudewatch

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The log tag for the whole app. Logs carry counts, names and error classes, never content. */
internal const val TAG = "ClaudeWatch"

internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** A time or date for Claude or the wearer, in British English, in the watch's time zone. */
internal fun ukTime(pattern: String, millis: Long): String = SimpleDateFormat(pattern, Locale.UK).format(Date(millis))

/** Replaces the file's contents in one go, so a crash mid-write never leaves half a file. */
internal fun AtomicFile.writeAll(bytes: ByteArray): Boolean {
    val out = try {
        startWrite()
    } catch (e: IOException) {
        return false
    }
    return try {
        out.write(bytes)
        finishWrite(out)
        true
    } catch (e: IOException) {
        failWrite(out)
        false
    }
}
