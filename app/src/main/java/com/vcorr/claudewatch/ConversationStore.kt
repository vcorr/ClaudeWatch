package com.vcorr.claudewatch

import android.content.Context
import android.util.AtomicFile
import com.vcorr.claudewatch.core.Conversation
import com.vcorr.claudewatch.core.Role
import com.vcorr.claudewatch.core.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the current chat in the app's private storage, so the next launch can carry on where the
 * last one stopped. A chat untouched for [MAX_AGE_MS] is not resumed. Backups are off for the app.
 */
object ConversationStore {

    const val MAX_AGE_MS = 30 * 60 * 1000L

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "conversation.json"))

    suspend fun save(context: Context, turns: List<Turn>) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("turns", JSONArray().apply {
                turns.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text)) }
            })
        val target = file(context)
        val out = try {
            target.startWrite()
        } catch (e: Exception) {
            return@withContext
        }
        try {
            out.write(json.toString().toByteArray())
            target.finishWrite(out)
        } catch (e: Exception) {
            target.failWrite(out)
        }
    }

    /** The saved turns, or none if there are none or they are too old. */
    suspend fun load(context: Context): List<Turn> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(String(file(context).readFully()))
            if (!Conversation.isFresh(json.getLong("savedAt"), System.currentTimeMillis(), MAX_AGE_MS)) {
                return@withContext emptyList()
            }
            val array = json.getJSONArray("turns")
            (0 until array.length()).map {
                val turn = array.getJSONObject(it)
                Turn(Role.valueOf(turn.getString("role")), turn.getString("text"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        file(context).delete()
    }
}
