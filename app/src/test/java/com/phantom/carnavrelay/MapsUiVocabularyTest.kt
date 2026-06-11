package com.phantom.carnavrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsUiVocabularyTest {

    @Test
    fun normalizesWhitespaceCaseAndEllipsis() {
        assertTrue(MapsUiVocabulary.matchesLabel("  SHARE… ", MapsUiVocabulary.shareLabels))
    }

    @Test
    fun warningExitRequiresExactLabel() {
        assertTrue(MapsUiVocabulary.matchesLabel("ออก", MapsUiVocabulary.warningExitLabels))
        assertFalse(MapsUiVocabulary.matchesLabel("ออกจากบัญชี", MapsUiVocabulary.warningExitLabels))
    }

    @Test
    fun recognizesThaiAndEnglishNavigationSignals() {
        assertTrue(MapsUiVocabulary.isLikelyNavigationSignal(listOf("เริ่มต้นการนำทาง…")))
        assertTrue(MapsUiVocabulary.isLikelyNavigationSignal(listOf("Navigation started")))
        assertFalse(MapsUiVocabulary.isLikelyNavigationSignal(listOf("Shared location")))
    }

    @Test
    fun phantomTargetMustStartWithAppName() {
        assertTrue(MapsUiVocabulary.startsWithPhantomGo("PHANToM GO - Main"))
        assertFalse(MapsUiVocabulary.startsWithPhantomGo("Open PHANToM GO"))
    }

    @Test
    fun resolverAllowlistDoesNotIncludeArbitraryApps() {
        assertTrue(MapsUiVocabulary.isAllowedResolverPackage("android"))
        assertFalse(MapsUiVocabulary.isAllowedResolverPackage("com.example.bank"))
    }
}
