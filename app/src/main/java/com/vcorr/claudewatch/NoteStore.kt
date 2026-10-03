package com.vcorr.claudewatch

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Things the wearer asked Claude to remember ("I parked on level 3"), optionally with where they
 * were. Kept on the watch only, in the app's private files (backups are off), and read back only
 * when Claude calls the recall tool. Oldest notes give way beyond [MAX_NOTES].
 */
class NoteStore(context: Context) {

    class Note(
        val id: Int,
        val text: String,
        val savedAt: Long,
        val latitude: Double?,
        val longitude: Double?,
        val place: String?,
    )

    private val file = AtomicFile(File(context.applicationContext.filesDir, "notes.json"))

    @Synchronized
    fun all(): List<Note> = try {
        val array = JSONArray(String(file.readFully()))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Note(
                id = o.getInt("id"),
                text = o.getString("text"),
                savedAt = o.getLong("savedAt"),
                latitude = o.optDouble("lat").takeUnless { it.isNaN() },
                longitude = o.optDouble("lon").takeUnless { it.isNaN() },
                place = o.optString("place").ifBlank { null },
            )
        }
    } catch (e: Exception) {
        // No file yet, or one that can't be read: nothing remembered.
        emptyList()
    }

    /** Saves a note and returns it, or null if it couldn't be written. */
    @Synchronized
    fun add(text: String, latitude: Double?, longitude: Double?, place: String?): Note? {
        val notes = all()
        val note = Note((notes.maxOfOrNull { it.id } ?: 0) + 1, text, System.currentTimeMillis(), latitude, longitude, place)
        return note.takeIf { write((notes + note).takeLast(MAX_NOTES)) }
    }

    /** Removes the notes with these ids; returns how many went. */
    @Synchronized
    fun remove(ids: Set<Int>): Int {
        val notes = all()
        val kept = notes.filterNot { it.id in ids }
        return if (write(kept)) notes.size - kept.size else 0
    }

    @Synchronized
    fun clear(): Int {
        val count = all().size
        return if (write(emptyList())) count else 0
    }

    private fun write(notes: List<Note>): Boolean {
        val array = JSONArray()
        notes.forEach { n ->
            array.put(
                JSONObject()
                    .put("id", n.id)
                    .put("text", n.text)
                    .put("savedAt", n.savedAt)
                    .apply {
                        if (n.latitude != null && n.longitude != null) put("lat", n.latitude).put("lon", n.longitude)
                        n.place?.let { put("place", it) }
                    }
            )
        }
        return file.writeAll(array.toString().toByteArray())
    }

    companion object {
        const val MAX_NOTES = 50
        const val MAX_LENGTH = 500
    }
}
