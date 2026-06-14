package com.phantom.carnavrelay

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object MapsUiMatcher {
    private const val MAX_NODES = 700
    private const val MAX_ANCESTOR_DEPTH = 8

    enum class ScanGoal {
        ROUTE_ACTION,
        SHARE_ONLY
    }

    /**
     * Result of a single traversal of the Maps UI node tree. The coordinator decides each action
     * from one snapshot instead of walking the tree once per control, which keeps CPU pressure off
     * Google Maps and the Share Sheet on slow devices.
     */
    data class MapsUiSnapshot(
        val warningExit: AccessibilityNodeInfo?,
        val warningContinue: AccessibilityNodeInfo?,
        val share: AccessibilityNodeInfo?,
        val stop: AccessibilityNodeInfo?,
        val preview: AccessibilityNodeInfo?,
        val visitedNodeCount: Int,
        val scanDurationMs: Long
    ) {
        /** Treat it as a closed-destination warning only when BOTH the exit and continue
         *  controls are present, so a plain "exit/ออก" button is never clicked by mistake. */
        val hasWarning: Boolean get() = warningExit != null && warningContinue != null
    }

    /** Walk the tree once and collect every route control we may need. */
    fun scanMapsUi(
        root: AccessibilityNodeInfo,
        goal: ScanGoal = ScanGoal.ROUTE_ACTION
    ): MapsUiSnapshot {
        val startNs = System.nanoTime()
        var warningExit: AccessibilityNodeInfo? = null
        var warningContinue: AccessibilityNodeInfo? = null
        var share: AccessibilityNodeInfo? = null
        var stop: AccessibilityNodeInfo? = null
        var preview: AccessibilityNodeInfo? = null
        var visited = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1

            val text = node.text
            val desc = node.contentDescription
            if (text != null || desc != null) {
                when {
                    warningExit == null && matches(text, desc, MapsUiVocabulary.warningExitLabels) && hasClickableAncestor(node) ->
                        warningExit = node
                    warningContinue == null && matches(text, desc, MapsUiVocabulary.warningContinueLabels) && hasClickableAncestor(node) ->
                        warningContinue = node
                    share == null && matches(text, desc, MapsUiVocabulary.shareLabels) && hasClickableAncestor(node) ->
                        share = node
                    stop == null && matches(text, desc, MapsUiVocabulary.stopLabels) && hasClickableAncestor(node) ->
                        stop = node
                    preview == null && matches(text, desc, MapsUiVocabulary.previewLabels) && hasClickableAncestor(node) ->
                        preview = node
                }
            }

            val foundEnough = when (goal) {
                ScanGoal.SHARE_ONLY -> share != null
                ScanGoal.ROUTE_ACTION ->
                    (warningExit != null && warningContinue != null) ||
                        (share != null && stop != null)
            }
            if (foundEnough) break

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        val durationMs = (System.nanoTime() - startNs) / 1_000_000L
        return MapsUiSnapshot(warningExit, warningContinue, share, stop, preview, visited, durationMs)
    }

    /**
     * Find the "PHANToM GO" entry in a resolver/Share Sheet window.
     * Fast path uses [AccessibilityNodeInfo.findAccessibilityNodeInfosByText]; if that returns
     * nothing usable it falls back to a bounded BFS. Returns null if not present in this window.
     */
    fun findPhantomGoTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val byText = root.findAccessibilityNodeInfosByText(MapsUiVocabulary.PHANTOM_GO_SEARCH)
            for (node in byText) {
                if (node == null) continue
                if ((MapsUiVocabulary.startsWithPhantomGo(node.text) ||
                        MapsUiVocabulary.startsWithPhantomGo(node.contentDescription)) &&
                    hasClickableAncestor(node)
                ) {
                    return node
                }
            }
        } catch (_: Exception) {
            // Fall through to BFS.
        }

        return find(root) { node ->
            MapsUiVocabulary.startsWithPhantomGo(node.text) ||
                MapsUiVocabulary.startsWithPhantomGo(node.contentDescription)
        }
    }

    /** Click a node by walking up to its first clickable ancestor. */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    private fun matches(
        text: CharSequence?,
        desc: CharSequence?,
        labels: Set<String>
    ): Boolean {
        return MapsUiVocabulary.matchesLabel(text, labels) ||
            MapsUiVocabulary.matchesLabel(desc, labels)
    }

    private fun find(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (predicate(node) && hasClickableAncestor(node)) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) return true
            current = current.parent
            depth += 1
        }
        return false
    }
}
