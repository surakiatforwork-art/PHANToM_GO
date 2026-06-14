package com.phantom.carnavrelay

import java.util.Locale

object MapsUiVocabulary {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Literal used for the fast text query in the Share Sheet. */
    const val PHANTOM_GO_SEARCH = "PHANToM GO"

    val shareLabels = setOf("แชร์", "share")
    val stopLabels = setOf(
        "ปิดการนำทาง",
        "หยุดการนำทาง",
        "stop navigation",
        "end navigation",
        "exit navigation"
    )
    val previewLabels = setOf(
        "ตัวอย่างเส้นทาง",
        "ภาพรวมเส้นทาง",
        "route preview",
        "route overview"
    )
    val warningExitLabels = setOf("ออก", "exit")
    val warningContinueLabels = setOf("ต่อไป", "continue", "proceed")

    private val navigationSignals = setOf(
        "เริ่มต้นการนำทาง",
        "กำลังนำทาง",
        "การนำทาง",
        "navigation started",
        "navigating",
        "navigation"
    )

    private val resolverPackages = setOf(
        "android",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "com.samsung.android.app.sharelive",
        "com.miui.mishare.connectivity"
    )

    fun normalize(value: CharSequence?): String {
        return value?.toString()
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace('…', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun matchesLabel(value: CharSequence?, labels: Set<String>): Boolean {
        val normalized = normalize(value)
        return normalized.isNotEmpty() && labels.any { normalized == normalize(it) }
    }

    fun startsWithPhantomGo(value: CharSequence?): Boolean {
        return normalize(value).startsWith("phantom go")
    }

    fun isLikelyNavigationSignal(values: Iterable<CharSequence?>): Boolean {
        val joined = values.joinToString(" ") { normalize(it) }
        return navigationSignals.any { joined.contains(normalize(it)) }
    }

    fun isAllowedResolverPackage(packageName: String?): Boolean {
        return packageName != null && (packageName == MAPS_PACKAGE || packageName in resolverPackages)
    }

    /** A share-sheet resolver package (excludes Maps itself). Used to detect that the Share Sheet
     *  actually appeared after clicking Share. */
    fun isResolverPackage(packageName: String?): Boolean {
        return packageName != null && packageName in resolverPackages
    }
}
