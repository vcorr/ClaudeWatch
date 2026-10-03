package com.vcorr.claudewatch

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Things the wearer asked Claude to remember ("I parked on level 3"), optionally with where they
 * were. Kept on the watch only, in the app's private files (backups are off), and read back only
 * when Claude calls the recall tool. Oldest notes give way beyond [MAX_NOTES]. Ids only ever grow,
 * so an id Claude heard earlier never names a different note. One lock for the whole app, since
 * each activity has its own tools.
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

    private class Contents(val nextId: Int, val notes: List<Note>)

    private val file = AtomicFile(File(context.applicationContext.filesDir, "notes.json"))

    /** The notes, oldest first; empty if there are none. Throws if the file can't be read. */
    fun all(): List<Note> = synchronized(LOCK) { read().notes }

    /** Saves a note and returns it, or null if it couldn't be written. */
    fun add(text: String, latitude: Double?, longitude: Double?, place: String?): Note? = synchronized(LOCK) {
        val contents = runCatching { read() }.getOrNull() ?: return null
        val note = Note(contents.nextId, text, System.currentTimeMillis(), latitude, longitude, place)
        note.takeIf { write(Contents(contents.nextId + 1, (contents.notes + note).takeLast(MAX_NOTES))) }
    }

    /** Removes the notes with these ids; returns how many went, or -1 if the notes couldn't be changed. */
    fun remove(ids: Set<Int>): Int = synchronized(LOCK) {
        val contents = runCatching { read() }.getOrNull() ?: return -1
        val kept = contents.notes.filterNot { it.id in ids }
        if (write(Contents(contents.nextId, kept))) contents.notes.size - kept.size else -1
    }

    /**
     * Deletes every note; returns how many there were (0 if the file couldn't be read), or -1 if
     * nothing could be written. An unreadable file is replaced too, so this always recovers, with
     * ids starting past any the old file might have used.
     */
    fun clear(): Int = synchronized(LOCK) {
        val contents = runCatching { read() }.getOrNull()
        val nextId = contents?.nextId ?: maxOf(1, (System.currentTimeMillis() / 1000 % 1_000_000_000).toInt())
        if (write(Contents(nextId, emptyList()))) contents?.notes?.size ?: 0 else -1
    }

    // A missing file is simply no notes yet; any other failure throws, so nothing overwrites a
    // file that merely couldn't be read.
    private fun read(): Contents {
        val text = try {
            String(file.readFully())
        } catch (e: FileNotFoundException) {
            return Contents(1, emptyList())
        }
        // The first version kept a bare list, with no counter.
        val root = if (text.trimStart().startsWith("[")) JSONObject().put("notes", JSONArray(text)) else JSONObject(text)
        val array = root.getJSONArray("notes")
        val notes = (0 until array.length()).map { i ->
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
        return Contents(maxOf(root.optInt("nextId", 1), (notes.maxOfOrNull { it.id } ?: 0) + 1), notes)
    }

    private fun write(contents: Contents): Boolean {
        val array = JSONArray()
        contents.notes.forEach { n ->
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
        val root = JSONObject().put("nextId", contents.nextId).put("notes", array)
        return file.writeAll(root.toString().toByteArray())
    }

    companion object {
        const val MAX_NOTES = 50
        const val MAX_LENGTH = 500
        private val LOCK = Any()
    }
}
