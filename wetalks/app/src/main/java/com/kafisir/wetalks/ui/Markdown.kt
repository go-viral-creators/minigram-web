package com.kafisir.wetalks.ui

/**
 * Markdown for chat messages (pure Kotlin, so it can be unit-tested).
 *  inline : **bold**  *italic* / _italic_  ***both***  ~~strike~~ / ~strike~  `code`  ||spoiler||  [text](https://link)  \* escapes
 *  blocks : ``` fenced code ```  # headings  - / * / 1. lists  - [ ] task lists  > quotes  | tables |  --- rules
 * Formatting only starts at a word edge, so snake_case_names, 2*3*4 or file*.txt stay exactly as typed.
 */
object Markdown {
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
                   val code: Boolean = false, val spoiler: Boolean = false, val link: String? = null)

    sealed class Block {
        class Para(val runs: List<Run>) : Block()
        class Heading(val level: Int, val runs: List<Run>) : Block()
        class Code(val lang: String, val text: String) : Block()
        class Quote(val blocks: List<Block>) : Block()
        class Item(val depth: Int, val marker: String, val check: Boolean?, val runs: List<Run>) : Block()
        class Table(val header: List<List<Run>>, val align: List<Int>, val rows: List<List<List<Run>>>) : Block()   // align: 0 left, 1 centre, 2 right
        object Rule : Block()
    }

    private val QUICK = Regex("""(\*\S|(?<![A-Za-z0-9])_\S|~|`|\|\||\]\(|(?m)^\s{0,3}(#{1,6}\s|>\s?|[-*+•]\s|\d+[.)]\s)|(?m)^\s*\|)""")
    /** Cheap check, so plain messages skip the parser. */
    fun hasFormatting(t: String) = t.length > 1 && QUICK.containsMatchIn(t)

    // ------------------------------------------------------------------------------------------ inline
    private fun isWord(c: Char?) = c != null && (c.isLetterOrDigit())
    private fun isSpace(c: Char?) = c == null || c.isWhitespace()

    fun inline(text: String): List<Run> {
        val out = ArrayList<Run>(); parseInline(text, Run(""), out); return merge(out)
    }

    private fun merge(runs: List<Run>): List<Run> {
        val res = ArrayList<Run>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val l = res.lastOrNull()
            if (l != null && l.copy(text = "") == r.copy(text = "")) res[res.lastIndex] = l.copy(text = l.text + r.text) else res.add(r)
        }
        return res
    }

    private fun closer(s: String, from: Int, mark: String): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == '`') { val e = s.indexOf('`', i + 1); if (e > 0) { i = e + 1; continue } }
            if (s.startsWith(mark, i) && i > from && !isSpace(s[i - 1]) && !isWord(s.getOrNull(i + mark.length)) && (mark.length == 1 || true)) {
                // for a single * or _ the next character must not continue the same mark (so ** is not closed by *)
                if (mark.length == 1 && s.getOrNull(i + 1) == mark[0]) { i += 2; continue }
                return i
            }
            i++
        }
        return -1
    }

    private fun parseInline(s: String, st: Run, out: MutableList<Run>) {
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(st.copy(text = buf.toString())); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else null
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_~|[]()#>-+.!") { buf.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                val n = s.substring(i).takeWhile { it == '`' }.length
                val tick = "`".repeat(n)
                val e = s.indexOf(tick, i + n)
                if (e > i + n - 0 && n <= 2) { flush(); out.add(st.copy(text = s.substring(i + n, e).trim(), code = true)); i = e + n; continue }
            }
            if (c == '|' && s.startsWith("||", i) && !isWord(prev) && !isSpace(s.getOrNull(i + 2))) {
                val e = s.indexOf("||", i + 2)
                if (e > i + 2 && !isSpace(s[e - 1])) { flush(); parseInline(s.substring(i + 2, e), st.copy(spoiler = true), out); i = e + 2; continue }
            }
            if (c == '[') {
                val m = LINK.find(s, i)
                if (m != null && m.range.first == i) {
                    val url = m.groupValues[2].trim()
                    if (SAFE_URL.matches(url)) { flush(); parseInline(m.groupValues[1], st.copy(link = url), out); i = m.range.last + 1; continue }
                }
            }
            if ((c == '*' || c == '_' || c == '~') && !isWord(prev)) {
                var done = false
                for (mark in listOf(c.toString().repeat(3), c.toString().repeat(2), c.toString())) {
                    if (c == '~' && mark.length == 3) continue
                    if (!s.startsWith(mark, i) || isSpace(s.getOrNull(i + mark.length)) || s.getOrNull(i + mark.length) == c) continue
                    val e = closer(s, i + mark.length, mark)
                    if (e < 0) continue
                    flush()
                    val inner = s.substring(i + mark.length, e)
                    val ns = when {
                        c == '~' -> st.copy(strike = true)
                        mark.length == 3 -> st.copy(bold = true, italic = true)
                        mark.length == 2 -> st.copy(bold = true)
                        else -> st.copy(italic = true)
                    }
                    parseInline(inner, ns, out); i = e + mark.length; done = true; break
                }
                if (done) continue
            }
            buf.append(c); i++
        }
        flush()
    }

    private val LINK = Regex("""\[([^\]\n]{1,200})]\(([^)\s]{1,500})\)""")
    private val SAFE_URL = Regex("""(?i)(https?://|mailto:|tel:)[^\s]+""")

    // ------------------------------------------------------------------------------------------ blocks
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val ITEM = Regex("""^(\s*)([-*+•]|\d{1,3}[.)])\s+(.*)$""")
    private val TASK = Regex("""^\[([ xX])]\s+(.*)$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<Block> = parseLines(text.replace("\r\n", "\n").split("\n"))

    private fun cells(line: String): List<String> {
        var l = line.trim(); if (l.startsWith("|")) l = l.substring(1); if (l.endsWith("|") && !l.endsWith("\\|")) l = l.dropLast(1)
        val out = ArrayList<String>(); val sb = StringBuilder(); var i = 0
        while (i < l.length) { if (l[i] == '\\' && i + 1 < l.length && l[i + 1] == '|') { sb.append('|'); i += 2; continue }; if (l[i] == '|') { out.add(sb.toString().trim()); sb.clear() } else sb.append(l[i]); i++ }
        out.add(sb.toString().trim()); return out
    }

    private fun parseLines(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>(); var i = 0
        val para = ArrayList<String>()
        fun flushPara() { if (para.isNotEmpty()) { blocks.add(Block.Para(inline(para.joinToString("\n")))); para.clear() } }
        while (i < lines.size) {
            val line = lines[i]; val t = line.trim()
            if (t.startsWith("```")) {                                        // fenced code
                flushPara()
                val lang = t.removePrefix("```").trim().substringBefore(' ').lowercase()
                val body = ArrayList<String>(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { body.add(lines[i]); i++ }
                i++ // closing fence (or the end)
                blocks.add(Block.Code(lang, body.joinToString("\n").trimEnd('\n'))); continue
            }
            if (t.isEmpty()) { flushPara(); i++; continue }
            val hm = HEADING.find(line)
            if (hm != null) { flushPara(); blocks.add(Block.Heading(hm.groupValues[1].length, inline(hm.groupValues[2]))); i++; continue }
            if (RULE.matches(line)) { flushPara(); blocks.add(Block.Rule); i++; continue }
            if (t.startsWith(">")) {
                flushPara(); val q = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q.add(lines[i].trim().removePrefix(">").removePrefix(" ")); i++ }
                blocks.add(Block.Quote(parseLines(q))); continue
            }
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') && cells(line).size >= 2) {
                flushPara()
                val head = cells(line).map { inline(it) }
                val align = cells(lines[i + 1]).map { c -> val l = c.startsWith(":"); val r = c.endsWith(":"); if (l && r) 1 else if (r) 2 else 0 }
                i += 2; val rows = ArrayList<List<List<Run>>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows.add(cells(lines[i]).map { inline(it) }); i++ }
                blocks.add(Block.Table(head, align, rows)); continue
            }
            val im = ITEM.find(line)
            if (im != null) {
                flushPara()
                var body = im.groupValues[3]; var check: Boolean? = null
                val tm = TASK.find(body)
                if (tm != null) { check = tm.groupValues[1] != " "; body = tm.groupValues[2] }
                val ordered = im.groupValues[2][0].isDigit()
                blocks.add(Block.Item(im.groupValues[1].replace("\t", "  ").length / 2, if (ordered) im.groupValues[2] else "•", check, inline(body))); i++
                continue
            }
            para.add(line); i++
        }
        flushPara()
        return blocks
    }

    // ------------------------------------------------------------------------------------------ plain text
    /** The message without any formatting marks: for reading aloud, previews and notifications. */
    fun plain(text: String): String {
        if (!hasFormatting(text)) return text
        val sb = StringBuilder()
        fun runs(r: List<Run>) = r.joinToString("") { it.text }
        fun walk(bs: List<Block>) {
            for (b in bs) {
                when (b) {
                    is Block.Para -> sb.append(runs(b.runs)).append('\n')
                    is Block.Heading -> sb.append(runs(b.runs)).append(".\n")
                    is Block.Code -> sb.append("Code block.\n")
                    is Block.Quote -> walk(b.blocks)
                    is Block.Item -> sb.append(runs(b.runs)).append('\n')
                    is Block.Table -> { sb.append(b.header.joinToString(", ") { runs(it) }).append(".\n"); for (r in b.rows) sb.append(r.joinToString(", ") { runs(it) }).append(".\n") }
                    Block.Rule -> {}
                }
            }
        }
        walk(parse(text))
        return sb.toString().trim()
    }

    /** One line version for the chat list and reply quotes. */
    fun preview(text: String): String = plain(text).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
