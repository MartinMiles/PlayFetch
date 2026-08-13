/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRegionsTest {
    @Test
    fun includesUkAndUsWithUsefulLocales() {
        val regions = MarketRegions.all(Locale.US)
        val uk = regions.single { it.countryCode == "GB" }
        val us = regions.single { it.countryCode == "US" }

        assertEquals("en", uk.languageCode)
        assertEquals("GB", uk.locale.country)
        assertEquals("en", us.languageCode)
        assertEquals("GB", regions[0].countryCode)
        assertEquals("US", regions[1].countryCode)
        assertEquals("🇬🇧", uk.flagEmoji)
        assertEquals("🇺🇸", us.flagEmoji)
        assertTrue(
            regions.drop(2).map { it.displayName.lowercase(Locale.US) }.zipWithNext()
                .all { (first, second) -> first <= second }
        )
        assertTrue(regions.size > 200)
    }
}
