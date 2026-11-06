package ru.ttraum.kotsql.postgres

import java.text.Normalizer

internal actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
