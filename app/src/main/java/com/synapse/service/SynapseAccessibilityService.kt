package com.synapse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.synapse.model.CapturedContext

class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: SynapseAccessibilityService? = null

        fun getInstance(): SynapseAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val packageName = context.packageName
            return enabledServices?.split(":")?.any {
                it.split("/").firstOrNull() == packageName
            } == true
        }
    }

    private var currentSourceApp: String? = null
    private var currentSourceUrl: String? = null
    private var currentSelectedText: String? = null
    private var currentPageTitle: String? = null
    private var nodeCache: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                if (text.isNotBlank()) {
                    currentSelectedText = text
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentSourceApp = event.packageName?.toString()
                updateUrlFromWindow(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                updateNodeCache()
            }
        }
    }

    private fun updateUrlFromWindow(event: AccessibilityEvent) {
        val source = event.source ?: return

        val browserPackages = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser"
        )

        if (currentSourceApp in browserPackages) {
            currentSourceUrl = findUrlBar(source)?.text?.toString()
            currentPageTitle = source.contentDescription?.toString()
        }

        // recycle() is deprecated on API 26+; the system manages AccessibilityNodeInfo lifecycle
    }

    private fun findUrlBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val urlBarIds = listOf("url_bar", "url_bar_title", "search_box", "mozac_browser_toolbar_url_view")

        for (viewId in urlBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$currentSourceApp:id/$viewId")
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }
        return null
    }

    private fun updateNodeCache() {
        val root = rootInActiveWindow ?: return
        nodeCache = collectAllTextNodes(root)
    }

    private fun collectAllTextNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        if (!node.text.isNullOrBlank()) {
            nodes.add(node)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                nodes.addAll(collectAllTextNodes(child))
            }
        }

        return nodes
    }

    // === Public API ===

    fun getCurrentContext(): CapturedContext.AutoContext? {
        val app = currentSourceApp ?: return null

        return CapturedContext.AutoContext(
            sourceApp = app,
            sourceUrl = currentSourceUrl,
            pageTitle = currentPageTitle
        )
    }

    fun getSelectedText(): CapturedContext.SelectedText? {
        val text = currentSelectedText ?: return null
        if (text.isBlank()) return null

        return CapturedContext.SelectedText(
            text = text,
            sourceApp = currentSourceApp,
            sourceUrl = currentSourceUrl
        )
    }

    fun getTextInRegion(screenBounds: Rect): CapturedContext.RegionText? {
        // Refresh node cache from all windows (the overlay is the active window,
        // so rootInActiveWindow would return the overlay tree with no text)
        refreshNodeCacheFromAllWindows()

        val nodesInRegion = nodeCache.filter { node ->
            try {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)
                // Require the node's center to be inside the selection region,
                // not just any overlap — avoids grabbing surrounding text
                screenBounds.contains(nodeBounds.centerX(), nodeBounds.centerY())
            } catch (e: Exception) {
                false
            }
        }

        if (nodesInRegion.isEmpty()) return null

        val sortedNodes = nodesInRegion.sortedWith(
            compareBy({ getBounds(it).top }, { getBounds(it).left })
        )

        val combinedText = sortedNodes
            .mapNotNull { it.text?.toString() }
            .joinToString(" ")

        if (combinedText.isBlank()) return null

        return CapturedContext.RegionText(
            text = combinedText,
            bounds = screenBounds
        )
    }

    /**
     * Refreshes the node cache by scanning all windows, not just the active one.
     * This is needed because the overlay window is active during region selection,
     * but we want text from the app window behind it.
     */
    private fun refreshNodeCacheFromAllWindows() {
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            for (window in windows) {
                // Skip our own overlay windows
                if (window.root?.packageName?.toString() == packageName) continue
                window.root?.let { root ->
                    allNodes.addAll(collectAllTextNodes(root))
                }
            }
            if (allNodes.isNotEmpty()) {
                nodeCache = allNodes
            }
        } catch (e: Exception) {
            // Fallback: try rootInActiveWindow
            val root = rootInActiveWindow ?: return
            nodeCache = collectAllTextNodes(root)
        }
    }

    private fun getBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    override fun onInterrupt() {
        // Do not clear instance here — onInterrupt() is temporary and the service may resume
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
