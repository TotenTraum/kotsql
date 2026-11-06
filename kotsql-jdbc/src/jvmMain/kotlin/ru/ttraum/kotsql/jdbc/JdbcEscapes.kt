package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.SqlDialect
import java.sql.SQLException

/** Finds where comments and quoted text end, which is where a brace or a comma isn't what it looks like. */
private class SqlScanner(private val sql: String, dialect: SqlDialect) {
    private val syntax = dialect.syntax

    /** The index after the quoted text or comment that starts at [i], or [i] if none does. */
    fun skip(i: Int): Int = syntax.opaqueEnd(sql, i)

    /** The index of the [close] that matches the opening at [open], or -1. */
    fun matching(open: Int, opening: Char, close: Char): Int {
        var depth = 0
        var i = open
        while (i < sql.length) {
            val after = skip(i)
            if (after != i) { i = after; continue }
            if (sql[i] == opening) depth++
            if (sql[i] == close && --depth == 0) return i
            i++
        }
        return -1
    }
}

/** Splits a list of arguments at its top-level commas. */
private fun splitArguments(text: String, dialect: SqlDialect): List<String> {
    if (text.isBlank()) return emptyList()
    val scanner = SqlScanner(text, dialect)
    val arguments = ArrayList<String>()
    var depth = 0
    var from = 0
    var i = 0
    while (i < text.length) {
        val after = scanner.skip(i)
        if (after != i) { i = after; continue }
        when (text[i]) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) {
                arguments += text.substring(from, i).trim()
                from = i + 1
            }
        }
        i++
    }
    arguments += text.substring(from).trim()
    return arguments
}

private val ESCAPE_KEYWORD = Regex("""^\s*(\w+)\s*""")

/** Rewrites JDBC's escapes in braces, such as `{fn UCASE(a)}` or `{d '2024-01-02'}`, to the database's own SQL. */
internal fun translateEscapes(sql: String, dialect: SqlDialect): String {
    if ('{' !in sql) return sql
    val scanner = SqlScanner(sql, dialect)
    val out = StringBuilder(sql.length)
    var i = 0
    while (i < sql.length) {
        val after = scanner.skip(i)
        if (after != i) {
            out.append(sql, i, after)
            i = after
            continue
        }
        if (sql[i] != '{') {
            out.append(sql[i++])
            continue
        }
        val close = scanner.matching(i, '{', '}')
        val body = if (close < 0) null else sql.substring(i + 1, close)
        val translated = body?.let { translateEscape(translateEscapes(it, dialect), dialect) }
        if (translated == null) {
            out.append(sql[i++]) // no escape of JDBC's
        } else {
            out.append(translated)
            i = close + 1
        }
    }
    return out.toString()
}

private fun translateEscape(body: String, dialect: SqlDialect): String? {
    val keyword = ESCAPE_KEYWORD.find(body) ?: return if (body.trimStart().startsWith("?")) {
        throw SQLException("A call with a return value takes Connection.prepareCall", "0A000")
    } else null
    val rest = body.substring(keyword.range.last + 1).trim()
    return when (keyword.groupValues[1].lowercase()) {
        "fn" -> translateFunction(rest, dialect)
        "d" -> dialect.temporalLiteral("DATE", rest)
        "t" -> dialect.temporalLiteral("TIME", rest)
        "ts" -> dialect.temporalLiteral("TIMESTAMP", rest)
        "escape" -> "ESCAPE $rest"
        "oj" -> rest
        "limit" -> "LIMIT $rest"
        "call" -> "CALL $rest"
        else -> null
    }
}

private fun translateFunction(text: String, dialect: SqlDialect): String {
    val open = text.indexOf('(')
    if (open < 0) return dialect.escapeFunction(text.trim(), emptyList()) ?: text
    val close = SqlScanner(text, dialect).matching(open, '(', ')')
    if (close < 0) throw SQLException("Malformed escape {fn $text}", "42601")
    val name = text.substring(0, open).trim()
    val arguments = splitArguments(text.substring(open + 1, close), dialect)
    return dialect.escapeFunction(name, arguments) ?: text
}

/** A call as `prepareCall` takes it. */
internal class ParsedCall(val returnsValue: Boolean, val name: String, val arguments: List<String>)

private val CALL_SYNTAX = Regex("""^\s*(\{\s*)?(\?\s*=\s*)?call\s+(.+?)\s*(})?\s*;?\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

internal fun parseCall(sql: String, dialect: SqlDialect): ParsedCall {
    val match = CALL_SYNTAX.find(sql) ?: throw SQLException("Not a call: '$sql'. It takes the form {? = call name(?, ?)} or {call name(?, ?)}", "42601")
    val (brace, result, target, closing) = match.destructured
    if (brace.isNotEmpty() != closing.isNotEmpty()) throw SQLException("Unbalanced braces in '$sql'", "42601")
    val open = target.indexOf('(')
    if (open < 0) return ParsedCall(result.isNotEmpty(), target.trim(), emptyList())
    val close = SqlScanner(target, dialect).matching(open, '(', ')')
    if (close != target.trimEnd().length - 1) throw SQLException("Malformed call '$sql'", "42601")
    return ParsedCall(result.isNotEmpty(), target.substring(0, open).trim(), splitArguments(target.substring(open + 1, close), dialect))
}
