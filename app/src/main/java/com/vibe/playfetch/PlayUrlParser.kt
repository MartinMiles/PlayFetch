/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object PlayUrlParser {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val urlPattern = Regex(
        "(?:https?://play\\.google\\.com/store/apps/details\\?[^\\s<>]+|market://details\\?[^\\s<>]+)",
        RegexOption.IGNORE_CASE
    )

    fun extractPlayUrl(input: String): String? =
        urlPattern.find(input.trim())
            ?.value
            ?.trimEnd('.', ',', ')', ']', '}', '\'', '"')

    fun extractPackageName(input: String): String? {
        val text = input.trim()
        if (packagePattern.matches(text)) return text

        val candidates = buildList {
            addAll(urlPattern.findAll(text).map { cleanUrl(it.value) })
            if (text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                text.startsWith("market://", ignoreCase = true)
            ) {
                add(cleanUrl(text))
            }
        }

        return candidates.firstNotNullOfOrNull(::packageFromUri)
    }

    fun extractMarketCountry(input: String): String? =
        urlPattern.findAll(input.trim())
            .map { cleanUrl(it.value) }
            .plus(sequenceOf(input.trim()))
            .mapNotNull { value -> queryParameter(value, "gl") }
            .map { it.uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) }

    private fun packageFromUri(value: String): String? = runCatching {
        val uri = URI(value)
        val accepted = when (uri.scheme?.lowercase()) {
            "http", "https" -> uri.host.equals("play.google.com", ignoreCase = true) &&
                uri.path == "/store/apps/details"
            "market" -> uri.host.equals("details", ignoreCase = true)
            else -> false
        }
        if (!accepted) return null

        val packageName = queryParameter(value, "id")

        packageName?.takeIf(packagePattern::matches)
    }.getOrNull()

    private fun queryParameter(value: String, key: String): String? = runCatching {
        URI(value).rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2 && pieces[0].equals(key, ignoreCase = true)) {
                    URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.name())
                } else {
                    null
                }
            }
            ?.firstOrNull()
    }.getOrNull()

    private fun cleanUrl(value: String): String =
        value.trimEnd('.', ',', ')', ']', '}', '\'', '"')
}
