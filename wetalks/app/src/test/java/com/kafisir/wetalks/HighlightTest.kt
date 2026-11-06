package com.kafisir.wetalks

import com.kafisir.wetalks.ui.Highlight
import org.junit.Assert.*
import org.junit.Test

class HighlightTest {
    private fun kinds(code: String, lang: String) = Highlight.spans(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test fun kotlinKeywordsStringsNumbersComments() {
        val k = kinds("val x = \"hi\" // note\nfun f() = 42", "kotlin")
        assertTrue(("val" to Highlight.KEYWORD) in k); assertTrue(("\"hi\"" to Highlight.STRING) in k); assertTrue(("// note" to Highlight.COMMENT) in k)
        assertTrue(("fun" to Highlight.KEYWORD) in k); assertTrue(("42" to Highlight.NUMBER) in k)
    }

    @Test fun pythonHashCommentsButNotInStrings() {
        val k = kinds("x = '#not a comment'  # real\nprint(x)", "python")
        assertTrue(("'#not a comment'" to Highlight.STRING) in k); assertTrue(("# real" to Highlight.COMMENT) in k)
    }

    @Test fun identifiersThatContainKeywordsAreNotColoured() {
        val k = kinds("value = integer1 + format", "python")
        assertTrue(k.none { it.second == Highlight.KEYWORD })
    }

    @Test fun hugeSnippetsStayPlain() { assertTrue(Highlight.spans("a".repeat(30_000), "kotlin").isEmpty()) }

    @Test fun fileExtensions() { assertEquals("py", Highlight.extension("python")); assertEquals("kt", Highlight.extension("Kotlin")); assertEquals("txt", Highlight.extension("weird")) }
}
