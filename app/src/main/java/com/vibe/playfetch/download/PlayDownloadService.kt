/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.vibe.playfetch.MainActivity
import com.vibe.playfetch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlayDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_description)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val countryCode = intent?.getStringExtra(EXTRA_COUNTRY_CODE).orEmpty()
        val languageCode = intent?.getStringExtra(EXTRA_LANGUAGE_CODE).orEmpty()
        val regionLabel = intent?.getStringExtra(EXTRA_REGION_LABEL).orEmpty()

        if (packageName.isBlank() || countryCode.isBlank() || languageCode.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeJob?.isActive == true) return START_NOT_STICKY

        val starting = getString(R.string.download_starting)
        startForeground(NOTIFICATION_ID, buildProgressNotification(starting, null))
        DownloadEvents.update(DownloadState(active = true, message = starting))

        activeJob = serviceScope.launch {
            try {
                val result = PlayDownloadEngine(applicationContext).download(
                    packageName = packageName,
                    countryCode = countryCode,
                    languageCode = languageCode,
                    regionLabel = regionLabel
                ) { message, progress ->
                    DownloadEvents.update(
                        DownloadState(active = true, progress = progress, message = message)
                    )
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(message, progress)
                    )
                }

                val kind = if (result.wasSplitBundle) "signed split archive" else "APK"
                val message = "Saved ${result.appName} ${result.versionName} $kind from ${result.source} to ${result.output.displayPath}"
                persistDownload(message, result.output)
                DownloadEvents.update(
                    DownloadState(
                        active = false,
                        progress = 100,
                        message = message,
                        outputName = result.output.displayName,
                        outputUri = result.output.contentUri,
                        outputMimeType = result.output.mimeType
                    )
                )
                notificationManager.notify(NOTIFICATION_ID, buildFinishedNotification(message, false))
            } catch (throwable: Throwable) {
                Log.e(TAG, "Download failed", throwable)
                val message = "Download failed: ${throwable.message ?: "Unknown error"}"
                persistStatus(message)
                DownloadEvents.update(
                    DownloadState(active = false, message = message, error = true)
                )
                notificationManager.notify(NOTIFICATION_ID, buildFinishedNotification(message, true))
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotification(message: String, progress: Int?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playfetch)
            .setContentTitle("PlayFetch")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .build()

    private fun buildFinishedNotification(message: String, failed: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playfetch)
            .setContentTitle(if (failed) "PlayFetch failed" else "PlayFetch complete")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(if (failed) openAppIntent() else openDownloadsIntent())
            .setAutoCancel(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openDownloadsIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun persistStatus(message: String) {
        getSharedPreferences(MainActivity.PREFERENCES, MODE_PRIVATE).edit {
            putString(MainActivity.KEY_LAST_STATUS, message)
        }
    }

    private fun persistDownload(message: String, output: PublishedDownload) {
        getSharedPreferences(MainActivity.PREFERENCES, MODE_PRIVATE).edit {
            putString(MainActivity.KEY_LAST_STATUS, message)
            putString(MainActivity.KEY_LAST_OUTPUT_NAME, output.displayName)
            putString(MainActivity.KEY_LAST_OUTPUT_URI, output.contentUri)
            putString(MainActivity.KEY_LAST_OUTPUT_MIME, output.mimeType)
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_LANGUAGE_CODE = "language_code"
        const val EXTRA_REGION_LABEL = "region_label"

        private const val CHANNEL_ID = "playfetch_downloads"
        private const val NOTIFICATION_ID = 2107
        private const val TAG = "PlayDownloadService"
    }
}
