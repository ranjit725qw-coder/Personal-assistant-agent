package com.jarves.mh.update

import java.io.File
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/** null means a successfully fetched, valid manifest has no newer version. */
internal object UpdateManifestParser {
    fun parseResponse(
        code: Int,
        readBody: () -> String,
        installedVersionCode: Long,
        variant: String,
    ): AppUpdateInfo? {
        check(code in 200..299) { "Update check failed (HTTP $code)" }
        val root = JSONObject(readBody())
        val versionCode = root.getLong("versionCode")
        check(versionCode > 0) { "Update manifest has an invalid version code" }
        if (versionCode <= installedVersionCode) return null
        val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
            ?: root.optJSONObject(variant)
            ?: root
        val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
        check(url.startsWith("https://")) { "Update manifest has no valid HTTPS APK URL for $variant" }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = root.optString("versionName", versionCode.toString()),
            apkUrl = url,
            sha256 = artifact.optString("sha256").lowercase(),
            sizeBytes = artifact.optLong("sizeBytes", -1L),
            notes = root.optString("notes"),
        )
    }
}

/** Owns the association between partial bytes and the exact artifact they belong to. */
internal class PartialUpdateDownload(private val partial: File, private val meta: File) {
    fun prepare(info: AppUpdateInfo): Long {
        val expected = info.sizeBytes.takeIf { it > 0 }
        if (partial.isFile && matches(info) && (expected == null || partial.length() <= expected)) {
            return partial.length()
        }
        return reset(info)
    }

    fun reset(info: AppUpdateInfo): Long {
        // Invalidate identity FIRST. Never label old bytes as a different release,
        // even if the next connection fails before opening/truncating the file.
        check(!meta.exists() || meta.delete()) { "Could not clear update download metadata" }
        check(!partial.exists() || partial.delete()) { "Could not clear the previous partial update" }
        // Do not swallow write errors: resuming without trustworthy metadata is unsafe.
        meta.writeText(
            JSONObject()
                .put("apkUrl", info.apkUrl)
                .put("versionCode", info.versionCode)
                .put("sha256", info.sha256.lowercase())
                .put("sizeBytes", info.sizeBytes)
                .toString(),
        )
        return 0L
    }

    private fun matches(info: AppUpdateInfo): Boolean = runCatching {
        val root = JSONObject(meta.readText())
        root.getString("apkUrl") == info.apkUrl &&
            root.getLong("versionCode") == info.versionCode &&
            root.getString("sha256").equals(info.sha256, ignoreCase = true) &&
            root.getLong("sizeBytes") == info.sizeBytes
    }.getOrDefault(false)
}
