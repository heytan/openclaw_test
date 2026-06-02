package com.openclaw.car.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.gesture.GesturePoint
import android.gesture.GestureStroke
import android.graphics.Path
import com.openclaw.car.OpenClawApp

class UiAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        sInstance = this
        Log.e(OpenClawApp.TAG, "UiAutomationService connected")
    }

    override fun onDestroy() {
        sInstance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun executeCommand(action: String?, text: String?, target: String?): String {
        if (action == null) return "ERROR: action is null"

        val root = getRootInActiveWindow()
        if (root == null) return "ERROR: no active window"

        try {
            return when (action) {
                "setText" -> doSetText(root, target, text)
                "typeText" -> doTypeText(root, target, text)
                "click" -> doClick(root, target)
                "scroll" -> doScroll(root, text, target)
                "findAndClick" -> doFindAndClick(root, target)
                "clickResult" -> doClickResult(root, target)
                "query" -> doQuery(root, target)
                "waitFor" -> {
                    root.recycle()
                    doWaitFor(text, target)
                }
                else -> "ERROR: unknown action $action"
            }
        } finally {
            if (action != "waitFor") {
                root.recycle()
            }
        }
    }

    private fun doSetText(root: AccessibilityNodeInfo, target: String?, text: String?): String {
        if (text == null) return "ERROR: text is null"
        val node = findEditableNode(root, target) ?: return "ERROR: editable node not found: $target"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return "OK: setText '$text'"
    }

    private fun doTypeText(root: AccessibilityNodeInfo, target: String?, text: String?): String {
        if (text == null) return "ERROR: text is null"
        val node = findEditableNode(root, target) ?: return "ERROR: editable node not found: $target"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val clearArgs = Bundle()
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        try {
            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        } catch (e: Exception) {
            node.recycle()
            return "ERROR: clipboard failed: ${e.message}"
        }
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "279"))
        } catch (e: Exception) {
            node.recycle()
            return "ERROR: paste failed: ${e.message}"
        }
        node.recycle()
        return "OK: typeText '$text'"
    }

    private fun doClick(root: AccessibilityNodeInfo, target: String?): String {
        if (target == null) return "ERROR: target is null"
        var node = findNodeByText(root, target)
        if (node == null) node = findNodeByResourceId(root, target)
        if (node == null) return "ERROR: node not found: $target"
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return "OK: clicked $target"
    }

    private fun doScroll(root: AccessibilityNodeInfo, direction: String?, target: String?): String {
        val scrollable = findScrollableNode(root, target) ?: return "ERROR: no scrollable node found"
        val act = if ("backward" == direction) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        scrollable.performAction(act)
        scrollable.recycle()
        return "OK: scrolled $direction"
    }

    private fun doFindAndClick(root: AccessibilityNodeInfo, target: String?): String {
        if (target == null) return "ERROR: target is null"
        var node = findNodeByTextNonEditable(root, target)
        var source = "nonEditable"
        if (node == null) { node = findNodeByText(root, target); source = "byText" }
        if (node == null) { node = findNodeByResourceId(root, target); source = "byResId" }
        if (node == null) return "ERROR: node not found: $target"

        val cls = node.className?.toString() ?: "?"
        val txt = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val b = Rect(); node.getBoundsInScreen(b)
        Log.e(OpenClawApp.TAG, "FAC: found $cls t=\"$txt\" d=\"$desc\" click=${node.isClickable} ${b.toShortString()} src=$source")

        // Use dispatchGesture to tap the center of the node (most reliable for custom views)
        val r = tapNode(node)
        node.recycle()
        return r
    }

    private fun doClickResult(root: AccessibilityNodeInfo, target: String?): String {
        if (target == null) return "ERROR: target is null"
        val node = findNodeByTextNonEditable(root, target) ?: return "ERROR: result not found: $target"

        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            node.recycle()
            return "OK: clickResult $target (direct)"
        }

        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth < 10) {
            if (ancestor.isClickable) {
                val clicked = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ancestor.recycle()
                node.recycle()
                return if (clicked) "OK: clickResult $target (ancestor depth=$depth)" else "ERROR: clickResult $target click failed"
            }
            val old = ancestor
            ancestor = ancestor.parent
            old.recycle()
            depth++
        }
        ancestor?.recycle()
        node.recycle()
        return "ERROR: clickResult $target no clickable ancestor found"
    }

    private fun tapNode(node: AccessibilityNodeInfo): String {
        val b = Rect(); node.getBoundsInScreen(b)
        val x = b.centerX().toFloat()
        val y = b.centerY().toFloat()
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
            "OK: tap ($x,$y)"
        } catch (e: Exception) {
            "ERROR: dispatchGesture failed: ${e.message}"
        }
    }

    private fun doWaitFor(text: String?, target: String?): String {
        var ms: Long = 5000
        if (text != null) try { ms = text.toLong() } catch (_: Exception) {}
        if (target.isNullOrEmpty()) return "ERROR: target is null"
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val root = getRootInActiveWindow()
            if (root != null) {
                val found = findNodeByText(root, target)
                root.recycle()
                if (found != null) { found.recycle(); return "OK: waitFor '$target' found" }
            }
            try { Thread.sleep(200) } catch (_: Exception) {}
        }
        return "ERROR: waitFor '$target' timeout"
    }

    private fun doQuery(root: AccessibilityNodeInfo, target: String?): String {
        if ("detail" == target) {
            val items = mutableListOf<String>()
            collectDetail(root, items, 0)
            return "OK:\n${items.take(100).joinToString("\n")}"
        }
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        return "OK: ${texts.take(60).joinToString(" | ")}"
    }

    private fun collectDetail(node: AccessibilityNodeInfo?, items: MutableList<String>, depth: Int) {
        if (node == null || depth > 10) return
        val t = node.text
        val desc = node.contentDescription
        val cls = node.className?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val click = node.isClickable
        val edit = node.isEditable
        val b = Rect(); node.getBoundsInScreen(b)
        if ((t != null && t.isNotEmpty()) || (desc != null && desc.isNotEmpty()) || click || edit) {
            val indent = "  ".repeat(depth)
            val sb = StringBuilder(indent)
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
            if (t != null && t.isNotEmpty()) sb.append(" t=\"$t\"")
            if (desc != null && desc.isNotEmpty()) sb.append(" d=\"$desc\"")
            if (resId.isNotEmpty()) sb.append(" id=").append(resId.substring(resId.lastIndexOf('/') + 1))
            if (click) sb.append(" [click]")
            if (edit) sb.append(" [edit]")
            sb.append(" ").append(b.toShortString())
            items.add(sb.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDetail(child, items, depth + 1)
            child.recycle()
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String?): AccessibilityNodeInfo? {
        if (node == null || text == null) return null
        val t = node.text
        if (t != null && t.toString().contains(text)) return node
        val d = node.contentDescription
        if (d != null && d.toString().contains(text)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findNodeByText(c, text)
            if (r != null) return r
            c.recycle()
        }
        return null
    }

    private fun findNodeByTextNonEditable(node: AccessibilityNodeInfo?, text: String?): AccessibilityNodeInfo? {
        if (node == null || text == null) return null
        if (!node.isEditable) {
            val t = node.text
            if (t != null && t.toString().contains(text)) return node
            val d = node.contentDescription
            if (d != null && d.toString().contains(text)) return node
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findNodeByTextNonEditable(c, text)
            if (r != null) return r
            c.recycle()
        }
        return null
    }

    private fun findNodeByResourceId(node: AccessibilityNodeInfo?, resId: String?): AccessibilityNodeInfo? {
        if (node == null || resId == null) return null
        val id = node.viewIdResourceName
        if (id != null && id.contains(resId)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findNodeByResourceId(c, resId)
            if (r != null) return r
            c.recycle()
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, target: String?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && (node.className?.toString()?.contains("EditText") == true || node.isEditable)) {
            if (target == null) return node
            val t = node.text
            val h = node.hintText
            val d = node.contentDescription
            if ((t != null && t.toString().contains(target)) || (h != null && h.toString().contains(target))
                || (d != null && d.toString().contains(target)) || target.isEmpty()) return node
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditableNode(c, target)
            if (r != null) return r
            c.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?, target: String?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && target == null) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findScrollableNode(c, target)
            if (r != null) return r
            c.recycle()
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>, depth: Int) {
        if (node == null || depth > 15) return
        val t = node.text
        if (t != null && t.isNotEmpty()) texts.add(t.toString())
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectTexts(c, texts, depth + 1)
            c.recycle()
        }
    }

    companion object {
        private var sInstance: UiAutomationService? = null
        fun getInstance(): UiAutomationService? = sInstance
    }
}
