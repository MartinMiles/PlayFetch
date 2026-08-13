/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputLayout
import com.vibe.playfetch.download.DownloadEvents
import com.vibe.playfetch.download.DownloadState
import com.vibe.playfetch.download.OutputStore
import com.vibe.playfetch.download.PlayDownloadService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: EditText
    private lateinit var regionSpinner: Spinner
    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var shareTelegramButton: Button
    private lateinit var versionText: TextView
    private lateinit var marketAdapter: MarketRegionAdapter

    private val regions by lazy { MarketRegions.all() }
    private var pendingDownload: Pair<String, MarketRegion>? = null
    private var downloadedOutput: DownloadedOutput? = null
    private var downloadActive = false

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingDownload
            pendingDownload = null
            if (granted && request != null) {
                startDownload(request.first, request.second)
            } else if (!granted) {
                Toast.makeText(this, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlLayout = findViewById(R.id.urlLayout)
        urlInput = findViewById(R.id.urlInput)
        regionSpinner = findViewById(R.id.regionSpinner)
        downloadButton = findViewById(R.id.downloadButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        shareTelegramButton = findViewById(R.id.shareTelegramButton)
        versionText = findViewById(R.id.versionText)

        configureRegions()
        configureActions()
        consumeIntent(intent)
        restoreLastStatus()
        showCurrentVersion()
        observeDownloadState()
    }

    override fun onResume() {
        super.onResume()
        refreshDownloadedFileState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun configureRegions() {
        marketAdapter = MarketRegionAdapter(this, regions)
        regionSpinner.adapter = marketAdapter

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val selectedCode = preferences.getString(
            KEY_REGION,
            MarketRegions.defaultCountryCode()
        )
        val index = marketAdapter.positionOf(selectedCode)
        regionSpinner.setSelection(if (index >= 0) index else 0)
    }

    private fun configureActions() {
        urlInput.doAfterTextChanged { urlLayout.error = null }

        findViewById<Button>(R.id.pasteButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                urlInput.setText(text)
                urlInput.setSelection(text.length)
            }
        }

        downloadButton.setOnClickListener {
            downloadedOutput?.let(::deleteDownloadedFile) ?: validateAndDownload()
        }
        shareTelegramButton.setOnClickListener { downloadedOutput?.let(::shareToTelegram) }
    }

    private fun validateAndDownload() {
        val packageName = PlayUrlParser.extractPackageName(urlInput.text.toString())
        if (packageName == null) {
            urlLayout.error = getString(R.string.invalid_url)
            return
        }

        val region = marketAdapter.regionAt(regionSpinner.selectedItemPosition)
            ?: return
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            putString(KEY_REGION, region.countryCode)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = packageName to region
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        startDownload(packageName, region)
    }

    private fun startDownload(packageName: String, region: MarketRegion) {
        val serviceIntent = Intent(this, PlayDownloadService::class.java).apply {
            putExtra(PlayDownloadService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(PlayDownloadService.EXTRA_COUNTRY_CODE, region.countryCode)
            putExtra(PlayDownloadService.EXTRA_LANGUAGE_CODE, region.languageCode)
            putExtra(PlayDownloadService.EXTRA_REGION_LABEL, region.toString())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadEvents.state.collect { state ->
                    if (!state.initialized) return@collect
                    downloadActive = state.active
                    updatePrimaryButton()
                    regionSpinner.isEnabled = !state.active
                    urlInput.isEnabled = !state.active
                    statusText.text = state.message
                    progressBar.visibility = if (state.active) ProgressBar.VISIBLE else ProgressBar.GONE
                    progressBar.isIndeterminate = state.active && state.progress == null
                    state.progress?.let { progressBar.progress = it }
                    if (state.outputUri != null && state.outputMimeType != null) {
                        setDownloadedOutput(
                            DownloadedOutput(
                                name = state.outputName.orEmpty(),
                                uri = state.outputUri,
                                mimeType = state.outputMimeType
                            )
                        )
                    } else if (state.active) {
                        setDownloadedOutput(null)
                    }
                }
            }
        }
    }

    private fun consumeIntent(intent: Intent?) {
        val sharedText = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!sharedText.isNullOrBlank()) {
            val fieldValue = PlayUrlParser.extractPlayUrl(sharedText) ?: sharedText.trim()
            urlInput.setText(fieldValue)
            urlInput.setSelection(fieldValue.length)
            PlayUrlParser.extractMarketCountry(sharedText)?.let { countryCode ->
                val index = marketAdapter.positionOf(countryCode)
                if (index >= 0) regionSpinner.setSelection(index)
            }
        }
    }

    private fun restoreLastStatus() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val lastStatus = preferences.getString(KEY_LAST_STATUS, null)
        if (!lastStatus.isNullOrBlank()) statusText.text = lastStatus
        val outputUri = preferences.getString(KEY_LAST_OUTPUT_URI, null)
        val outputMime = preferences.getString(KEY_LAST_OUTPUT_MIME, null)
        if (!outputUri.isNullOrBlank() && !outputMime.isNullOrBlank()) {
            setDownloadedOutput(
                DownloadedOutput(
                    name = preferences.getString(KEY_LAST_OUTPUT_NAME, null).orEmpty(),
                    uri = outputUri,
                    mimeType = outputMime
                )
            )
        }
    }

    private fun setDownloadedOutput(output: DownloadedOutput?) {
        downloadedOutput = output
        shareTelegramButton.visibility = if (output == null) View.GONE else View.VISIBLE
        updatePrimaryButton()
    }

    private fun updatePrimaryButton() {
        downloadButton.isEnabled = !downloadActive
        downloadButton.setText(
            if (downloadedOutput == null) R.string.download else R.string.delete_file
        )
    }

    private fun refreshDownloadedFileState() {
        val output = downloadedOutput ?: return
        if (!OutputStore(this).exists(output.uri)) {
            clearDownloadedOutput(getString(R.string.downloaded_file_missing, output.name))
        }
    }

    private fun deleteDownloadedFile(output: DownloadedOutput) {
        if (OutputStore(this).delete(output.uri)) {
            clearDownloadedOutput(getString(R.string.file_deleted, output.name))
        } else {
            statusText.text = getString(R.string.delete_failed, output.name)
        }
    }

    private fun clearDownloadedOutput(message: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            remove(KEY_LAST_OUTPUT_NAME)
            remove(KEY_LAST_OUTPUT_URI)
            remove(KEY_LAST_OUTPUT_MIME)
            putString(KEY_LAST_STATUS, message)
        }
        setDownloadedOutput(null)
        statusText.text = message
        DownloadEvents.update(DownloadState(message = message))
    }

    @Suppress("DEPRECATION")
    private fun showCurrentVersion() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        versionText.text = getString(
            R.string.version_label,
            info.versionName.orEmpty(),
            build
        )
    }

    private fun shareToTelegram(output: DownloadedOutput) {
        val uri = output.uri.toUri()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = output.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, output.name.ifBlank { "APK" }, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val telegramPackage = TELEGRAM_PACKAGES.firstOrNull { packageName ->
            sendIntent.cloneFilter().apply { setPackage(packageName) }
                .resolveActivity(packageManager) != null
        }
        val target = if (telegramPackage != null) {
            Intent(sendIntent).setPackage(telegramPackage)
        } else {
            Intent.createChooser(sendIntent, getString(R.string.share_package))
        }
        try {
            startActivity(target)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val PREFERENCES = "playfetch_preferences"
        const val KEY_REGION = "market_region"
        const val KEY_LAST_STATUS = "last_status"
        const val KEY_LAST_OUTPUT_NAME = "last_output_name"
        const val KEY_LAST_OUTPUT_URI = "last_output_uri"
        const val KEY_LAST_OUTPUT_MIME = "last_output_mime"

        private val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram"
        )
    }
}

private data class DownloadedOutput(
    val name: String,
    val uri: String,
    val mimeType: String
)
