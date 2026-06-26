package com.phantom.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test

class DashcamModeTest {

    @Test
    fun `falls back to rear when value is unknown`() {
        assertEquals(DashcamMode.REAR, DashcamMode.fromStorageValue("something-else"))
    }

    @Test
    fun `parses dual mode`() {
        assertEquals(DashcamMode.DUAL, DashcamMode.fromStorageValue("dual"))
    }
}
