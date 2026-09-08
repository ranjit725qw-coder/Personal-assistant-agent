package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncUiGuardsTest {
    @Test
    fun shortOutputIsUnchanged() {
        val output = BoundedTerminalOutput(16)
        output.append("hello")
        output.append("\nworld")
        assertEquals("hello\nworld", output.toString())
    }

    @Test
    fun repeatedOutputNeverExceedsTheLimit() {
        val output = BoundedTerminalOutput(200_000)
        val chunk = "log line\n".repeat(2_048)
        repeat(1_000) {
            output.append(chunk)
            assertTrue(output.length <= 200_000)
        }
        assertEquals(200_000, output.length)
        assertTrue(output.toString().endsWith(chunk))
    }

    @Test
    fun preservesNewestCharactersAcrossChunkBoundaries() {
        val output = BoundedTerminalOutput(8)
        output.append("123456")
        output.append("789")
        assertEquals("23456789", output.toString())
        output.append("")
        assertEquals("23456789", output.toString())
    }

    @Test
    fun anOversizedChunkRetainsOnlyItsTail() {
        val output = BoundedTerminalOutput(4)
        output.append("old")
        output.append("0123456789")
        assertEquals("6789", output.toString())
        assertEquals(4, output.length)
    }

    @Test
    fun retainsFinalWorkingDirectoryMarkerAfterLargeOutput() {
        val output = BoundedTerminalOutput(100)
        output.append("x".repeat(1_000))
        output.append("\n__CWD__")
        output.append("/workspace/project\n")
        assertTrue(output.toString().endsWith("\n__CWD__/workspace/project\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonpositiveCapacity() {
        BoundedTerminalOutput(0)
    }

    @Test
    fun slowerFirstReadCannotOverwriteNewerFile() {
        val reads = LatestFileRead()
        val first = reads.begin("project", "a.kt")
        val second = reads.begin("project", "b.kt")
        assertFalse(reads.isCurrent(first, "project", "b.kt"))
        assertTrue(reads.isCurrent(second, "project", "b.kt"))
    }

    @Test
    fun reopeningTheSamePathDoesNotReviveAnOldRead() {
        val reads = LatestFileRead()
        val first = reads.begin("project", "a.kt")
        reads.begin("project", "b.kt")
        val reopened = reads.begin("project", "a.kt")
        assertFalse(reads.isCurrent(first, "project", "a.kt"))
        assertTrue(reads.isCurrent(reopened, "project", "a.kt"))
    }

    @Test
    fun closingOrChangingProjectRootInvalidatesPendingReads() {
        val reads = LatestFileRead()
        val pending = reads.begin("project", "a.kt")
        reads.invalidate()
        assertFalse(reads.isCurrent(pending, "project", "a.kt"))
        assertFalse(reads.isCurrent(pending, null, null))
    }

    @Test
    fun requiresMatchingProjectAndPath() {
        val reads = LatestFileRead()
        val pending = reads.begin("first", "a.kt")
        assertFalse(reads.isCurrent(pending, "second", "a.kt"))
        assertFalse(reads.isCurrent(pending, "first", "b.kt"))
        assertTrue(reads.isCurrent(pending, "first", "a.kt"))
    }
}
