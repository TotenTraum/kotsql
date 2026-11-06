package ru.ttraum.kotsql.postgres

/**
 * Linux has no normalization short of a dependency on ICU, so this one is identity, which is right for all but a password
 * that isn't in normal form to begin with. The README's limitations list what that means.
 */
internal actual fun normalizeNfkc(text: String): String = text
