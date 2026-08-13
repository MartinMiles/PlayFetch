/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadState(
    val active: Boolean = false,
    val progress: Int? = null,
    val message: String = "Ready. Free apps only.",
    val outputName: String? = null,
    val outputUri: String? = null,
    val outputMimeType: String? = null,
    val error: Boolean = false,
    val initialized: Boolean = false
)

object DownloadEvents {
    private val mutableState = MutableStateFlow(DownloadState())
    val state = mutableState.asStateFlow()

    fun update(value: DownloadState) {
        mutableState.value = value.copy(initialized = true)
    }
}
