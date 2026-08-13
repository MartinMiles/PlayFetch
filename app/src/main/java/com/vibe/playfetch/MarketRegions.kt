/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import java.util.Locale

data class MarketRegion(
    val countryCode: String,
    val displayName: String,
    val languageCode: String
) {
    val flagEmoji: String
        get() = countryCode.uppercase(Locale.ROOT).map { letter ->
            String(Character.toChars(0x1F1E6 + (letter - 'A')))
        }.joinToString("")

    val locale: Locale
        get() = Locale.Builder()
            .setLanguage(languageCode)
            .setRegion(countryCode)
            .build()

    override fun toString(): String = "$flagEmoji  $displayName ($countryCode)"
}

object MarketRegions {
    private val primaryLanguages = mapOf(
        "AE" to "ar", "AR" to "es", "AT" to "de", "AU" to "en", "BE" to "nl",
        "BR" to "pt", "CA" to "en", "CH" to "de", "CL" to "es", "CN" to "zh",
        "CO" to "es", "CZ" to "cs", "DE" to "de", "DK" to "da", "EG" to "ar",
        "ES" to "es", "FI" to "fi", "FR" to "fr", "GB" to "en", "GR" to "el",
        "HK" to "zh", "HU" to "hu", "ID" to "id", "IE" to "en", "IL" to "he",
        "IN" to "hi", "IT" to "it", "JP" to "ja", "KR" to "ko", "MX" to "es",
        "MY" to "ms", "NL" to "nl", "NO" to "nb", "NZ" to "en", "PE" to "es",
        "PH" to "fil", "PL" to "pl", "PT" to "pt", "RO" to "ro", "RU" to "ru",
        "SA" to "ar", "SE" to "sv", "SG" to "en", "TH" to "th", "TR" to "tr",
        "TW" to "zh", "UA" to "uk", "US" to "en", "VN" to "vi", "ZA" to "en"
    )

    fun all(displayLocale: Locale = Locale.getDefault()): List<MarketRegion> {
        val alphabetical = Locale.getISOCountries()
            .map { code ->
                val locale = Locale.Builder().setLanguage("en").setRegion(code).build()
                MarketRegion(
                    countryCode = code,
                    displayName = locale.getDisplayCountry(displayLocale).ifBlank { code },
                    languageCode = primaryLanguages[code] ?: "en"
                )
            }
            .sortedBy { it.displayName.lowercase(displayLocale) }

        val pinnedCodes = listOf("GB", "US")
        val pinned = pinnedCodes.mapNotNull { code ->
            alphabetical.firstOrNull { it.countryCode == code }
        }
        return pinned + alphabetical.filterNot { it.countryCode in pinnedCodes }
    }

    fun defaultCountryCode(): String =
        Locale.getDefault().country.takeIf { it.length == 2 } ?: "GB"
}
