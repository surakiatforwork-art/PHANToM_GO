package com.phantom.carnavrelay

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object MapsUiMatcher {
    private const val MAX_NODES = 700

    data class ClosedWarning(
        val exit: AccessibilityNodeInfo,
        val continueNode: AccessibilityNodeInfo
    )

    fun findClosedWarning(root: AccessibilityNodeInfo): ClosedWarning? {
        val exit = findByLabels(root, MapsUiVocabulary.warningExitLabels) ?: return null
        val continueNode = findByLabels(root, MapsUiVocabulary.warningContinueLabels) ?: return null
        return ClosedWarning(exit, continueNode)
    }

    fun findShare(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findByLabels(root, MapsUiVocabulary.shareLabels)
    }

    fun findStop(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findByLabels(root, MapsUiVocabulary.stopLabels)
    }

    fun findPreview(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findByLabels(root, MapsUiVocabulary.previewLabels)
    }

    fun findPhantomGoTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return find(root) { node ->
            MapsUiVocabulary.startsWithPhantomGo(node.text) ||
                MapsUiVocabulary.startsWithPhantomGo(node.contentDescription)
        }
    }

    fun hasStrongRouteControls(root: AccessibilityNodeInfo): Boolean {
        return findClosedWarning(root) != null || findStop(root) != null
    }

    fun click(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    private fun findByLabels(
        root: AccessibilityNodeInfo,
        labels: Set<String>
    ): AccessibilityNodeInfo? {
        return find(root) { node ->
            MapsUiVocabulary.matchesLabel(node.text, labels) ||
                MapsUiVocabulary.matchesLabel(node.contentDescription, labels)
        }
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
        while (current != null && depth < 8) {
            if (current.isClickable) return true
            current = current.parent
            depth += 1
        }
        return false
    }
}
