package com.phantom.carnavrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayPowerStatePolicyTest {

    @Test
    fun unknownStateSeedsWithoutCreatingAnEvent() {
        assertNull(DisplayPowerStatePolicy.edge(null, currentPlugged = true))
        assertNull(DisplayPowerStatePolicy.edge(null, currentPlugged = false))
    }

    @Test
    fun unchangedStateDoesNotCreateDuplicateEvents() {
        assertNull(DisplayPowerStatePolicy.edge(previousPlugged = true, currentPlugged = true))
        assertNull(DisplayPowerStatePolicy.edge(previousPlugged = false, currentPlugged = false))
    }

    @Test
    fun changedStateCreatesCorrectEdge() {
        assertEquals(
            DisplayPowerEdge.CONNECTED,
            DisplayPowerStatePolicy.edge(previousPlugged = false, currentPlugged = true)
        )
        assertEquals(
            DisplayPowerEdge.DISCONNECTED,
            DisplayPowerStatePolicy.edge(previousPlugged = true, currentPlugged = false)
        )
    }
}
