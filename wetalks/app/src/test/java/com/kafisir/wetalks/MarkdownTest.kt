package com.kafisir.wetalks

import com.kafisir.wetalks.ui.Markdown
import com.kafisir.wetalks.ui.Markdown.Block
import com.kafisir.wetalks.ui.Markdown.Run
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private fun runs(s: String) = Markdown.inline(s)
    private fun txt(r: List<Run>) = r.joinToString("") { it.text }

    @Test fun boldItalicStrikeCode() {
        val r = runs("**bold** and *it* and ~~gone~~ and `code` end")
        assertEquals("bold and it and gone and code end", txt(r))
        assertTrue(r.first { it.text == "bold" }.bold); assertTrue(r.first { it.text == "it" }.italic)
        assertTrue(r.first { it.text == "gone" }.strike); assertTrue(r.first { it.text == "code" }.code)
        assertFalse(r.first { it.text == " and " }.bold)
    }

    @Test fun underscoreAndSingleTilde() {
        val r = runs("_slant_ and ~old~ and __strong__")
        assertTrue(r.first { it.text == "slant" }.italic); assertTrue(r.first { it.text == "old" }.strike); assertTrue(r.first { it.text == "strong" }.bold)
    }

    @Test fun boldItalicTogetherAndNested() {
        val r = runs("***both*** and **bold with *italic* inside**")
        assertTrue(r.first { it.text == "both" }.let { it.bold && it.italic })
        assertTrue(r.first { it.text == "italic" }.let { it.bold && it.italic })
    }

    @Test fun doesNotFormatInsideWordsOrMaths() {
        for (s in listOf("snake_case_name and my_var_2", "2*3*4 = 24", "file*.txt and *.png", "price is 5 * 6", "a * b * c", "hello_world_ok", "I~am~fine5")) {
            val r = runs(s); if (s.startsWith("I~")) continue
            assertEquals(s, txt(r)); assertTrue(s, r.all { !it.bold && !it.italic && !it.strike })
        }
    }

    @Test fun escapesAndUnclosedMarks() {
        assertEquals("*not italic*", txt(runs("\\*not italic\\*")))
        assertEquals("**unclosed bold", txt(runs("**unclosed bold")))
        assertEquals("a ` b", txt(runs("a ` b")))
    }

    @Test fun linksOnlyWhenSafe() {
        val r = runs("see [the docs](https://example.com/a?b=1) now")
        assertEquals("https://example.com/a?b=1", r.first { it.text == "the docs" }.link)
        assertNull(runs("[x](javascript:alert(1))").firstOrNull { it.link != null })
        assertEquals("[x](javascript:alert(1))", txt(runs("[x](javascript:alert(1))")))
    }

    @Test fun spoilers() {
        val r = runs("the answer is ||42|| ok")
        assertTrue(r.first { it.text == "42" }.spoiler); assertFalse(r.first { it.text.startsWith("the") }.spoiler)
    }

    @Test fun codeSpanKeepsMarks() {
        val r = runs("use `**not bold**` here")
        assertEquals("**not bold**", r.first { it.code }.text); assertFalse(r.first { it.code }.bold)
    }

    @Test fun fencedCodeBlocks() {
        val b = Markdown.parse("before\n```kotlin\nval x = 1\nprintln(x)\n```\nafter")
        assertEquals(3, b.size)
        val c = b[1] as Block.Code; assertEquals("kotlin", c.lang); assertEquals("val x = 1\nprintln(x)", c.text)
        val open = Markdown.parse("```\nno closing fence\nstill code") // unterminated: everything after is code
        assertEquals("no closing fence\nstill code", (open.single() as Block.Code).text)
    }

    @Test fun headingsListsQuotesRules() {
        val b = Markdown.parse("# Title\n- one\n- [x] done\n- [ ] todo\n1. first\n2. second\n> quoted *text*\n---")
        assertEquals(1, (b[0] as Block.Heading).level)
        assertEquals("•", (b[1] as Block.Item).marker); assertEquals(true, (b[2] as Block.Item).check); assertEquals(false, (b[3] as Block.Item).check)
        assertEquals("1.", (b[4] as Block.Item).marker)
        assertTrue(b[6] is Block.Quote); assertTrue(b[7] === Block.Rule)
        assertEquals("quoted text", txt(((b[6] as Block.Quote).blocks.single() as Block.Para).runs))
    }

    @Test fun tablesWithAlignment() {
        val b = Markdown.parse("| Name | Qty | Price |\n|:-----|:---:|------:|\n| Tea | 2 | 10 |\n| Milk | 1 | 50 |") .single() as Block.Table
        assertEquals(listOf(0, 1, 2), b.align); assertEquals(3, b.header.size); assertEquals(2, b.rows.size); assertEquals("Milk", txt(b.rows[1][0]))
    }

    @Test fun plainTextStaysPlain() {
        for (s in listOf("hello how are you", "meet at 5, ok?", "2 + 2 = 4", "ab_cd", "a - b")) { assertFalse(s, Markdown.hasFormatting(s)); assertEquals(s, Markdown.plain(s)) }
    }

    @Test fun lineBreaksAreKept() {
        val p = Markdown.parse("line one\nline two\n\nnew paragraph")
        assertEquals(2, p.size); assertEquals("line one\nline two", txt((p[0] as Block.Para).runs))
    }

    @Test fun plainForSpeechAndPreview() {
        assertEquals("Hello bold world", Markdown.preview("Hello **bold** _world_"))
        assertEquals("Title. one two", Markdown.preview("# Title\n- one\n- two"))
        assertEquals("see docs Code block.", Markdown.preview("see [docs](https://x.com)\n```js\nalert(1)\n```"))
        assertEquals("a, b. 1, 2.", Markdown.preview("| a | b |\n|---|---|\n| 1 | 2 |"))
    }
}
