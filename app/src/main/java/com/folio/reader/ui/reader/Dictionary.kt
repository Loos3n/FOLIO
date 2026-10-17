package com.folio.reader.ui.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Meaning(val partOfSpeech: String, val definitions: List<Pair<String, String?>>)

data class Definition(val word: String, val phonetic: String?, val meanings: List<Meaning>)

sealed interface Lookup {
    data object Loading : Lookup
    data class Found(val definition: Definition) : Lookup
    data object NotFound : Lookup
    data object Offline : Lookup
}

/** Looks words up in the free English dictionary at dictionaryapi.dev. */
object Dictionary {
    /** The word (or short phrase) to look up from a selection: trimmed of punctuation. */
    fun termFrom(selection: String): String {
        val words = selection.trim().split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() && c != '-' && c != '\'' } }
            .filter { it.isNotEmpty() }
        return (if (words.size > 3) words.take(1) else words).joinToString(" ")
            .trim { !it.isLetterOrDigit() }
    }

    private val cache = HashMap<String, Lookup>()

    suspend fun lookUp(term: String): Lookup {
        val key = term.lowercase()
        synchronized(cache) { cache[key] }?.let { return it }
        val result = withContext(Dispatchers.IO) {
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(key, "UTF-8").replace("+", "%20"))
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                when (conn.responseCode) {
                    404 -> Lookup.NotFound
                    200 -> parse(conn.inputStream.bufferedReader().readText())?.let { Lookup.Found(it) } ?: Lookup.NotFound
                    else -> Lookup.Offline
                }
            } catch (e: FileNotFoundException) {
                Lookup.NotFound
            } catch (e: Exception) {
                Lookup.Offline
            } finally {
                conn.disconnect()
            }
        }
        if (result !is Lookup.Offline) synchronized(cache) { cache[key] = result }
        return result
    }

    private fun parse(json: String): Definition? = runCatching {
        val entries = JSONArray(json)
        if (entries.length() == 0) return null
        val first = entries.getJSONObject(0)
        var phonetic = first.optString("phonetic").takeIf { it.isNotBlank() }
        // Group meanings by part of speech across entries (the API splits some words up).
        val byPart = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
        for (e in 0 until entries.length()) {
            val entry = entries.getJSONObject(e)
            if (phonetic == null) {
                val ps = entry.optJSONArray("phonetics")
                for (p in 0 until (ps?.length() ?: 0)) {
                    val t = ps!!.getJSONObject(p).optString("text")
                    if (t.isNotBlank()) { phonetic = t; break }
                }
            }
            val meanings = entry.optJSONArray("meanings") ?: continue
            for (m in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(m)
                val list = byPart.getOrPut(meaning.optString("partOfSpeech")) { mutableListOf() }
                val defs = meaning.optJSONArray("definitions") ?: continue
                for (d in 0 until defs.length()) {
                    if (list.size >= 4) break
                    val def = defs.getJSONObject(d)
                    val text = def.optString("definition")
                    if (text.isNotBlank()) list += text to def.optString("example").takeIf { it.isNotBlank() }
                }
            }
        }
        val meanings = byPart.filterValues { it.isNotEmpty() }.map { (part, defs) -> Meaning(part, defs) }
        if (meanings.isEmpty()) null else Definition(first.optString("word"), phonetic, meanings)
    }.getOrNull()
}
