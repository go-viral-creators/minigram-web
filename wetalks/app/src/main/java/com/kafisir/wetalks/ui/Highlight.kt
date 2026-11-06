package com.kafisir.wetalks.ui

/** Light-weight syntax colouring for code blocks (one pass, no language grammar needed). */
object Highlight {
    const val KEYWORD = 0; const val STRING = 1; const val COMMENT = 2; const val NUMBER = 3
    class Span(val start: Int, val end: Int, val kind: Int)

    private val KW = ("abstract as assert async await bool boolean break by byte case catch char class const constructor continue data def default defer del delete do double " +
        "elif else enum except export extends extern false fi final finally float fn for from fun func function go goto if impl implements import in include inline instanceof int interface " +
        "is it lambda let long match module mut namespace new nil none not null object of open operator or override package pass print private protected pub public raise readonly ref " +
        "return sealed select self short signed static string struct super switch synchronized then this throw throws trait true try type typeof union unsigned until use using val var " +
        "virtual void volatile when where while with yield select insert update delete create table values order group limit join on and").split(' ').toHashSet()
    private val HASH_COMMENT = setOf("py", "python", "sh", "bash", "zsh", "shell", "yaml", "yml", "rb", "ruby", "toml", "r", "pl", "perl", "dockerfile", "makefile", "ini", "conf", "properties", "")
    private val DASH_COMMENT = setOf("sql", "lua", "hs", "haskell")
    private val SLASH_COMMENT = setOf("js", "javascript", "ts", "typescript", "java", "kotlin", "kt", "c", "cpp", "c++", "cs", "csharp", "go", "rust", "rs", "swift", "php", "dart", "scala", "json5", "css", "scss", "groovy", "gradle", "")

    fun spans(code: String, lang: String): List<Span> {
        if (code.length > 20_000) return emptyList()             // very long snippets stay plain, keeps scrolling smooth
        val l = lang.lowercase()
        val parts = ArrayList<String>()
        if (l in SLASH_COMMENT || l == "html" || l == "xml") parts.add("""/\*[\s\S]*?\*/|//[^\n]*""")
        if (l == "html" || l == "xml") parts.add("""<!--[\s\S]*?-->""")
        if (l in HASH_COMMENT) parts.add("""#[^\n]*""")
        if (l in DASH_COMMENT) parts.add("""--[^\n]*""")
        parts.add(""""(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`""")
        parts.add("""\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?\b""")
        parts.add("""[A-Za-z_][A-Za-z0-9_]*""")
        val re = Regex(parts.joinToString("|") { "($it)" })
        val out = ArrayList<Span>()
        var commentGroups = parts.size - 3
        for (m in re.findAll(code)) {
            val g = m.groups
            var kind = -1
            for (i in 1..parts.size) if (g[i] != null) {
                kind = when { i <= commentGroups -> COMMENT; i == commentGroups + 1 -> STRING; i == commentGroups + 2 -> NUMBER; else -> if (m.value in KW || m.value.lowercase() in KW && l == "sql") KEYWORD else -1 }
                break
            }
            if (kind >= 0) out.add(Span(m.range.first, m.range.last + 1, kind))
        }
        return out
    }

    fun extension(lang: String) = when (lang.lowercase()) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "html", "htm" -> "html"; "css" -> "css"
        "json" -> "json"; "bash", "sh", "shell", "zsh" -> "sh"; "sql" -> "sql"; "c" -> "c"; "cpp", "c++" -> "cpp"; "go" -> "go"; "rust", "rs" -> "rs"; "xml" -> "xml"
        "yaml", "yml" -> "yml"; "md", "markdown" -> "md"; "php" -> "php"; "swift" -> "swift"; "csharp", "cs" -> "cs"; "ruby", "rb" -> "rb"; else -> "txt"
    }
}
