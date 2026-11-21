package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.wrongUse
import java.io.File

/** One chapter of the manual, or one section of a chapter: its id, its title and its text. [sections] are the sections of a chapter that have an id. */
class ManualTopic(val id: String, val title: String, val html: String, val sections: List<ManualTopic> = emptyList())

/**
 * The manual of the layer engine, `docs/watchface-animation.html` of the AAPS checkout the server
 * draws with (design 8.2): its chapters as plain text, and the key tables of its key reference.
 */
class EngineManual(private val file: File?) {

    private val html: String by lazy { file?.takeIf { it.isFile }?.readText() ?: wrongUse("The manual of the engine is missing: ${file?.path ?: "the AAPS checkout is not known"}") }

    /** The chapters: every `h2` with an id. */
    val topics: List<ManualTopic> by lazy {
        val heads = Regex("<h2 id=\"([^\"]+)\">(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        heads.mapIndexed { i, head ->
            val end = heads.getOrNull(i + 1)?.range?.first ?: html.length
            val chapter = html.substring(head.range.first, end).substringBefore("</main>")
            ManualTopic(head.groupValues[1], plain(head.groupValues[2]).trim(), chapter, sections(chapter))
        }
    }

    /** The sections of [chapter]: every `h3` and `h4` with an id, up to the next heading of its own level or higher. */
    private fun sections(chapter: String): List<ManualTopic> {
        val heads = Regex("<h([34]) id=\"([^\"]+)\">(.*?)</h[34]>", RegexOption.DOT_MATCHES_ALL).findAll(chapter).toList()
        val all = Regex("<h([34])[ >]").findAll(chapter).toList()
        return heads.map { head ->
            val level = head.groupValues[1].toInt()
            val end = all.firstOrNull { it.range.first > head.range.first && it.groupValues[1].toInt() <= level }?.range?.first ?: chapter.length
            ManualTopic(head.groupValues[2], plain(head.groupValues[3]).trim(), chapter.substring(head.range.first, end))
        }
    }

    /** The chapter or the section [topic] (an id, or a word of a title) as plain text. A chapter comes before a section. */
    fun text(topic: String): String {
        val sections = topics.flatMap { it.sections }
        val found = topics.firstOrNull { it.id == topic } ?: sections.firstOrNull { it.id == topic }
            ?: topics.firstOrNull { it.title.contains(topic, ignoreCase = true) } ?: sections.firstOrNull { it.title.contains(topic, ignoreCase = true) }
            ?: wrongUse("The manual has no topic '$topic'. Topics: ${topics.joinToString(", ") { it.id }}. facetool manual --list shows the sections too")
        return plain(found.html).trim()
    }

    /**
     * The key tables of the chapter "Key reference": the title of each table and the keys in its first
     * column. This is the manual's own list of every key the engine reads.
     */
    val keyTables: Map<String, List<String>> by lazy {
        val reference = topics.firstOrNull { it.id == "reference" }?.html ?: return@lazy emptyMap()
        val result = LinkedHashMap<String, List<String>>()
        val parts = reference.split("<h3")
        for (part in parts.drop(1)) {
            val title = plain(part.substringAfter(">").substringBefore("</h3>")).trim()
            val keys = Regex("<tr>\\s*<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(part)
                .flatMap { row -> Regex("<code>(.*?)</code>").findAll(row.groupValues[1]).map { plain(it.groupValues[1]).trim() } }
                .filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }.distinct().toList()
            if (keys.isNotEmpty()) result[title] = keys
        }
        result
    }

    /** HTML as plain text: a line per block, a table row as cells with ` | `. */
    private fun plain(html: String): String = html
        .replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("</t[dh]>\\s*<t[dh][^>]*>"), " | ")
        .replace(Regex("<h[1-6][^>]*>"), "\n\n## ")
        .replace(Regex("<li[^>]*>"), "\n- ")
        .replace(Regex("</(p|div|tr|h[1-6]|pre|table|ul|ol)>"), "\n")
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")
}
