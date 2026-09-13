package com.maragung.arrowide.github

import com.maragung.arrowide.ui.github.formatByteSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatByteSize] of the workflow file list (plan #17): the "1.2 KB" style
 * subtitle next to each file path. Pure logic, no fixtures.
 */
class WorkflowFileSizeFormatTest {

    @Test
    fun bytesBelowOneKilobyteAreShownVerbatim() {
        assertEquals("0 B", formatByteSize(0L))
        assertEquals("1 B", formatByteSize(1L))
        assertEquals("412 B", formatByteSize(412L))
        assertEquals("1023 B", formatByteSize(1023L))
    }

    @Test
    fun kilobytesKeepAtMostOneDecimal() {
        assertEquals("1 KB", formatByteSize(1024L))
        assertEquals("1.2 KB", formatByteSize(1234L))
        assertEquals("1.5 KB", formatByteSize(1536L))
    }

    @Test
    fun largerUnitsScaleAndDropTrailingZeroes() {
        assertEquals("1 MB", formatByteSize(1048576L))
        assertEquals("1.2 MB", formatByteSize(1234567L))
        assertEquals("5 GB", formatByteSize(5368709120L))
    }
}
