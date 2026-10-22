package com.folio.reader.ui.reader

import org.json.JSONObject
import android.util.Log
import android.text.Html
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
        // The free dictionary first (it has pronunciations); Wiktionary when that one is down,
        // busy or doesn't know the word.
        var result = withContext(Dispatchers.IO) { fetch(primaryUrl(key)) { parse(it) } }
        if (result !is Lookup.Found) {
            val second = withContext(Dispatchers.IO) { fetch(wiktionaryUrl(key)) { parseWiktionary(key, it) } }
            if (second is Lookup.Found || result is Lookup.Offline) result = second
        }
        if (result !is Lookup.Offline) synchronized(cache) { cache[key] = result }
        return result
    }

    private fun encode(word: String) = URLEncoder.encode(word, "UTF-8").replace("+", "%20")

    private fun primaryUrl(key: String) = "https://api.dictionaryapi.dev/api/v2/entries/en/" + encode(key)

    private fun wiktionaryUrl(key: String) = "https://en.wiktionary.org/api/rest_v1/page/definition/" + encode(key)

    /** GETs [url]: Found when [parse] makes sense of it, NotFound on a 404, Offline otherwise. */
    private fun fetch(url: String, parse: (String) -> Definition?): Lookup {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            // Wikimedia asks apps to say who they are.
            conn.setRequestProperty("User-Agent", "Folio/1.0 (Android e-reader)")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                404 -> Lookup.NotFound
                200 -> parse(conn.inputStream.bufferedReader().readText())?.let { Lookup.Found(it) } ?: Lookup.NotFound
                else -> {
                    Log.w("FolioDictionary", "$url answered $code")
                    Lookup.Offline
                }
            }
        } catch (e: FileNotFoundException) {
            Lookup.NotFound
        } catch (e: Exception) {
            Log.w("FolioDictionary", "Couldn't reach $url", e)
            Lookup.Offline
        } finally {
            conn.disconnect()
        }
    }

    /** Wiktionary's definitions: {"en": [{partOfSpeech, definitions: [{definition, examples}]}], ...} in HTML. */
    private fun parseWiktionary(word: String, json: String): Definition? = runCatching {
        val english = JSONObject(json).optJSONArray("en") ?: return null
        val byPart = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
        for (i in 0 until english.length()) {
            val entry = english.getJSONObject(i)
            val list = byPart.getOrPut(entry.optString("partOfSpeech").lowercase()) { mutableListOf() }
            val defs = entry.optJSONArray("definitions") ?: continue
            for (d in 0 until defs.length()) {
                if (list.size >= 4) break
                val def = defs.getJSONObject(d)
                val text = plain(def.optString("definition"))
                if (text.isBlank()) continue
                val example = def.optJSONArray("examples")?.optString(0)?.let(::plain)?.takeIf { it.isNotBlank() }
                list += text to example
            }
        }
        val meanings = byPart.filter { it.key.isNotBlank() && it.value.isNotEmpty() }.map { (part, defs) -> Meaning(part, defs) }
        if (meanings.isEmpty()) null else Definition(word, null, meanings)
    }.getOrNull()

    private fun plain(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

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
