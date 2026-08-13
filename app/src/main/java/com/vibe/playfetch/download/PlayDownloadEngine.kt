/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch.download

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.android.apksig.ApkVerifier
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.data.providers.DeviceInfoProvider
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.R as GPlayApiR
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class DownloadResult(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val output: PublishedDownload,
    val wasSplitBundle: Boolean,
    val source: String
)

private data class PreparedDownload(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val file: File,
    val outputName: String,
    val mimeType: String,
    val wasSplitBundle: Boolean,
    val source: String
)

private data class SignerExpectations(
    val sha256: Set<String> = emptySet(),
    val sha1: Set<String> = emptySet()
)

private data class VerifiedApk(
    val appName: String,
    val versionName: String,
    val versionCode: Long
)

private data class MirrorListing(
    val downloadUrl: String,
    val signerSha1: String?
)

class PlayDownloadEngine(private val context: Context) {
    fun download(
        packageName: String,
        countryCode: String,
        languageCode: String,
        regionLabel: String,
        onProgress: (message: String, progress: Int?) -> Unit
    ): DownloadResult {
        val locale = Locale.Builder()
            .setLanguage(languageCode)
            .setRegion(countryCode)
            .build()
        val properties = loadDeviceProperties()
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith(TEMP_DIRECTORY_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        val workDirectory = File(context.filesDir, "$TEMP_DIRECTORY_PREFIX${UUID.randomUUID()}")
        check(workDirectory.mkdirs()) { "Could not create a temporary download directory" }

        try {
            var playMetadata: App? = null
            val prepared = try {
            onProgress("Creating an anonymous $regionLabel Play session…", null)
            val authData = authenticate(properties, locale)

            onProgress("Looking up $packageName in $regionLabel…", null)
            val app = AppDetailsHelper(authData).getAppByPackageName(packageName)
            playMetadata = app
            check(app.packageName == packageName) { "Google Play returned a different package" }
            if (!app.isFree && app.price.isNotBlank()) {
                error("${app.displayName} is not free (${app.price}). Anonymous downloads support free apps only.")
            }

            onProgress("Requesting ${app.displayName} ${app.versionName} from Google Play…", null)
            val files = PurchaseHelper(authData)
                .purchase(packageName, app.versionCode, app.offerType)
                .filter { it.url.isNotBlank() }
            check(files.isNotEmpty()) { "Google Play did not return any downloadable files" }

            val downloadedFiles = downloadAndVerifyFiles(
                packageName = packageName,
                versionCode = app.versionCode,
                signerExpectations = signerExpectations(app),
                files = files,
                workDirectory = workDirectory,
                onProgress = onProgress
            )

            val isSingleApk = files.size == 1 && files.single().type == PlayFile.Type.BASE
            val safePackage = safeName(packageName)
            val version = app.versionCode
            val outputFile: File
            val outputName: String
            val mimeType: String

            if (isSingleApk) {
                outputFile = downloadedFiles.single().second
                outputName = "$safePackage-$version.apk"
                mimeType = "application/vnd.android.package-archive"
            } else {
                onProgress("Packing the original signed APK splits…", null)
                outputName = "$safePackage-$version.apks"
                outputFile = File(workDirectory, outputName)
                createApksArchive(
                    target = outputFile,
                    appName = app.displayName,
                    packageName = packageName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    regionLabel = regionLabel,
                    files = downloadedFiles
                )
                mimeType = "application/zip"
            }

            onProgress("Publishing $outputName to Download…", 100)
            PreparedDownload(
                appName = app.displayName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                file = outputFile,
                outputName = outputName,
                mimeType = mimeType,
                wasSplitBundle = !isSingleApk,
                source = "Google Play"
            )
            } catch (googleFailure: Throwable) {
                onProgress(
                    "Google delivery is unavailable; checking a signer-verified APK mirror...",
                    null
                )
                try {
                    prepareMirrorDownload(
                        packageName = packageName,
                        locale = locale,
                        playMetadata = playMetadata,
                        workDirectory = workDirectory,
                        onProgress = onProgress
                    )
                } catch (mirrorFailure: Throwable) {
                    throw IllegalStateException(
                        "Google delivery failed (${shortReason(googleFailure)}); " +
                            "verified mirror fallback failed (${shortReason(mirrorFailure)})",
                        mirrorFailure
                    )
                }
            }

            onProgress("Publishing ${prepared.outputName} to Download...", 100)
            val published = OutputStore(context).publish(
                prepared.file,
                prepared.outputName,
                prepared.mimeType
            )
            return DownloadResult(
                appName = prepared.appName,
                packageName = packageName,
                versionName = prepared.versionName,
                versionCode = prepared.versionCode,
                output = published,
                wasSplitBundle = prepared.wasSplitBundle,
                source = prepared.source
            )
        } catch (throwable: Throwable) {
            throw explainFailure(throwable, countryCode)
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun prepareMirrorDownload(
        packageName: String,
        locale: Locale,
        playMetadata: App?,
        workDirectory: File,
        onProgress: (String, Int?) -> Unit
    ): PreparedDownload {
        onProgress("Resolving the latest signed APK mirror copy...", null)
        val listing = fetchMirrorListing(packageName, locale)
        val target = File(workDirectory, "mirror.apk")

        downloadMirrorFile(listing.downloadUrl, target) { bytes, totalBytes ->
            val progress = totalBytes
                ?.takeIf { it > 0 }
                ?.let { ((bytes * 100L) / it).toInt().coerceIn(0, 99) }
            onProgress("Downloading signer-verified mirror APK...", progress)
        }

        val googleSigners = playMetadata?.let(::signerExpectations) ?: SignerExpectations()
        val expectations = googleSigners.copy(
            sha1 = googleSigners.sha1 + listOfNotNull(listing.signerSha1?.lowercase())
        )
        val verified = verifyApk(
            file = target,
            expectedPackage = packageName,
            expectedVersionCode = playMetadata?.versionCode?.takeIf { it > 0 },
            signerExpectations = expectations
        )

        check(expectations.sha256.isNotEmpty() || expectations.sha1.isNotEmpty()) {
            "No trusted signer metadata was available for the mirror APK"
        }

        return PreparedDownload(
            appName = verified.appName,
            versionName = verified.versionName,
            versionCode = verified.versionCode,
            file = target,
            outputName = "${safeName(packageName)}-${verified.versionCode}.apk",
            mimeType = "application/vnd.android.package-archive",
            wasSplitBundle = false,
            source = if (googleSigners.sha256.isNotEmpty()) {
                "verified mirror (signer matched Google Play)"
            } else {
                "verified APK mirror"
            }
        )
    }

    private fun fetchMirrorListing(packageName: String, locale: Locale): MirrorListing {
        val languageTag = URLEncoder.encode(locale.toLanguageTag(), StandardCharsets.UTF_8.name())
        val encodedPackage = URLEncoder.encode(packageName, StandardCharsets.UTF_8.name())
        val endpoint = "$MIRROR_API_URL?hl=$languageTag&package_name=$encodedPackage"
        val connection = URL(endpoint).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "APKPure/3.20.50 (Android; 13)")
            connection.setRequestProperty("x-sv", "29")
            connection.setRequestProperty("x-abis", "arm64-v8a,armeabi-v7a,armeabi")
            connection.setRequestProperty("x-gp", "1")
            check(connection.responseCode in 200..299) {
                "APK mirror catalog returned HTTP ${connection.responseCode}"
            }

            val response = connection.inputStream.use { input ->
                input.readBytes().also {
                    check(it.size <= MAX_CATALOG_BYTES) { "APK mirror catalog response was too large" }
                }
            }
            val text = String(response, Charsets.ISO_8859_1)
            val downloadUrl = MIRROR_APK_URL.find(text)?.value
                ?: error("The APK mirror has no monolithic APK for this package")
            val downloadUri = URI(downloadUrl)
            check(downloadUri.scheme.equals("https", ignoreCase = true) &&
                downloadUri.host.equals("download.pureapk.com", ignoreCase = true)
            ) { "The APK mirror returned an untrusted download host" }

            val signerSha1 = Regex.escape(packageName).let { escapedPackage ->
                Regex(escapedPackage + "[\\s\\S]{0,140}:\\(([0-9a-fA-F]{40})")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            }
            return MirrorListing(downloadUrl, signerSha1)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadMirrorFile(
        downloadUrl: String,
        target: File,
        onBytes: (bytes: Long, totalBytes: Long?) -> Unit
    ) {
        var lastFailure: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) {
            val existingBytes = target.length()
            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "APKPure/3.20.50 (Android; 13)")
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                }

                val responseCode = connection.responseCode
                check(responseCode in 200..299) {
                    "APK mirror download returned HTTP $responseCode"
                }
                val finalUri = connection.url.toURI()
                check(finalUri.scheme.equals("https", ignoreCase = true) &&
                    isTrustedMirrorHost(finalUri.host)
                ) { "The APK mirror redirected to an untrusted host" }

                val isResume = existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
                if (existingBytes > 0 && !isResume) target.delete()
                val startBytes = if (isResume) existingBytes else 0L
                val contentRangeTotal = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull()
                val expectedTotal = contentRangeTotal
                    ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(startBytes)

                BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                    FileOutputStream(target, isResume).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = startBytes
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                            onBytes(total, expectedTotal)
                        }
                        output.fd.sync()
                    }
                }

                if (expectedTotal == null || target.length() == expectedTotal) return
                lastFailure = IOException(
                    "APK mirror transfer ended at ${target.length()} of $expectedTotal bytes"
                )
            } catch (exception: Exception) {
                lastFailure = exception
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("APK mirror download did not complete after retries", lastFailure)
    }

    private fun isTrustedMirrorHost(host: String?): Boolean {
        val normalized = host?.lowercase().orEmpty()
        return normalized == "download.pureapk.com" ||
            normalized == "winudf.com" ||
            normalized.endsWith(".winudf.com")
    }

    private fun signerExpectations(app: App): SignerExpectations {
        val sha256 = app.certificateSetList.mapNotNull { certificate ->
            decodeBase64Digest(certificate.sha256)
        }.toSet()
        val sha1 = app.certificateSetList.mapNotNull { certificate ->
            decodeBase64Digest(certificate.certificateSet)
        }.toSet()
        return SignerExpectations(sha256 = sha256, sha1 = sha1)
    }

    private fun decodeBase64Digest(encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .toHex()
        }.getOrNull()
    }

    private fun shortReason(throwable: Throwable): String {
        val root = generateSequence(throwable) { it.cause }.last()
        return (root.message ?: throwable.message ?: root.javaClass.simpleName)
            .replace(Regex("\\s+"), " ")
            .take(160)
    }

    private fun authenticate(properties: Properties, locale: Locale) = try {
        val response = postJson(DISPENSER_URL, propertiesToJson(properties).toString())
        val json = JSONObject(response)
        val email = json.optString("email")
        val token = json.optString("authToken").ifBlank { json.optString("auth") }
        check(email.isNotBlank() && token.isNotBlank()) {
            "The anonymous token service returned an incomplete response"
        }

        AuthHelper.build(
            email = email,
            token = token,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = properties,
            locale = locale
        )
    } catch (throwable: Throwable) {
        throw IllegalStateException("Could not create an anonymous Google Play session", throwable)
    }

    private fun loadDeviceProperties(): Properties {
        return Properties().apply {
            context.resources.openRawResource(GPlayApiR.raw.gplayapi_px_9a).use(::load)
        }
    }

    private fun propertiesToJson(properties: Properties): JSONObject = JSONObject().apply {
        properties.stringPropertyNames().forEach { key -> put(key, properties.getProperty(key)) }
    }

    private fun postJson(endpoint: String, body: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "com.vibe.playfetch-1.0.0-1")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) {
                "Anonymous token service HTTP ${connection.responseCode}: ${response.take(180)}"
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndVerifyFiles(
        packageName: String,
        versionCode: Long,
        signerExpectations: SignerExpectations,
        files: List<PlayFile>,
        workDirectory: File,
        onProgress: (String, Int?) -> Unit
    ): List<Pair<PlayFile, File>> {
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0) }
        var completedBytes = 0L

        return files.mapIndexed { index, playFile ->
            val localName = uniqueEntryName(playFile, index)
            val target = File(workDirectory, localName)
            val fileStart = completedBytes
            downloadFile(playFile, target) { fileBytes ->
                val overall = if (totalBytes > 0) {
                    (((fileStart + fileBytes) * 100L) / totalBytes).toInt().coerceIn(0, 99)
                } else {
                    null
                }
                onProgress("Downloading ${playFile.name} (${index + 1}/${files.size})…", overall)
            }
            verifySizeAndDigest(playFile, target)
            if (playFile.type == PlayFile.Type.BASE || playFile.type == PlayFile.Type.SPLIT) {
                verifyApk(target, packageName, versionCode, signerExpectations)
            }
            completedBytes += target.length()
            playFile to target
        }
    }

    private fun downloadFile(playFile: PlayFile, target: File, onBytes: (Long) -> Unit) {
        check(URI(playFile.url).scheme.equals("https", ignoreCase = true)) {
            "Google Play returned a non-HTTPS download URL"
        }
        val connection = URL(playFile.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            check(connection.responseCode in 200..299) {
                "Google download server returned HTTP ${connection.responseCode}"
            }
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        onBytes(total)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySizeAndDigest(playFile: PlayFile, target: File) {
        if (playFile.size > 0 && target.length() != playFile.size) {
            error("Size verification failed for ${playFile.name}")
        }

        val expectedSha256 = playFile.sha256.lowercase().trim()
        val expectedSha1 = playFile.sha1.lowercase().trim()
        when {
            expectedSha256.isNotBlank() -> check(digest(target, "SHA-256") == expectedSha256) {
                "Google SHA-256 verification failed for ${playFile.name}"
            }
            expectedSha1.isNotBlank() -> check(digest(target, "SHA-1") == expectedSha1) {
                "Google SHA-1 verification failed for ${playFile.name}"
            }
            else -> error("Google Play did not provide an integrity digest for ${playFile.name}")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(
        file: File,
        expectedPackage: String,
        expectedVersionCode: Long?,
        signerExpectations: SignerExpectations
    ): VerifiedApk {
        val signatureResult = ApkVerifier.Builder(file).build().verify()
        check(signatureResult.isVerified) {
            val errors = signatureResult.errors.joinToString(limit = 3) { it.toString() }
            "APK cryptographic signature verification failed: $errors"
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android could not parse ${file.name} as an APK")
        check(info.packageName == expectedPackage) { "Package verification failed for ${file.name}" }
        val actualVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        if (expectedVersionCode != null) {
            check(actualVersion == expectedVersionCode) { "Version verification failed for ${file.name}" }
        }

        val signerBytes = signerCertificates(info)
        check(signerBytes.isNotEmpty()) { "Signature verification failed for ${file.name}" }
        val actualSha256 = signerBytes.map { digest(it, "SHA-256") }.toSet()
        val actualSha1 = signerBytes.map { digest(it, "SHA-1") }.toSet()

        if (signerExpectations.sha256.isNotEmpty()) {
            check(actualSha256.any(signerExpectations.sha256::contains)) {
                "APK signer does not match Google Play's certificate metadata"
            }
        }
        if (signerExpectations.sha1.isNotEmpty()) {
            check(actualSha1.any(signerExpectations.sha1::contains)) {
                "APK signer does not match the expected certificate"
            }
        }

        installedSignerCertificates(expectedPackage, flags)?.let { installedSignerBytes ->
            val installedSha256 = installedSignerBytes.map { digest(it, "SHA-256") }.toSet()
            check(actualSha256.any(installedSha256::contains)) {
                "APK signer does not match the already installed app"
            }
        }

        val applicationInfo = info.applicationInfo
        if (applicationInfo != null) {
            applicationInfo.sourceDir = file.absolutePath
            applicationInfo.publicSourceDir = file.absolutePath
        }
        val appName = applicationInfo?.let {
            runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull()
        }.orEmpty().ifBlank { expectedPackage }

        return VerifiedApk(
            appName = appName,
            versionName = info.versionName.orEmpty().ifBlank { actualVersion.toString() },
            versionCode = actualVersion
        )
    }

    @Suppress("DEPRECATION")
    private fun signerCertificates(info: PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            (signingInfo.apkContentsSigners.asList() + signingInfo.signingCertificateHistory.asList())
                .distinctBy { it.toCharsString() }
                .map { it.toByteArray() }
        } else {
            info.signatures.orEmpty().map { it.toByteArray() }
        }
    }

    private fun installedSignerCertificates(packageName: String, flags: Int): List<ByteArray>? {
        val installedInfo = try {
            context.packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return signerCertificates(installedInfo)
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun createApksArchive(
        target: File,
        appName: String,
        packageName: String,
        versionName: String,
        versionCode: Long,
        regionLabel: String,
        files: List<Pair<PlayFile, File>>
    ) {
        ZipOutputStream(FileOutputStream(target).buffered(128 * 1024)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            val usedNames = mutableSetOf<String>()
            files.forEachIndexed { index, (playFile, file) ->
                val entryName = uniqueArchiveName(playFile, index, usedNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered(128 * 1024).use { it.copyTo(zip, 128 * 1024) }
                zip.closeEntry()
            }

            val metadata = JSONObject().apply {
                put("format", "PlayFetch signed split archive")
                put("appName", appName)
                put("packageName", packageName)
                put("versionName", versionName)
                put("versionCode", versionCode)
                put("marketRegion", regionLabel)
                put("createdAt", Instant.now().toString())
                put("files", JSONArray().apply {
                    files.forEach { (playFile, _) ->
                        put(JSONObject().apply {
                            put("name", playFile.name)
                            put("type", playFile.type.name)
                            put("size", playFile.size)
                            put("sha1", playFile.sha1)
                            put("sha256", playFile.sha256)
                        })
                    }
                })
            }
            zip.putNextEntry(ZipEntry("playfetch.json"))
            zip.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun uniqueArchiveName(
        playFile: PlayFile,
        index: Int,
        usedNames: MutableSet<String>
    ): String {
        val baseName = when (playFile.type) {
            PlayFile.Type.BASE -> "base.apk"
            PlayFile.Type.SPLIT -> safeName(playFile.name).let { if (it.endsWith(".apk")) it else "$it.apk" }
            PlayFile.Type.OBB, PlayFile.Type.PATCH -> "Android/obb/${safeName(playFile.name)}"
        }
        var candidate = baseName
        var suffix = 1
        while (!usedNames.add(candidate)) {
            val dot = baseName.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${baseName.substring(0, dot)}-$suffix${baseName.substring(dot)}"
            } else {
                "$baseName-$suffix"
            }
            suffix++
        }
        return candidate.ifBlank { "file-$index.apk" }
    }

    private fun uniqueEntryName(playFile: PlayFile, index: Int): String =
        "${index.toString().padStart(3, '0')}-${safeName(playFile.name.ifBlank { "file.apk" })}"

    private fun safeName(input: String): String =
        input.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "package" }

    private fun explainFailure(throwable: Throwable, countryCode: String): Throwable {
        val root = generateSequence(throwable) { it.cause }.last()
        val detail = root.message?.takeIf { it.isNotBlank() } ?: throwable.message.orEmpty()
        return IllegalStateException(
            when {
                detail.contains("404") || detail.contains("not found", ignoreCase = true) ->
                    "This app was not found in the $countryCode Play market or is incompatible with the device profile."
                detail.contains("403") || detail.contains("not purchased", ignoreCase = true) ->
                    "Google Play refused this anonymous download. The app may be paid, account-limited, or unavailable in $countryCode."
                detail.contains("429") ->
                    "The anonymous login service is rate-limited. Please try again later."
                else -> throwable.message?.takeIf { it.isNotBlank() } ?: "Download failed: $detail"
            },
            throwable
        )
    }

    companion object {
        private const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private const val MIRROR_API_URL = "https://api.pureapk.com/m/v3/cms/app_version"
        private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
        private const val MAX_DOWNLOAD_ATTEMPTS = 4
        private const val TEMP_DIRECTORY_PREFIX = "playfetch-temp-"
        private val MIRROR_APK_URL = Regex(
            "https://download\\.pureapk\\.com/b/APK/[A-Za-z0-9._~:/?#@!&'()*+,;=%-]+"
        )
    }
}
