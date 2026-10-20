package com.folio.reader.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A word the reader looked up, with the sentence it came from. */
data class SavedWord(
    val id: String,
    val word: String,
    /** Its first dictionary meaning, once found (shown without going online again). */
    val meaning: String?,
    val partOfSpeech: String?,
    val sentence: String,
    val bookId: String?,
    val bookTitle: String,
    val addedAt: Long,
    /** How many times it has been looked up. */
    val count: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("word", word)
        .put("meaning", meaning ?: JSONObject.NULL)
        .put("partOfSpeech", partOfSpeech ?: JSONObject.NULL)
        .put("sentence", sentence)
        .put("bookId", bookId ?: JSONObject.NULL)
        .put("bookTitle", bookTitle)
        .put("addedAt", addedAt)
        .put("count", count)

    companion object {
        fun fromJson(o: JSONObject) = SavedWord(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            word = o.optString("word"),
            meaning = o.optString("meaning").takeIf { !o.isNull("meaning") && it.isNotBlank() },
            partOfSpeech = o.optString("partOfSpeech").takeIf { !o.isNull("partOfSpeech") && it.isNotBlank() },
            sentence = o.optString("sentence"),
            bookId = o.optString("bookId").takeIf { !o.isNull("bookId") && it.isNotBlank() },
            bookTitle = o.optString("bookTitle"),
            addedAt = o.optLong("addedAt"),
            count = o.optInt("count", 1).coerceAtLeast(1),
        )
    }
}

/** Every word looked up with Define, newest first, kept in a small JSON file. */
class WordList(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "words.json")

    private val _words = MutableStateFlow(load())
    val words: StateFlow<List<SavedWord>> = _words.asStateFlow()

    /** Adds [word], or moves it back to the top if it was looked up before. */
    fun add(word: String, sentence: String, bookId: String?, bookTitle: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        change { list ->
            val old = list.firstOrNull { it.word.equals(w, ignoreCase = true) }
            val entry = old?.copy(addedAt = System.currentTimeMillis(), count = old.count + 1)
                ?: SavedWord(UUID.randomUUID().toString(), w, null, null, sentence.trim(), bookId, bookTitle, System.currentTimeMillis())
            listOf(entry) + list.filterNot { it.id == entry.id }
        }
    }

    /** Remembers the meaning the dictionary gave for [word]. */
    fun setMeaning(word: String, partOfSpeech: String?, meaning: String) = change { list ->
        list.map { if (it.word.equals(word.trim(), ignoreCase = true) && it.meaning == null) it.copy(meaning = meaning, partOfSpeech = partOfSpeech) else it }
    }

    fun remove(id: String) = change { list -> list.filterNot { it.id == id } }

    fun toJson(): JSONArray = JSONArray().apply { _words.value.forEach { put(it.toJson()) } }

    /** Adds the words from a backup that aren't here yet. */
    fun merge(a: JSONArray, ids: Map<String, String>) = change { list ->
        val theirs = (0 until a.length()).mapNotNull { runCatching { SavedWord.fromJson(a.getJSONObject(it)) }.getOrNull() }
            .filter { t -> t.word.isNotBlank() && list.none { it.word.equals(t.word, ignoreCase = true) } }
            .map { it.copy(bookId = it.bookId?.let { id -> ids[id] ?: id }) }
        (list + theirs).sortedByDescending { it.addedAt }
    }

    private fun change(transform: (List<SavedWord>) -> List<SavedWord>) {
        _words.update(transform)
        val snapshot = _words.value
        scope.launch(Dispatchers.IO) { save(snapshot) }
    }

    private fun load(): List<SavedWord> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        List(a.length()) { SavedWord.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(list: List<SavedWord>) {
        // Only the newest state matters; an older write finishing late mustn't win.
        if (list !== _words.value) return
        val tmp = File(file.parentFile, "words.json.tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }
}
