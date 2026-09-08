package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jarves.mh.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * [BuildConfig.APP_UPDATE_MANIFEST_URL]. Debug builds populate this
     * from Settings → Update channel so the update flow can be exercised
     * end-to-end against a Cloudflare Tunnel or ngrok HTTPS URL without
     * publishing a release to GitHub.
     */
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { BuildConfig.APP_UPDATE_MANIFEST_URL }
        check(manifestUrl.startsWith("https://")) { "Update manifest URL must use HTTPS" }
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            UpdateManifestParser.parseResponse(
                code = connection.responseCode,
                readBody = { connection.inputStream.bufferedReader().use { it.readText() } },
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                variant = BuildConfig.APP_VARIANT,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [info] into a partial file, resuming from previously downloaded
     * bytes whenever the partial file belongs to this exact update (tracked via a
     * small sidecar meta file, so a stale partial from an older release is never
     * resumed). Network failures leave the partial file on disk, so the next
     * attempt continues where the last one stopped via HTTP Range requests.
     */
    fun download(
        info: AppUpdateInfo,
        progress: (Long, Long) -> Unit,
        onStage: (String) -> Unit = {},
    ): File {
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
        val meta = File(directory, "${partial.name}.json")
        // Network errors thrown here intentionally leave `partial` (and `meta`) on
        // disk so the next attempt resumes instead of starting over.
        downloadToPartial(info, partial, meta, progress)
        try {
            if (info.sha256.isNotBlank()) {
                onStage("Verifying package (SHA-256)\\u2026")
                val actual = sha256(partial)
                check(actual.equals(info.sha256, ignoreCase = true)) { "Downloaded APK failed its SHA-256 verification" }
            }
            onStage("Verifying app signature\\u2026")
            verifyApk(partial, info.versionCode)
        } catch (error: Exception) {
            // Corrupt bytes must never poison the next attempt: wipe them so the
            // next tap downloads fresh instead of re-verifying the same bad file.
            runCatching { partial.delete() }
            runCatching { meta.delete() }
            throw error
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not prepare the downloaded update" }
        runCatching { meta.delete() }
        return target
    }

    private fun downloadToPartial(
        info: AppUpdateInfo,
        partial: File,
        meta: File,
        progress: (Long, Long) -> Unit,
    ) {
        val expected = info.sizeBytes.takeIf { it > 0 }
        val downloadState = PartialUpdateDownload(partial, meta)
        // Clear stale bytes before recording the new artifact identity or connecting.
        var resumeFrom = downloadState.prepare(info)
        if (expected != null && resumeFrom == expected) {
            progress(expected, expected)
            return
        }
        if (resumeFrom > 0 && expected != null) progress(resumeFrom, expected)
        var retriedFresh = false
        while (true) {
            val connection = openDownloadConnection(info.apkUrl, resumeFrom)
            val code = connection.responseCode
            if (code == 200) {
                // Full body (server ignored Range, or we never sent one): fresh download.
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: expected ?: -1L
                consumeToFile(connection, partial, append = false, startOffset = 0, total = total, progress = progress)
                return
            }
            if (code == 206 && resumeFrom > 0 &&
                parseContentRangeStart(connection.getHeaderField("Content-Range")) == resumeFrom
            ) {
                val remaining = connection.contentLengthLong.takeIf { it > 0 }
                val total = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                    ?: (if (remaining != null) resumeFrom + remaining else null)
                    ?: expected ?: -1L
                consumeToFile(connection, partial, append = true, startOffset = resumeFrom, total = total, progress = progress)
                return
            }
            // Anything else (416, unexpected 206/offset, 4xx/5xx): retry once from
            // scratch for offset problems, otherwise fail with the HTTP status.
            connection.disconnect()
            if (code != 206 && code != 416) error("Update download failed (HTTP $code)")
            check(!retriedFresh) { "Update download failed (HTTP $code)" }
            retriedFresh = true
            resumeFrom = downloadState.reset(info)
        }
    }

    /**
     * Opens the APK URL, following redirects manually so the Range header is
     * re-applied on every hop (GitHub release URLs redirect to a signed host,
     * which would otherwise drop it).
     */
    private fun openDownloadConnection(url: String, resumeFrom: Long): HttpURLConnection {
        var current = url
        for (i in 0 until 5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "Update download failed (redirect without location)" }
                current = URL(URL(current), location).toString()
            } else {
                return connection
            }
        }
        error("Update download failed (too many redirects)")
    }

    private fun consumeToFile(
        connection: HttpURLConnection,
        partial: File,
        append: Boolean,
        startOffset: Long,
        total: Long,
        progress: (Long, Long) -> Unit,
    ) {
        try {
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = startOffset
                    progress(downloaded, total)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** "bytes 500-999/1000" -> 500 */
    private fun parseContentRangeStart(header: String?): Long? {
        val range = header?.substringAfter(' ')?.substringBefore('/')?.trim() ?: return null
        return range.substringBefore('-').toLongOrNull()
    }

    /** "bytes 500-999/1000" -> 1000 */
    private fun parseContentRangeTotal(header: String?): Long? {
        val total = header?.substringAfterLast('/')?.trim() ?: return null
        return total.toLongOrNull()?.takeIf { it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update package name does not match Mobile Harness" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode && archiveVersion > BuildConfig.VERSION_CODE) { "Update version does not match its manifest" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.map { sha256(it.toByteArray()) }.toSet() == installedSignatures.map { sha256(it.toByteArray()) }.toSet()
        ) { "Update is not signed with the installed app's signing key" }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
