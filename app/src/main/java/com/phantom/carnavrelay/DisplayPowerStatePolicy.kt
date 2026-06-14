package com.phantom.carnavrelay

internal enum class DisplayPowerEdge {
    CONNECTED,
    DISCONNECTED
}

internal object DisplayPowerStatePolicy {

    fun edge(previousPlugged: Boolean?, currentPlugged: Boolean): DisplayPowerEdge? {
        if (previousPlugged == null || previousPlugged == currentPlugged) return null
        return if (currentPlugged) DisplayPowerEdge.CONNECTED else DisplayPowerEdge.DISCONNECTED
    }
}
