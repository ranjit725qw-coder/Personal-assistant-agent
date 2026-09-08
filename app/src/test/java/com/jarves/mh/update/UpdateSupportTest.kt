package com.jarves.mh.update

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val info = AppUpdateInfo(109L, "1.1.9", "https://example.com/update.apk", "abc123", 10L, "Fixes")

    @Test
    fun httpErrorsFailWithoutReadingOrAcceptingTheBody() {
        for (code in listOf(301, 403, 404, 429, 500, 503)) {
            var bodyRead = false
            val failure = assertThrows(IllegalStateException::class.java) {
                UpdateManifestParser.parseResponse(code, { bodyRead = true; "{}" }, 108L, "online")
            }
            assertFalse(bodyRead)
            assertTrue(failure.message.orEmpty().contains("HTTP $code"))
        }
    }

    @Test
    fun validCurrentOrOlderVersionMeansUpToDate() {
        assertNull(parse("""{"versionCode":108}"""))
        assertNull(parse("""{"versionCode":107}"""))
    }

    @Test
    fun parsesTheRequestedArtifact() {
        val update = parse("""{"versionCode":109,"versionName":"1.1.9","artifacts":{"online":{"url":"https://example.com/online.apk","sha256":"ABC","sizeBytes":42},"offline":{"url":"https://example.com/offline.apk"}}}""")
        assertEquals("https://example.com/online.apk", update?.apkUrl)
        assertEquals("abc", update?.sha256)
        assertEquals(42L, update?.sizeBytes)
    }

    @Test
    fun supportsLegacyTopLevelManifest() {
        val update = parse("""{"versionCode":109,"apkUrl":"https://example.com/legacy.apk"}""")
        assertEquals("https://example.com/legacy.apk", update?.apkUrl)
    }

    @Test
    fun malformedManifestCannotMasqueradeAsUpToDate() {
        for (body in listOf("not json", "{}", """{"versionCode":0}""", """{"versionCode":109}""", """{"versionCode":109,"url":"http://example.com/update.apk"}""")) {
            assertThrows(Exception::class.java) { parse(body) }
        }
    }

    @Test
    fun bodyReadFailuresPropagateToTheExistingFailedCheckHandler() {
        assertThrows(java.io.IOException::class.java) {
            UpdateManifestParser.parseResponse(200, { throw java.io.IOException("disconnected") }, 108L, "online")
        }
    }

    @Test
    fun interruptedSameArtifactResumesWithoutLosingBytes() {
        val (state, partial, _) = fixture()
        assertEquals(0L, state.prepare(info))
        partial.writeText("1234")
        assertEquals(4L, state.prepare(info))
        assertEquals("1234", partial.readText())
    }

    @Test
    fun changingReleaseClearsOldBytesBeforeAnyNetworkRequest() {
        val (state, partial, meta) = fixture()
        state.prepare(info)
        partial.writeText("old")
        val next = info.copy(versionCode = 110L, apkUrl = "https://example.com/new.apk")
        assertEquals(0L, state.prepare(next))
        assertFalse(partial.exists())
        assertEquals(110L, JSONObject(meta.readText()).getLong("versionCode"))
        // Simulate a connection failure before new bytes arrive, then retry.
        assertEquals(0L, state.prepare(next))
        assertFalse(partial.exists())
    }

    @Test
    fun changedChecksumOrSizeInvalidatesSameUrlAndVersion() {
        for (changed in listOf(info.copy(sha256 = "different"), info.copy(sizeBytes = 20L))) {
            val (state, partial, _) = fixture()
            state.prepare(info)
            partial.writeText("old")
            assertEquals(0L, state.prepare(changed))
            assertFalse(partial.exists())
        }
    }

    @Test
    fun missingCorruptOrLegacyMetadataCannotAuthenticateOldBytes() {
        for (metadata in listOf(null, "{broken", """{"apkUrl":"https://example.com/update.apk","versionCode":109}""")) {
            val (state, partial, meta) = fixture()
            partial.writeText("old")
            if (metadata != null) meta.writeText(metadata)
            assertEquals(0L, state.prepare(info))
            assertFalse(partial.exists())
        }
    }

    @Test
    fun completedBytesAreRetainedForVerificationWithoutRedownload() {
        val (state, partial, _) = fixture()
        state.prepare(info)
        partial.writeText("1234567890")
        assertEquals(10L, state.prepare(info))
        assertEquals(10L, partial.length())
    }

    @Test
    fun oversizedPartialIsDiscarded() {
        val (state, partial, _) = fixture()
        state.prepare(info)
        partial.writeText("12345678901")
        assertEquals(0L, state.prepare(info))
        assertFalse(partial.exists())
    }

    @Test
    fun unknownTotalSizeStillAllowsMatchingPartialToResume() {
        val (state, partial, _) = fixture()
        val unknown = info.copy(sizeBytes = -1L)
        state.prepare(unknown)
        partial.writeText("1234")
        assertEquals(4L, state.prepare(unknown))
    }

    @Test
    fun rangeRetryResetRemovesPartialBeforeRewritingIdentity() {
        val (state, partial, meta) = fixture()
        state.prepare(info)
        partial.writeText("1234")
        assertEquals(0L, state.reset(info))
        assertFalse(partial.exists())
        assertTrue(meta.isFile)
    }

    @Test
    fun cleanupFailureDoesNotRelabelStaleContent() {
        val (state, partial, meta) = fixture()
        state.prepare(info)
        assertTrue(partial.mkdir())
        File(partial, "block-delete").writeText("old")
        assertThrows(IllegalStateException::class.java) {
            state.prepare(info.copy(versionCode = 110L))
        }
        assertFalse(meta.exists())
        assertEquals("old", File(partial, "block-delete").readText())
    }

    private fun parse(body: String): AppUpdateInfo? =
        UpdateManifestParser.parseResponse(200, { body }, 108L, "online")

    private fun fixture(): Triple<PartialUpdateDownload, File, File> {
        val directory = temporaryFolder.newFolder()
        val partial = File(directory, "update.apk.part")
        val meta = File(directory, "update.apk.part.json")
        return Triple(PartialUpdateDownload(partial, meta), partial, meta)
    }
}
