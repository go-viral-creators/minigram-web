package com.kafisir.wetalks.ui

/** Small pure helpers for the message composer and long messages (no Android, so they are unit tested). */
object TextTools {
    class Edit(val start: Int, val end: Int, val text: String, val cursor: Int)

    private val LIST_LINE = Regex("^([ \\t]*)(?:(\\d{1,4})([.)])|([-*•]))[ \\t]+(.*)$")

    /**
     * The user just pressed Enter (a '\n' now sits at [nl]). If the line above is a list item, return the edit that continues the list:
     * "1. a" -> new line "2. ", "- a" -> new line "- ". Enter on an empty item ends the list (the marker is removed).
     */
    fun continueList(text: CharSequence, nl: Int): Edit? {
        if (nl < 0 || nl >= text.length || text[nl] != '\n') return null
        var ls = nl
        while (ls > 0 && text[ls - 1] != '\n') ls--
        val line = text.subSequence(ls, nl).toString()
        val m = LIST_LINE.matchEntire(line) ?: return null
        val indent = m.groupValues[1]
        val body = m.groupValues[5]
        if (body.isBlank()) return Edit(ls, nl + 1, "", ls)                // a second Enter: back to plain text
        val marker = if (m.groupValues[2].isNotEmpty()) (m.groupValues[2].toInt() + 1).toString() + m.groupValues[3] + " " else m.groupValues[4] + " "
        val ins = "\n$indent$marker"
        return Edit(nl, nl + 1, ins, nl + ins.length)
    }

    /** First part of a long message, or null when it is short enough to show whole. Open code fences are closed again. */
    fun cut(text: String, maxLines: Int = 9, maxChars: Int = 420): String? {
        val lines = text.split('\n')
        if (lines.size <= 11 && text.length <= 560) return null
        val sb = StringBuilder()
        var n = 0
        for (l in lines) {
            if (n >= maxLines || sb.length + l.length > maxChars) {
                if (n == 0) sb.append(l.take(maxChars))
                break
            }
            if (n > 0) sb.append('\n')
            sb.append(l); n++
        }
        val head = sb.toString().trimEnd()
        if (text.length - head.length < 120 && lines.size <= 12) return null
        val fences = Regex("(?m)^\\s*```").findAll(head).count()
        return if (fences % 2 == 1) head + "\n```" else head
    }
}

/** Works out whether pasted or typed text is source code, and in which language, so it can be sent as a Markdown code block. */
object CodeDetect {
    private class Sig(val re: Regex, val w: Int)
    private fun sig(w: Int, p: String) = Sig(Regex(p, setOf(RegexOption.MULTILINE)), w)
    private fun sigI(w: Int, p: String) = Sig(Regex(p, setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)), w)

    private val LANGS: Map<String, List<Sig>> = linkedMapOf(
        "python" to listOf(sig(4, "^\\s*def \\w+\\(.*\\)\\s*(->.*)?:"), sig(4, "^\\s*class \\w+(\\(.*\\))?:"), sig(3, "^\\s*(from [\\w.]+ import |import \\w+( as \\w+)?\\s*$)"),
            sig(2, "^\\s*(if|elif|else|for|while|try|except|finally|with)\\b.*:\\s*$"), sig(2, "\\bself\\."), sig(3, "__\\w+__"), sig(1, "\\bprint\\("), sig(1, "^\\s*#.*$"), sig(2, "\\bin range\\(")),
        "kotlin" to listOf(sig(4, "\\bfun \\w+\\("), sig(2, "\\b(val|var) \\w+(\\s*:\\s*[\\w<>?]+)?\\s*="), sig(4, "@Composable"), sig(3, "\\b(data|sealed) class\\b"), sig(1, "\\bprintln\\("), sig(3, "\\bwhen\\s*(\\(.*\\))?\\s*\\{"), sig(2, "\\?\\.|\\?:")),
        "java" to listOf(sig(4, "\\bpublic\\s+(static\\s+)?(final\\s+)?(void|class|int|String|boolean)\\b"), sig(5, "System\\.out\\.print"), sig(3, "\\b(private|protected)\\s+(static\\s+)?\\w+"), sig(5, "^\\s*import java\\."), sig(3, "@Override"), sig(2, "\\bnew \\w+\\(")),
        "javascript" to listOf(sig(3, "\\b(const|let) \\w+\\s*="), sig(3, "\\bfunction\\s*\\w*\\s*\\("), sig(2, "=>"), sig(5, "console\\.(log|error|warn)\\("), sig(3, "\\b(document|window)\\."), sig(4, "\\brequire\\(|module\\.exports|export default"), sig(4, "^\\s*import .* from ['\"]"), sig(2, "\\bvar \\w+\\s*="), sig(2, "===|!==")),
        "c" to listOf(sig(5, "^\\s*#include\\s*<\\w+\\.h>"), sig(5, "\\bint main\\s*\\("), sig(4, "\\b(printf|scanf)\\("), sig(2, "\\b(int|char|float|double|void|long)\\s+\\*?\\w+\\s*\\("), sig(1, "\\bmalloc\\(|\\bsizeof\\(")),
        "cpp" to listOf(sig(6, "\\bstd::"), sig(5, "\\bcout\\s*<<|\\bcin\\s*>>"), sig(5, "^\\s*using namespace\\b"), sig(5, "^\\s*#include\\s*<(iostream|vector|string|map|algorithm)>"), sig(3, "\\btemplate\\s*<")),
        "csharp" to listOf(sig(6, "^\\s*using System"), sig(5, "Console\\.Write(Line)?\\("), sig(3, "^\\s*namespace \\w+"), sig(2, "\\b(public|private)\\s+(static\\s+)?(void|class|string|int)\\b"), sig(2, "\\{ get; set; \\}")),
        "go" to listOf(sig(4, "^package \\w+\\s*$"), sig(4, "^\\s*func (\\(\\w+ \\*?\\w+\\) )?\\w+\\("), sig(5, "\\bfmt\\.Print"), sig(2, ":="), sig(3, "^\\s*import \\(")),
        "rust" to listOf(sig(5, "\\bfn \\w+\\s*(<.*>)?\\("), sig(5, "\\blet mut\\b"), sig(5, "\\bprintln!\\("), sig(2, "\\bimpl\\b"), sig(2, "->\\s*\\w+\\s*\\{"), sig(3, "^\\s*use \\w+(::\\w+)+")),
        "php" to listOf(sig(7, "<\\?php"), sig(2, "\\$\\w+\\s*="), sig(2, "\\becho\\b"), sig(2, "->\\w+\\(")),
        "sql" to listOf(sigI(6, "\\bselect\\b[\\s\\S]+\\bfrom\\b"), sigI(6, "\\b(insert into|create table|update \\w+ set|delete from|alter table|drop table)\\b"), sigI(1, "\\bwhere\\b"), sigI(2, "\\b(group by|order by|inner join|left join)\\b")),
        "bash" to listOf(sig(7, "^#!/bin/(ba|z)?sh"), sig(3, "^\\s*(sudo|apt|apt-get|pip|pip3|npm|npx|yarn|git|cd|ls|chmod|chown|mkdir|curl|wget|export|source|docker|kubectl)\\s+[-\\w./\$\"']+"), sig(1, "\\$\\(|\\$\\{?\\w+"), sig(2, "^\\s*(fi|done|then|esac)\\s*$"), sig(2, "^\\s*echo\\s+")),
        "css" to listOf(sig(6, "^\\s*[.#]?[\\w-]+(\\s*[,>+~]\\s*[.#]?[\\w-]+)*\\s*\\{[^}]*:[^}]*;"), sig(3, "^\\s*[\\w-]+\\s*:\\s*[^;{}]+;\\s*\$"), sig(3, "@media|@keyframes|@import")),
        "swift" to listOf(sig(6, "^\\s*import (UIKit|SwiftUI|Foundation)"), sig(4, "\\bfunc \\w+\\(.*\\)\\s*(->\\s*\\w+)?\\s*\\{"), sig(2, "\\blet \\w+\\s*[:=]"), sig(2, "\\bguard let\\b|\\bif let\\b")),
        "ruby" to listOf(sig(3, "^\\s*def \\w+[^:\\n]*\$"), sig(3, "^\\s*end\\s*\$"), sig(3, "\\bputs\\s"), sig(2, "\\bdo \\|\\w+\\|"), sig(2, "^\\s*require ['\"]")),
    )

    private val HTML_START = Regex("(?is)^\\s*(<!doctype html|<html|<head|<body|<div|<span|<section|<main|<header|<footer|<nav|<script|<style|<table|<ul|<ol|<form|<p[ >]|<h[1-6][ >]|<a |<img|<button|<input|<svg|<canvas)")
    private val TAG = Regex("</?[a-zA-Z][a-zA-Z0-9-]*(\\s[^<>]*)?/?>")

    /** The Markdown language tag ("python", "html" …) when [text] looks like code, otherwise null. */
    fun language(text: String): String? {
        val s = text.trim()
        if (s.length < 10 || s.contains("```")) return null
        if (Regex("(?m)^\\s*(https?://\\S+\\s*)+\$").matches(s)) return null
        val lines = s.split('\n')
        if (s.startsWith("<?xml")) return "xml"
        if (HTML_START.containsMatchIn(s) && TAG.findAll(s).count() >= 2) return "html"
        if (((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) && Regex("\"[^\"\\n]+\"\\s*:").containsMatchIn(s)) return "json"

        var best: String? = null; var bestScore = 0
        for ((lang, sigs) in LANGS) {
            var sc = 0
            for (g in sigs) { val n = g.re.findAll(s).count(); if (n > 0) sc += g.w + minOf(n - 1, 3) }
            if (sc > bestScore) { bestScore = sc; best = lang }
        }
        // generic code shapes: statements ending in ; { } ) and indentation
        val nonEmpty = lines.filter { it.isNotBlank() }
        val endings = nonEmpty.count { l -> l.trimEnd().let { it.endsWith(";") || it.endsWith("{") || it.endsWith("}") || it.endsWith(")") || it.endsWith(":") } }
        val indented = nonEmpty.count { it.startsWith("    ") || it.startsWith("\t") || it.startsWith("  ") }
        var generic = 0
        if (nonEmpty.size >= 2) {
            if (endings * 100 / nonEmpty.size >= 50) generic++
            if (indented * 100 / nonEmpty.size >= 25) generic++
            if (Regex("[{}();=<>\\[\\]]").findAll(s).count() * 100 / s.length >= 6) generic++
        }
        val need = if (nonEmpty.size >= 2) 4 else 5
        if (best == null || bestScore < need) return null
        if (nonEmpty.size >= 2 && generic == 0 && bestScore < 7) return null
        return best
    }

    /** [text] as a fenced code block when it is code; unchanged otherwise. */
    fun wrap(text: String): String {
        val lang = language(text) ?: return text
        return "```" + lang + "\n" + text.trim('\n', '\r').trimEnd() + "\n```"
    }
}
