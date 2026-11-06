package com.kafisir.wetalks

import com.kafisir.wetalks.ui.CodeDetect
import com.kafisir.wetalks.ui.TextTools
import org.junit.Assert.*
import org.junit.Test

class TextToolsTest {
    private fun enter(before: String): String {
        val t = before + "\n"
        val e = TextTools.continueList(t, t.length - 1) ?: return t
        return t.substring(0, e.start) + e.text + t.substring(e.end)
    }

    @Test fun numberedListContinues() { assertEquals("1. one\n2. ", enter("1. one")); assertEquals("9. x\n10. ", enter("9. x")); assertEquals("a\n  3) q\n  4) ", enter("a\n  3) q")) }
    @Test fun bulletListContinues() { assertEquals("- a\n- ", enter("- a")); assertEquals("* a\n* ", enter("* a")); assertEquals("• a\n• ", enter("• a")) }
    @Test fun emptyItemEndsList() { assertEquals("1. a\n", enter("1. a\n2. ")); assertEquals("- a\n", enter("- a\n- ")) }
    @Test fun plainTextUntouched() { assertEquals("hello\n", enter("hello")) }
    @Test fun enterInMiddleContinuesThatLine() {
        val t = "1. a\n2. b\n3. c"; val nl = 4          // "1. a" + Enter pressed after a
        val e = TextTools.continueList(t, nl)!!
        assertEquals("\n2. ", e.text)
    }

    @Test fun shortMessageNotCut() { assertNull(TextTools.cut("hi there")); assertNull(TextTools.cut((1..10).joinToString("\n") { "line $it" })) }
    @Test fun longMessageCut() {
        val t = (1..40).joinToString("\n") { "line number $it" }
        val c = TextTools.cut(t)!!
        assertTrue(c.lines().size <= 9); assertTrue(c.startsWith("line number 1"))
    }
    @Test fun cutClosesFence() {
        val t = "```kotlin\n" + (1..40).joinToString("\n") { "val a$it = $it" } + "\n```"
        val c = TextTools.cut(t)!!
        assertTrue(c.trimEnd().endsWith("```"))
        assertEquals(0, Regex("```").findAll(c).count() % 2)
    }
    @Test fun longSingleParagraphCut() { assertNotNull(TextTools.cut("word ".repeat(300))) }

    @Test fun detectsLanguages() {
        assertEquals("python", CodeDetect.language("def add(a, b):\n    return a + b\n\nprint(add(1, 2))"))
        assertEquals("kotlin", CodeDetect.language("fun main() {\n    val x = 5\n    println(x)\n}"))
        assertEquals("java", CodeDetect.language("public class A {\n    public static void main(String[] args) {\n        System.out.println(\"hi\");\n    }\n}"))
        assertEquals("javascript", CodeDetect.language("const x = 5;\nfunction f(a) { return a * 2; }\nconsole.log(f(x));"))
        assertEquals("html", CodeDetect.language("<div class=\"a\"><p>Hello</p></div>"))
        assertEquals("html", CodeDetect.language("<!DOCTYPE html>\n<html><body><h1>Hi</h1></body></html>"))
        assertEquals("json", CodeDetect.language("{\n  \"name\": \"x\",\n  \"n\": 3\n}"))
        assertEquals("sql", CodeDetect.language("SELECT id, name FROM users WHERE age > 18;"))
        assertEquals("c", CodeDetect.language("#include <stdio.h>\nint main() {\n    printf(\"hi\");\n    return 0;\n}"))
        assertEquals("cpp", CodeDetect.language("#include <iostream>\nint main() {\n    std::cout << \"hi\";\n}"))
        assertEquals("go", CodeDetect.language("package main\n\nfunc main() {\n    fmt.Println(\"hi\")\n}"))
        assertEquals("rust", CodeDetect.language("fn main() {\n    let mut x = 5;\n    println!(\"{}\", x);\n}"))
        assertEquals("php", CodeDetect.language("<?php\n\$a = 5;\necho \$a;"))
        assertEquals("bash", CodeDetect.language("#!/bin/bash\nsudo apt install git\ncd /tmp"))
        assertEquals("css", CodeDetect.language(".box {\n  color: red;\n  margin: 0;\n}"))
    }

    @Test fun normalChatIsNotCode() {
        for (t in listOf("Bhai ek problem ho gayi yr, tu help kar dega?", "I will call you at 5. Is that ok?", "Check this out: (really good) and, well, done.",
            "Let me know if you want to come for dinner tonight, we are making biryani", "ok", "https://example.com/page", "Meeting at 10:30 - room 4; bring laptop",
            "def: a word that means something", "1. buy milk\n2. buy eggs\n3. call mom"))
            assertNull(t, CodeDetect.language(t))
    }

    @Test fun wrapFences() {
        assertEquals("```python\ndef f():\n    return 1\n```", CodeDetect.wrap("def f():\n    return 1\n"))
        assertEquals("hello there", CodeDetect.wrap("hello there"))
        val already = "```js\nlet a = 1;\n```"
        assertEquals(already, CodeDetect.wrap(already))
    }
}
