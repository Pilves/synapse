package com.synapse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.synapse.model.CapturedContext

class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_NODE_DEPTH = 50

        @Volatile
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

    /**
     * Packages excluded from accessibility event processing.
     * Banking, password managers, and other sensitive app categories.
     */
    private val excludedPackages = setOf(
        // Banking apps
        "com.chase.sig.android",
        "com.bankofamerica.cashpromobile",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.usaa.mobile.android.usaa",
        "com.ally.MobileBanking",
        // Password managers
        "com.onepassword.android",
        "com.lastpass.lpandroid",
        "com.x8bit.bitwarden",
        "com.dashlane",
        "keepass2android.keepass2android",
        // Payment apps
        "com.venmo",
        "com.squareup.cash",
        "com.paypal.android.p2pmobile",
        // Authenticator apps
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "org.fedorahosted.freeotp"
    )

    private var currentSourceApp: String? = null
    private var currentSourceUrl: String? = null
    private var currentSelectedText: String? = null
    private var currentPageTitle: String? = null
    @Volatile
    private var nodeCache: List<AccessibilityNodeInfo> = emptyList()

    /** Listener notified when a different app window comes to the foreground. */
    var onWindowChanged: (() -> Unit)? = null

    /** Listener notified when the back button is pressed. Return true to consume the event. */
    var onBackPressed: (() -> Boolean)? = null

    override fun onServiceConnected() {
        instance = this
        broadcastHealthState(true)

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Skip events from excluded sensitive apps
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg in excludedPackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                if (text.isNotBlank()) {
                    currentSelectedText = text
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentSourceApp = event.packageName?.toString()
                // Notify listener when a different app comes to the foreground
                if (currentSourceApp != null && currentSourceApp != packageName) {
                    onWindowChanged?.invoke()
                }
                // Clear cached data when switching to an excluded app
                if (currentSourceApp in excludedPackages) {
                    nodeCache = emptyList()
                    currentSelectedText = null
                    currentSourceUrl = null
                    currentPageTitle = null
                    return
                }
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
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectAllTextNodes(root, result, 0)
        nodeCache = result
    }

    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth >= MAX_NODE_DEPTH) return

        if (!node.text.isNullOrBlank()) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectAllTextNodes(child, result, depth + 1)
            }
        }
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
                    collectAllTextNodes(root, allNodes, 0)
                }
            }
            if (allNodes.isNotEmpty()) {
                nodeCache = allNodes
            }
        } catch (e: Exception) {
            // Fallback: try rootInActiveWindow
            val root = rootInActiveWindow ?: return
            val result = mutableListOf<AccessibilityNodeInfo>()
            collectAllTextNodes(root, result, 0)
            nodeCache = result
        }
    }

    private fun getBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val consumed = onBackPressed?.invoke() ?: false
            if (consumed) return true
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        // Do not clear instance here — onInterrupt() is temporary and the service may resume
    }

    override fun onDestroy() {
        instance = null
        broadcastHealthState(false)
        super.onDestroy()
    }

    private fun broadcastHealthState(connected: Boolean) {
        try {
            val intent = Intent(PermissionHealthMonitor.ACTION_ACCESSIBILITY_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(PermissionHealthMonitor.EXTRA_IS_CONNECTED, connected)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            // Ignore broadcast failures
        }
    }
}
