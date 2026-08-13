/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch.download

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream

data class PublishedDownload(
    val displayName: String,
    val displayPath: String,
    val contentUri: String,
    val mimeType: String
)

class OutputStore(private val context: Context) {
    fun publish(source: File, requestedName: String, mimeType: String): PublishedDownload {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(source, requestedName, mimeType)
        } else {
            publishToLegacyDownloads(source, requestedName, mimeType)
        }
    }

    fun exists(contentUri: String): Boolean {
        val uri = trustedUri(contentUri) ?: return false
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun delete(contentUri: String): Boolean {
        val uri = trustedUri(contentUri) ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0 || !exists(contentUri)
        } catch (_: Exception) {
            false
        }
    }

    private fun trustedUri(value: String): Uri? = runCatching {
        value.toUri().takeIf { uri ->
            uri.scheme == "content" &&
                (uri.authority == "media" || uri.authority == "${context.packageName}.files")
        }
    }.getOrNull()

    @SuppressLint("NewApi")
    private fun publishWithMediaStore(
        source: File,
        requestedName: String,
        mimeType: String
    ): PublishedDownload {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: error("Android could not create the output file in Download")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, 128 * 1024) }
            } ?: error("Android could not open the output file in Download")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }

        val actualName = resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { requestedName }

        return PublishedDownload(
            displayName = actualName,
            displayPath = "Download/$actualName",
            contentUri = uri.toString(),
            mimeType = mimeType
        )
    }

    @Suppress("DEPRECATION")
    private fun publishToLegacyDownloads(
        source: File,
        requestedName: String,
        mimeType: String
    ): PublishedDownload {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) {
            error("Android could not create the Download directory")
        }

        val target = uniqueFile(downloads, requestedName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            target
        )
        return PublishedDownload(
            displayName = target.name,
            displayPath = target.absolutePath,
            contentUri = uri.toString(),
            mimeType = mimeType
        )
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct

        val extensionIndex = requestedName.lastIndexOf('.')
        val stem = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(directory, "$stem ($counter)$extension")
            if (!candidate.exists()) return candidate
            counter++
        }
    }
}
