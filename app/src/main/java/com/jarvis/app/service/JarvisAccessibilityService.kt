package com.jarvis.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val root = rootInActiveWindow ?: return
                currentScreenText = extractAllText(root)
                currentPackageName = event.packageName?.toString() ?: ""
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    // === TEXT EXTRACTION ===

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectText(node, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) sb.append(it).append(" ")
        }
        node.contentDescription?.let {
            if (it.isNotBlank() && node.text == null) sb.append(it).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    fun getScreenText(): String = currentScreenText
    fun getCurrentPackage(): String = currentPackageName

    // === CLICK OPERATIONS ===

    fun performClickOnText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val lower = targetText.lowercase()

        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            if (clickNodeOrParent(node)) return true
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(lower) || desc.contains(lower)) {
                if (clickNodeOrParent(node)) {
                    allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return true
                }
            }
        }
        allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return false
    }

    fun clickByContentDescription(targetDesc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val lower = targetDesc.lowercase()
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains(lower)) {
                if (clickNodeOrParent(node)) {
                    allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return true
                }
            }
        }
        allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val grandparent = parent.parent
            parent.recycle()
            parent = grandparent
            depth++
        }
        return false
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list)
            child.recycle()
        }
    }

    fun searchAndClick(targetText: String, maxScrolls: Int = 5): Boolean {
        if (performClickOnText(targetText)) return true
        for (i in 0 until maxScrolls) {
            if (!scrollDown()) break
            try { Thread.sleep(600) } catch (_: Exception) {}
            if (performClickOnText(targetText)) return true
        }
        return false
    }

    // === TYPE OPERATIONS ===

    fun performTypeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            return result
        }
        val editFields = findEditableNodes(root)
        if (editFields.isNotEmpty()) {
            val target = editFields.last()
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            try { Thread.sleep(200) } catch (_: Exception) {}
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            editFields.forEach { try { it.recycle() } catch (_: Exception) {} }
            return result
        }
        return false
    }

    fun tapEditFieldAndType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editFields = findEditableNodes(root)
        if (editFields.isNotEmpty()) {
            val target = editFields.last()
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            try { Thread.sleep(300) } catch (_: Exception) {}
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            editFields.forEach { try { it.recycle() } catch (_: Exception) {} }
            return result
        }
        return false
    }

    // === KEYBOARD / IME ACTIONS ===

    fun pressKeyboardAction(): Boolean {
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val root = window.root ?: continue
                    val actionTexts = listOf("Search", "Go", "Enter", "Done", "Send", "search", "go")
                    for (text in actionTexts) {
                        val nodes = root.findAccessibilityNodeInfosByText(text)
                        for (node in nodes) {
                            if (node.isClickable) {
                                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                node.recycle()
                                root.recycle()
                                if (result) return true
                            }
                            node.recycle()
                        }
                    }
                    val allKbNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodes(root, allKbNodes)
                    for (node in allKbNodes) {
                        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                        if ((desc.contains("search") || desc.contains("enter") ||
                             desc.contains("go") || desc.contains("done") || desc.contains("send")) &&
                            node.isClickable) {
                            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            allKbNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                            root.recycle()
                            if (result) return true
                        }
                    }
                    allKbNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    root.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pressKeyboardAction error", e)
        }
        return false
    }

    // === SEARCH & SEND ===

    fun clickSearchField(): Boolean {
        val root = rootInActiveWindow ?: return false
        val searchTexts = listOf(
            "Search", "search", "Search…", "Search or type URL",
            "সার্চ", "খুঁজুন", "Search...", "Type a message"
        )
        for (text in searchTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable || node.isEditable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.recycle()
                    return true
                }
                if (clickNodeOrParent(node)) {
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("search") || desc.contains("সার্চ")) {
                if (node.isClickable) {
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    if (result) return true
                }
            }
        }
        allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return false
    }

    fun clickSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            if ((desc.contains("send") || desc.contains("পাঠ") ||
                 text == "send" || text == "পাঠান") && node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (result) return true
            }
        }
        allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return false
    }

    fun clickFirstClickableItem(): Boolean {
        val root = rootInActiveWindow ?: return false
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            if (node.isClickable && !node.text.isNullOrBlank() &&
                node.className?.toString() != "android.widget.EditText") {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                return result
            }
        }
        allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return false
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (node.isEditable) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            results.addAll(findEditableNodes(child))
            child.recycle()
        }
        return results
    }

    // === APP LAUNCH ===

    fun openApp(packageName: String): Boolean {
        return try {
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(intent)
                true
            } else false
        } catch (e: Exception) { false }
    }

    // === SCROLL ===

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findScrollableAndScroll(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findScrollableAndScroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findScrollableAndScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) return node.performAction(action)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findScrollableAndScroll(child, action)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // === GLOBAL ACTIONS ===

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // === GESTURES ===

    fun tapAtPosition(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
            private set
        var currentScreenText: String = ""
            private set
        var currentPackageName: String = ""
            private set
        fun isRunning(): Boolean = instance != null
    }
}
