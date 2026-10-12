package sk.mirecky.skener.data

import java.text.Normalizer

/** Vyhľadávanie v názvoch a texte dokumentov bez ohľadu na veľkosť písmen a diakritiku. */
object TextSearch {

    private val MARKS = Regex("\\p{Mn}+")

    /** Malé písmená bez diakritiky; dĺžka reťazca ostane zachovaná (pozície sa dajú preniesť na pôvodný text). */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.code < 128) {
                sb.append(ch.lowercaseChar())
                continue
            }
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).replace(MARKS, "")
            sb.append((base.firstOrNull() ?: ch).lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * Úryvok pôvodného textu okolo prvého výskytu [query] (už normalizovaného) v [text].
     * Vráti úryvok a rozsah zvýraznenia v ňom, alebo null, ak sa výraz nenašiel.
     */
    fun snippet(text: String, normalized: String, query: String, radius: Int = 40): Pair<String, IntRange>? {
        if (query.isEmpty()) return null
        val at = normalized.indexOf(query)
        if (at < 0 || normalized.length != text.length) return null
        val start = (at - radius).coerceAtLeast(0).let { s -> text.lastIndexOf(' ', s).takeIf { it in (s - 15)..s }?.plus(1) ?: s }
        val end = (at + query.length + radius).coerceAtMost(text.length).let { e -> text.indexOf(' ', e).takeIf { it in e..(e + 15) } ?: e }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val body = text.substring(start, end).replace(Regex("\\s+"), " ")
        // Po zlúčení medzier sa pozícia výrazu prepočíta v zlúčenom úryvku.
        val bodyNorm = normalize(body)
        val local = bodyNorm.indexOf(query)
        if (local < 0) return null
        val from = prefix.length + local
        return (prefix + body + suffix) to (from until from + query.length)
    }
}
