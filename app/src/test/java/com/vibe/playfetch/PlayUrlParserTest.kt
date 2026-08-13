/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayUrlParserTest {
    @Test
    fun extractsPackageFromPlayUrl() {
        assertEquals(
            "co.uk.Nationwide.Mobile",
            PlayUrlParser.extractPackageName(
                "https://play.google.com/store/apps/details?id=co.uk.Nationwide.Mobile&hl=en_GB"
            )
        )
    }

    @Test
    fun extractsPackageFromSharedSentence() {
        assertEquals(
            "org.mozilla.firefox",
            PlayUrlParser.extractPackageName(
                "Try Firefox: https://play.google.com/store/apps/details?id=org.mozilla.firefox"
            )
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=org.mozilla.firefox",
            PlayUrlParser.extractPlayUrl(
                "Try Firefox: https://play.google.com/store/apps/details?id=org.mozilla.firefox)"
            )
        )
    }

    @Test
    fun acceptsMarketSchemeAndRawPackage() {
        assertEquals(
            "com.example.app",
            PlayUrlParser.extractPackageName("market://details?id=com.example.app")
        )
        assertEquals("com.example.app", PlayUrlParser.extractPackageName("com.example.app"))
        assertEquals(
            "com.example.app",
            PlayUrlParser.extractPackageName(
                "http://play.google.com/store/apps/details?id=com.example.app"
            )
        )
    }

    @Test
    fun extractsMarketCountryWhenPresent() {
        assertEquals(
            "GB",
            PlayUrlParser.extractMarketCountry(
                "https://play.google.com/store/apps/details?id=co.uk.Nationwide.Mobile&gl=gb"
            )
        )
        assertNull(PlayUrlParser.extractMarketCountry("com.example.app"))
    }

    @Test
    fun rejectsLookalikeHostsAndInvalidPackages() {
        assertNull(
            PlayUrlParser.extractPackageName(
                "https://play.google.com.attacker.test/store/apps/details?id=com.example.app"
            )
        )
        assertNull(PlayUrlParser.extractPackageName("not a package"))
    }
}
