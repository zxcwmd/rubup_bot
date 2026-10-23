package com.rubup.worker

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Immutable copy of the small part of the Android window tree needed by the driver. */
data class UiNode(
    val path: String,
    val text: String,
    val description: String,
    val resourceId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val visible: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val bounds: Rect,
    val children: List<UiNode>,
) {
    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
    fun label(): String = listOf(text, description, resourceId).joinToString(" ").trim()
    fun textOrDescription(): String = listOf(text, description).joinToString(" ").trim()
}

data class UiSnapshot(val root: UiNode) {
    val nodes: List<UiNode>
    private val byPath: Map<String, UiNode>
    private val parents: Map<String, String>

    init {
        val all = mutableListOf<UiNode>()
        val byPath = mutableMapOf<String, UiNode>()
        val parents = mutableMapOf<String, String>()
        fun visit(node: UiNode, parent: String) {
            all += node
            byPath[node.path] = node
            if (parent.isNotEmpty()) parents[node.path] = parent
            node.children.forEach { visit(it, node.path) }
        }
        visit(root, "")
        nodes = all
        this.byPath = byPath
        this.parents = parents
    }

    fun ancestors(node: UiNode): List<UiNode> {
        val result = mutableListOf<UiNode>()
        var parent = parents[node.path]
        while (parent != null) {
            byPath[parent]?.let(result::add)
            parent = parents[parent]
        }
        return result
    }

    fun text(): String = nodes.joinToString("\n") { it.textOrDescription() }
}

/**
 * User-enabled bridge to the already installed TikTok application.
 * No screenshots, root, injected code, or accessibility-key logging are used.
 */
class TikTokAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        return try { root.packageName?.toString().orEmpty() } finally { root.recycle() }
    }

    fun waitForPackage(packages: Set<String>, timeoutSeconds: Long = 20): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (currentPackage() in packages) return true
            Thread.sleep(250)
        }
        return currentPackage() in packages
    }

    fun snapshot(): UiSnapshot {
        val root = rootInActiveWindow ?: throw BotFailure("Android не вернул дерево текущего окна")
        return try {
            val budget = intArrayOf(1800)
            UiSnapshot(copyTree(root, "0", budget))
        } finally {
            root.recycle()
        }
    }

    private fun copyTree(node: AccessibilityNodeInfo, path: String, budget: IntArray): UiNode {
        if (budget[0] > 0) budget[0]--
        val children = mutableListOf<UiNode>()
        if (budget[0] > 0) {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    children += copyTree(child, "$path.$index", budget)
                } finally {
                    child.recycle()
                }
                if (budget[0] <= 0) break
            }
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return UiNode(
            path = path,
            text = node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            visible = node.isVisibleToUser,
            selected = node.isSelected,
            checked = node.isChecked,
            bounds = bounds,
            children = children,
        )
    }

    fun clickFirst(predicate: (UiNode) -> Boolean): Boolean {
        val target = snapshot().nodes.firstOrNull { it.visible && predicate(it) } ?: return false
        return clickPath(target.path)
    }

    fun clickPath(path: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val allocated = mutableListOf<AccessibilityNodeInfo>()
        return try {
            val target = atPath(root, path, allocated) ?: return false
            var current = target
            repeat(10) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val parent = current.parent ?: return false
                allocated += parent
                current = parent
            }
            false
        } finally {
            allocated.forEach { node -> try { node.recycle() } catch (_: Exception) { } }
            root.recycle()
        }
    }

    fun setTextPath(path: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val allocated = mutableListOf<AccessibilityNodeInfo>()
        return try {
            val target = atPath(root, path, allocated) ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            allocated.forEach { node -> try { node.recycle() } catch (_: Exception) { } }
            root.recycle()
        }
    }

    private fun atPath(
        root: AccessibilityNodeInfo,
        path: String,
        allocated: MutableList<AccessibilityNodeInfo>,
    ): AccessibilityNodeInfo? {
        if (path == "0") return root
        if (!path.startsWith("0.")) return null
        var current = root
        for (part in path.removePrefix("0.").split('.')) {
            val index = part.toIntOrNull() ?: return null
            val child = current.getChild(index) ?: return null
            allocated += child
            current = child
        }
        return current
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        @Volatile private var instance: TikTokAccessibilityService? = null
        @Volatile private var connected = false

        fun isReady(): Boolean = connected && instance != null
        fun current(): TikTokAccessibilityService? = instance
    }
}
