# SYNAPSE - Technical Specification v2 (Feature Expansion)

## Overview

This document specifies new features to expand Synapse beyond Obsidian-only usage. These are additions to the existing v1 specification.

**New Capabilities:**
- Multi-destination sync (local folder, clipboard, share intent, Notion, Google Docs)
- Context capture (selected text, source URL, region grab)
- Intent detection (note, task, question, reminder)
- Smart LLM routing (separate transcription/answering providers)
- Cost tracking and estimation

**Design Principles:**
- Every permission skippable, app degrades gracefully
- LLM handles complexity, user doesn't manage modes
- Privacy by default, context deletable before sync
- Free tier friendly (Gemini 2.5 Flash as default)

---

## Feature 1: Destination Abstraction

### Architecture

```kotlin
// === Core Interface ===

interface Destination {
    val id: String
    val displayName: String
    val iconRes: Int
    val requiresAuth: Boolean
    
    suspend fun configure(): DestinationConfig
    suspend fun send(content: SyncContent): SyncResult
    suspend fun testConnection(): Boolean
}

data class SyncContent(
    val markdown: String,
    val plainText: String,
    val metadata: NoteMetadata,
    val contexts: List<CapturedContext>
)

data class NoteMetadata(
    val timestamp: Long,
    val intentType: IntentType,
    val sourceApp: String?,
    val sourceUrl: String?
)

sealed class SyncResult {
    object Success : SyncResult()
    data class PartialSuccess(val message: String) : SyncResult()
    data class Failure(val error: SyncError) : SyncResult()
}

enum class SyncError {
    NETWORK,
    AUTH_EXPIRED,
    RATE_LIMITED,
    PERMISSION_DENIED,
    NOT_FOUND,
    UNKNOWN
}
```

### Destination Implementations

```kotlin
// === Local Folder (existing, refactored) ===

class LocalFolderDestination(
    private val safHelper: SafHelper
) : Destination {
    override val id = "local_folder"
    override val displayName = "Local Folder"
    override val iconRes = R.drawable.ic_folder
    override val requiresAuth = false
    
    override suspend fun send(content: SyncContent): SyncResult {
        return try {
            safHelper.appendToFile(
                uri = config.fileUri,
                content = content.markdown
            )
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Failure(SyncError.PERMISSION_DENIED)
        }
    }
}

// === Clipboard ===

class ClipboardDestination(
    private val clipboardManager: ClipboardManager
) : Destination {
    override val id = "clipboard"
    override val displayName = "Clipboard"
    override val iconRes = R.drawable.ic_clipboard
    override val requiresAuth = false
    
    override suspend fun send(content: SyncContent): SyncResult {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("Synapse Note", content.markdown)
        )
        return SyncResult.Success
    }
    
    override suspend fun testConnection(): Boolean = true
}

// === Share Intent ===

class ShareIntentDestination(
    private val context: Context
) : Destination {
    override val id = "share"
    override val displayName = "Share to..."
    override val iconRes = R.drawable.ic_share
    override val requiresAuth = false
    
    private val markdownFriendlyApps = setOf(
        "com.discord",
        "com.slack",
        "md.obsidian",
        "notion.id",
        "it.feio.android.omninotes",
        "com.logseq.app"
    )
    
    override suspend fun send(content: SyncContent): SyncResult {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content.plainText)
        }
        context.startActivity(Intent.createChooser(intent, "Share note"))
        return SyncResult.Success
    }
    
    fun formatForPackage(packageName: String, content: SyncContent): String {
        return if (packageName in markdownFriendlyApps) {
            content.markdown
        } else {
            content.plainText
        }
    }
}

// === Notion ===

class NotionDestination(
    private val notionApi: NotionApi,
    private val authManager: AuthManager
) : Destination {
    override val id = "notion"
    override val displayName = "Notion"
    override val iconRes = R.drawable.ic_notion
    override val requiresAuth = true
    
    data class NotionConfig(
        val mode: NotionMode,
        val pageId: String?,
        val databaseId: String?,
        val parentPageId: String?
    )
    
    enum class NotionMode {
        APPEND_PAGE,
        NEW_PAGE,
        DATABASE_ROW
    }
    
    override suspend fun send(content: SyncContent): SyncResult {
        val token = authManager.getToken("notion") 
            ?: return SyncResult.Failure(SyncError.AUTH_EXPIRED)
            
        return when (config.mode) {
            NotionMode.APPEND_PAGE -> appendToPage(token, content)
            NotionMode.NEW_PAGE -> createNewPage(token, content)
            NotionMode.DATABASE_ROW -> addDatabaseRow(token, content)
        }
    }
    
    private suspend fun appendToPage(token: String, content: SyncContent): SyncResult {
        val blocks = markdownToNotionBlocks(content.markdown)
        return try {
            notionApi.appendBlocks(config.pageId!!, blocks, token)
            SyncResult.Success
        } catch (e: NotionApiException) {
            SyncResult.Failure(mapNotionError(e))
        }
    }
    
    private suspend fun createNewPage(token: String, content: SyncContent): SyncResult {
        val page = NotionPage(
            parent = Parent.Page(config.parentPageId!!),
            properties = mapOf(
                "title" to TitleProperty(content.metadata.timestamp.toFormattedDate())
            ),
            children = markdownToNotionBlocks(content.markdown)
        )
        return try {
            notionApi.createPage(page, token)
            SyncResult.Success
        } catch (e: NotionApiException) {
            SyncResult.Failure(mapNotionError(e))
        }
    }
    
    private suspend fun addDatabaseRow(token: String, content: SyncContent): SyncResult {
        val properties = mapOf(
            "Name" to TitleProperty(content.markdown.take(100)),
            "Content" to RichTextProperty(content.markdown),
            "Created" to DateProperty(content.metadata.timestamp),
            "Type" to SelectProperty(content.metadata.intentType.name),
            "Source" to UrlProperty(content.metadata.sourceUrl)
        )
        return try {
            notionApi.createDatabaseItem(config.databaseId!!, properties, token)
            SyncResult.Success
        } catch (e: NotionApiException) {
            SyncResult.Failure(mapNotionError(e))
        }
    }
}

// === Google Docs ===

class GoogleDocsDestination(
    private val docsApi: GoogleDocsApi,
    private val authManager: AuthManager
) : Destination {
    override val id = "google_docs"
    override val displayName = "Google Docs"
    override val iconRes = R.drawable.ic_google_docs
    override val requiresAuth = true
    
    data class GoogleDocsConfig(
        val mode: GoogleDocsMode,
        val documentId: String?,
        val folderId: String?
    )
    
    enum class GoogleDocsMode {
        APPEND_DOC,
        NEW_DOC
    }
    
    override suspend fun send(content: SyncContent): SyncResult {
        val token = authManager.getToken("google")
            ?: return SyncResult.Failure(SyncError.AUTH_EXPIRED)
            
        return when (config.mode) {
            GoogleDocsMode.APPEND_DOC -> appendToDoc(token, content)
            GoogleDocsMode.NEW_DOC -> createNewDoc(token, content)
        }
    }
}
```

### Destination Repository

```kotlin
class DestinationRepository(
    private val dataStore: DataStore<Preferences>,
    private val destinations: Map<String, Destination>
) {
    private val _mainDestination = MutableStateFlow<String>("clipboard")
    val mainDestination: StateFlow<String> = _mainDestination
    
    suspend fun getDestination(id: String): Destination? = destinations[id]
    
    suspend fun getAllDestinations(): List<Destination> = destinations.values.toList()
    
    suspend fun getConfiguredDestinations(): List<Destination> {
        return destinations.values.filter { dest ->
            !dest.requiresAuth || authManager.hasValidToken(dest.id)
        }
    }
    
    suspend fun setMainDestination(id: String) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.MAIN_DESTINATION] = id
        }
        _mainDestination.value = id
    }
}
```

### Per-Session Destination Selection

```kotlin
// In ReviewViewModel
data class SessionSyncConfig(
    val sessionId: String,
    val destinations: List<String>,  // Multiple destinations allowed
    val contexts: List<CapturedContext>  // Editable before sync
)

class ReviewViewModel(
    private val destinationRepository: DestinationRepository,
    private val syncManager: SyncManager
) : ViewModel() {
    
    private val _sessionConfigs = MutableStateFlow<Map<String, SessionSyncConfig>>(emptyMap())
    val sessionConfigs: StateFlow<Map<String, SessionSyncConfig>> = _sessionConfigs
    
    fun setSessionDestinations(sessionId: String, destinations: List<String>) {
        _sessionConfigs.update { configs ->
            configs + (sessionId to configs[sessionId]!!.copy(destinations = destinations))
        }
    }
    
    fun addDestinationToSession(sessionId: String, destinationId: String) {
        _sessionConfigs.update { configs ->
            val current = configs[sessionId]!!
            configs + (sessionId to current.copy(
                destinations = current.destinations + destinationId
            ))
        }
    }
}
```

### UI: Destination Selection in Review

```kotlin
@Composable
fun SessionCard(
    session: Session,
    config: SessionSyncConfig,
    availableDestinations: List<Destination>,
    onDestinationChange: (List<String>) -> Unit,
    onAddDestination: () -> Unit
) {
    Card {
        Column {
            // Session header
            Text("Session ${session.timestamp.toFormattedTime()}")
            
            // Chunk thumbnails
            ChunkThumbnailRow(session.chunks)
            
            // Destination selection
            Row {
                // Primary destination dropdown
                DestinationDropdown(
                    selected = config.destinations.firstOrNull(),
                    options = availableDestinations,
                    onSelect = { dest ->
                        onDestinationChange(listOf(dest) + config.destinations.drop(1))
                    }
                )
                
                // Additional destinations
                config.destinations.drop(1).forEach { destId ->
                    DestinationChip(
                        destinationId = destId,
                        onRemove = {
                            onDestinationChange(config.destinations - destId)
                        }
                    )
                }
                
                // Add another
                IconButton(onClick = onAddDestination) {
                    Icon(Icons.Default.Add, "Add destination")
                }
            }
        }
    }
}
```

---

## Feature 2: Context Capture System

### Data Models

```kotlin
sealed class CapturedContext {
    abstract val id: String
    abstract val timestamp: Long
    
    data class SelectedText(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val sourceApp: String?,
        val sourceUrl: String?
    ) : CapturedContext()
    
    data class RegionText(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val bounds: Rect
    ) : CapturedContext()
    
    data class RegionImage(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val imagePath: String,
        val bounds: Rect,
        val description: String? = null  // Filled by LLM later
    ) : CapturedContext()
    
    data class AutoContext(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val sourceApp: String,
        val sourceUrl: String?,
        val pageTitle: String?
    ) : CapturedContext()
}

data class CaptureSession(
    val id: String,
    val startTime: Long,
    val chunks: List<Chunk>,
    val contexts: MutableList<CapturedContext>
)
```

### Accessibility Service

```kotlin
class SynapseAccessibilityService : AccessibilityService() {
    
    companion object {
        private var instance: SynapseAccessibilityService? = null
        
        fun getInstance(): SynapseAccessibilityService? = instance
        
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(context.packageName) == true
        }
    }
    
    private var currentSourceApp: String? = null
    private var currentSourceUrl: String? = null
    private var currentSelectedText: String? = null
    private var currentPageTitle: String? = null
    private var nodeCache: List<AccessibilityNodeInfo> = emptyList()
    
    override fun onServiceConnected() {
        instance = this
        
        val config = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = config
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
        
        // Browser URL detection
        val browserPackages = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx"
        )
        
        if (currentSourceApp in browserPackages) {
            currentSourceUrl = findUrlBar(source)?.text?.toString()
            currentPageTitle = findPageTitle(source)
        }
        
        source.recycle()
    }
    
    private fun findUrlBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Chrome: "com.android.chrome:id/url_bar"
        // Firefox: "org.mozilla.firefox:id/url_bar_title"
        val urlBarIds = listOf("url_bar", "url_bar_title", "search_box")
        
        return findNodeByViewIds(root, urlBarIds)
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
        val nodesInRegion = nodeCache.filter { node ->
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            Rect.intersects(nodeBounds, screenBounds)
        }
        
        if (nodesInRegion.isEmpty()) return null
        
        // Sort by position (top to bottom, left to right)
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
    
    private fun getBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }
    
    override fun onInterrupt() {
        instance = null
    }
    
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
```

### Manifest Declaration

```xml
<service
    android:name=".service.SynapseAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### res/xml/accessibility_service_config.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_description"
    android:accessibilityEventTypes="typeViewTextSelectionChanged|typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:settingsActivity=".ui.settings.SettingsActivity" />
```

### Process Text Intent (Fallback)

```kotlin
// In AndroidManifest.xml
<activity
    android:name=".ui.ProcessTextActivity"
    android:label="Synapse"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>

// ProcessTextActivity.kt
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        
        if (text != null) {
            // Store context and launch overlay
            ContextHolder.pendingContext = CapturedContext.SelectedText(
                text = text,
                sourceApp = referrer?.host,
                sourceUrl = null
            )
            
            // Start overlay service
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_WITH_CONTEXT
            }
            startService(overlayIntent)
        }
        
        finish()
    }
}
```

---

## Feature 3: Region Capture

### Gesture Detection

```kotlin
class RegionGestureDetector(
    private val onRegionSelected: (Rect) -> Unit
) {
    private var isHolding = false
    private var holdStartTime = 0L
    private var holdStartPoint: PointF? = null
    private var currentRect: Rect? = null
    
    private val holdThresholdMs = 500L
    private val movementThreshold = 10f  // pixels
    
    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable {
        isHolding = true
        // Vibrate feedback
        vibrate()
    }
    
    fun onTouchEvent(event: MotionEvent): RegionGestureResult {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                holdStartTime = System.currentTimeMillis()
                holdStartPoint = PointF(event.x, event.y)
                handler.postDelayed(holdRunnable, holdThresholdMs)
                RegionGestureResult.Pending
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isHolding) {
                    // Update selection rectangle
                    val start = holdStartPoint!!
                    currentRect = Rect(
                        minOf(start.x, event.x).toInt(),
                        minOf(start.y, event.y).toInt(),
                        maxOf(start.x, event.x).toInt(),
                        maxOf(start.y, event.y).toInt()
                    )
                    RegionGestureResult.SelectionInProgress(currentRect!!)
                } else {
                    // Check if moved too much before hold triggered
                    val distance = hypot(
                        event.x - holdStartPoint!!.x,
                        event.y - holdStartPoint!!.y
                    )
                    if (distance > movementThreshold) {
                        handler.removeCallbacks(holdRunnable)
                        RegionGestureResult.Stroke  // Normal drawing
                    } else {
                        RegionGestureResult.Pending
                    }
                }
            }
            
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                
                if (isHolding && currentRect != null) {
                    val rect = currentRect!!
                    isHolding = false
                    currentRect = null
                    holdStartPoint = null
                    
                    if (rect.width() > 50 && rect.height() > 50) {
                        onRegionSelected(rect)
                        RegionGestureResult.SelectionComplete(rect)
                    } else {
                        RegionGestureResult.SelectionCancelled
                    }
                } else {
                    isHolding = false
                    holdStartPoint = null
                    RegionGestureResult.Stroke
                }
            }
            
            else -> RegionGestureResult.Ignored
        }
    }
    
    private fun vibrate() {
        // Haptic feedback when hold triggers
    }
}

sealed class RegionGestureResult {
    object Pending : RegionGestureResult()
    object Stroke : RegionGestureResult()
    data class SelectionInProgress(val rect: Rect) : RegionGestureResult()
    data class SelectionComplete(val rect: Rect) : RegionGestureResult()
    object SelectionCancelled : RegionGestureResult()
    object Ignored : RegionGestureResult()
}
```

### Region Capture Logic

```kotlin
class RegionCaptureManager(
    private val accessibilityService: () -> SynapseAccessibilityService?,
    private val screenshotManager: ScreenshotManager
) {
    suspend fun captureRegion(screenBounds: Rect): CapturedContext {
        // Try text first
        val textContext = accessibilityService()?.getTextInRegion(screenBounds)
        
        if (textContext != null && textContext.text.isNotBlank()) {
            return textContext
        }
        
        // Fall back to screenshot
        return captureRegionScreenshot(screenBounds)
    }
    
    private suspend fun captureRegionScreenshot(bounds: Rect): CapturedContext.RegionImage {
        val bitmap = screenshotManager.captureRegion(bounds)
        val path = saveScreenshot(bitmap)
        
        return CapturedContext.RegionImage(
            imagePath = path,
            bounds = bounds,
            description = null  // LLM will fill this
        )
    }
    
    private suspend fun saveScreenshot(bitmap: Bitmap): String {
        val file = File(cacheDir, "region_${System.currentTimeMillis()}.webp")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
        }
        return file.absolutePath
    }
}
```

### Screenshot Manager (MediaProjection)

```kotlin
class ScreenshotManager(private val context: Context) {
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var permissionGranted = false
    
    fun hasPermission(): Boolean = permissionGranted
    
    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
        permissionGranted = true
    }
    
    suspend fun captureRegion(bounds: Rect): Bitmap = withContext(Dispatchers.IO) {
        val projection = mediaProjection 
            ?: throw IllegalStateException("MediaProjection not initialized")
        
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = projection.createVirtualDisplay(
            "SynapseCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        
        delay(100)  // Wait for capture
        
        val image = imageReader!!.acquireLatestImage()
            ?: throw IllegalStateException("Failed to capture screen")
        
        val fullBitmap = imageToBitmap(image)
        image.close()
        
        virtualDisplay?.release()
        imageReader?.close()
        
        // Crop to region
        Bitmap.createBitmap(
            fullBitmap,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()
        )
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
```

### MediaProjection Permission Flow

```kotlin
class MediaProjectionHelper(private val activity: ComponentActivity) {
    
    private val projectionManager = activity.getSystemService(
        Context.MEDIA_PROJECTION_SERVICE
    ) as MediaProjectionManager
    
    private var pendingCallback: ((MediaProjection?) -> Unit)? = null
    
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projection = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionManager.getMediaProjection(result.resultCode, result.data!!)
        } else {
            null
        }
        pendingCallback?.invoke(projection)
        pendingCallback = null
    }
    
    fun requestPermission(callback: (MediaProjection?) -> Unit) {
        pendingCallback = callback
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }
}
```

---

## Feature 4: Intent Detection

### Intent Types

```kotlin
enum class IntentType {
    NOTE,       // General information
    TASK,       // Action item
    QUESTION,   // User wants answer
    REMINDER,   // Time-based alert
    REACTION    // Response to context
}

data class DetectedIntent(
    val type: IntentType,
    val confidence: Float,  // 0.0 - 1.0
    val extractedData: IntentData?
)

sealed class IntentData {
    data class TaskData(
        val taskText: String,
        val deadline: String?  // Natural language: "tomorrow", "next week"
    ) : IntentData()
    
    data class QuestionData(
        val question: String,
        val answer: String?  // Filled by answering LLM
    ) : IntentData()
    
    data class ReminderData(
        val reminderText: String,
        val time: String?,   // Natural language
        val parsedTime: Long?  // Epoch millis, if parseable
    ) : IntentData()
}
```

### Updated LLM Prompt Template

```kotlin
val TRANSCRIPTION_PROMPT_V2 = """
You transcribe handwritten notes to structured JSON with intent detection.

Input: Image chunks with timestamps, plus optional context (selected text, URLs, region captures).

Variables:
- CLEANUP_MODE: {cleanup_enabled}
- ADVANCED_FORMATTING: {advanced_formatting}

Phase 1 - Transcribe:
- Read each chunk's handwritten text
- Illegible: "[unclear: best guess?]"
- Diagrams/arrows: see Phase 4
- Empty/accidental marks: skip
- Ignore crossed-out words
- Preserve [[wikilinks]] if user draws brackets

Phase 2 - Detect Intent:
For each logical thought, classify:
- NOTE: General information to save
- TASK: Action item (look for: "todo", "need to", "must", "should", action verbs)
- QUESTION: User wants answer (look for: "?", "what", "why", "how", "who")
- REMINDER: Time-based alert (look for: time references + action)
- REACTION: Response to provided context (only if context is present)

Confidence threshold:
- High confidence (>0.8): proceed with detected intent
- Low confidence (<0.8): set needsConfirmation: true

Phase 3 - Extract Intent Data:
- TASK: Extract deadline if mentioned ("tomorrow", "by Friday", "next week")
- QUESTION: Keep question text separate
- REMINDER: Extract time reference and reminder text

Phase 4 - Format:
- Format each note for readability using markdown
- Tasks: format as "- [ ] task text"
- Questions: format question clearly
- Use headings, lists, bold where appropriate
- Don't over-format simple notes
- If ADVANCED_FORMATTING enabled:
  - Diagrams/flowcharts: convert to Mermaid syntax
  - Math/equations: convert to LaTeX ($...$)
  - Tables: convert to markdown tables
- If ADVANCED_FORMATTING disabled:
  - Diagrams: "[diagram: brief description]"
  - Equations: "[equation: description]"

Phase 5 - Handle Context:
If context provided:
- Reference it naturally in the note
- For REACTION type: quote relevant part of context
- Include source URL if available
- For RegionImage: describe what you see in the image

Cleanup (if enabled): Fix spelling/grammar, expand abbreviations.

Output ONLY valid JSON, no markdown fencing:
{
  "notes": [
    {
      "text": "formatted markdown content",
      "chunks_used": [0, 1],
      "intent": {
        "type": "NOTE|TASK|QUESTION|REMINDER|REACTION",
        "confidence": 0.95,
        "needsConfirmation": false,
        "data": {
          // For TASK:
          "deadline": "tomorrow",
          // For QUESTION:
          "question": "what is X?",
          // For REMINDER:
          "time": "5pm tomorrow",
          "parsedTime": 1706900400000
        }
      }
    }
  ],
  "contexts_used": ["context_id_1", "context_id_2"]
}
""".trimIndent()
```

### Question Answering Flow

```kotlin
class QuestionAnswerService(
    private val answeringProvider: LlmProvider,  // Can be different from transcription
    private val transcriptionProvider: LlmProvider
) {
    suspend fun processTranscription(
        chunks: List<ChunkData>,
        contexts: List<CapturedContext>,
        settings: TranscriptionSettings
    ): ProcessedResult {
        
        // Step 1: Transcribe with intent detection
        val transcriptionResult = transcriptionProvider.transcribe(
            chunks = chunks,
            contexts = contexts,
            prompt = TRANSCRIPTION_PROMPT_V2,
            settings = settings
        )
        
        // Step 2: Find questions that need answers
        val questions = transcriptionResult.notes.filter { 
            it.intent.type == IntentType.QUESTION 
        }
        
        if (questions.isEmpty()) {
            return ProcessedResult(
                notes = transcriptionResult.notes,
                questions = emptyList()
            )
        }
        
        // Step 3: Answer questions
        val answeredQuestions = questions.map { note ->
            val question = (note.intent.data as IntentData.QuestionData).question
            val answer = answeringProvider.answerQuestion(question, contexts)
            
            QuestionWithAnswer(
                originalNote = note,
                question = question,
                answer = answer
            )
        }
        
        return ProcessedResult(
            notes = transcriptionResult.notes.filter { 
                it.intent.type != IntentType.QUESTION 
            },
            questions = answeredQuestions
        )
    }
}

data class QuestionWithAnswer(
    val originalNote: TranscribedNote,
    val question: String,
    val answer: String
)

data class ProcessedResult(
    val notes: List<TranscribedNote>,
    val questions: List<QuestionWithAnswer>
)
```

### Intent Confirmation UI

```kotlin
@Composable
fun IntentConfirmationDialog(
    note: TranscribedNote,
    onConfirm: (IntentType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Is this a task?") },
        text = { 
            Text(
                note.text.take(100) + if (note.text.length > 100) "..." else ""
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(IntentType.TASK) }) {
                Text("Yes, it's a task")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(IntentType.NOTE) }) {
                Text("No, just a note")
            }
        }
    )
    
    // Auto-dismiss after 5 seconds, default to NOTE
    LaunchedEffect(Unit) {
        delay(5000)
        onConfirm(IntentType.NOTE)
    }
}

@Composable
fun QuestionAnswerDialog(
    questionWithAnswer: QuestionWithAnswer,
    onSaveBoth: () -> Unit,
    onSaveQuestionOnly: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("Question Detected") },
        text = {
            Column {
                Text("Your question:", style = MaterialTheme.typography.labelMedium)
                Text(questionWithAnswer.question)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Answer:", style = MaterialTheme.typography.labelMedium)
                Text(questionWithAnswer.answer)
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveBoth) {
                Text("Save both")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSaveQuestionOnly) {
                    Text("Save question only")
                }
                TextButton(onClick = onDiscard) {
                    Text("Discard")
                }
            }
        }
    )
}

@Composable
fun ReminderDialog(
    note: TranscribedNote,
    onCreateAlarm: () -> Unit,
    onCreateCalendarEvent: () -> Unit,
    onSaveAsNote: () -> Unit
) {
    val reminderData = note.intent.data as IntentData.ReminderData
    
    AlertDialog(
        onDismissRequest = onSaveAsNote,
        title = { Text("Reminder Detected") },
        text = {
            Column {
                Text(reminderData.reminderText)
                reminderData.time?.let {
                    Text("Time: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onCreateAlarm) {
                    Row {
                        Icon(Icons.Default.Alarm, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create Alarm")
                    }
                }
                TextButton(onClick = onCreateCalendarEvent) {
                    Row {
                        Icon(Icons.Default.Event, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add to Calendar")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onSaveAsNote) {
                Text("Just save as note")
            }
        }
    )
}
```

### Reminder Creation

```kotlin
class ReminderManager(private val context: Context) {
    
    fun createAlarm(reminderData: IntentData.ReminderData, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            reminderData.parsedTime?.let { time ->
                val calendar = Calendar.getInstance().apply { timeInMillis = time }
                putExtra(AlarmClock.EXTRA_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }
    
    fun createCalendarEvent(reminderData: IntentData.ReminderData, title: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            reminderData.parsedTime?.let { time ->
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, time + 3600000) // +1 hour
            }
        }
        
        context.startActivity(intent)
    }
}
```

---

## Feature 5: Cost Tracking

### Cost Calculator

```kotlin
object LlmCostCalculator {
    
    // Prices per 1M tokens (as of 2025)
    private val pricing = mapOf(
        "gemini-2.5-flash" to TokenPricing(input = 0.0, output = 0.0),  // Free tier
        "gemini-1.5-pro" to TokenPricing(input = 3.50, output = 10.50),
        "claude-3-haiku" to TokenPricing(input = 0.25, output = 1.25),
        "claude-3-sonnet" to TokenPricing(input = 3.00, output = 15.00),
        "gpt-4o-mini" to TokenPricing(input = 0.15, output = 0.60),
        "gpt-4o" to TokenPricing(input = 5.00, output = 15.00)
    )
    
    // Approximate tokens per KB of image
    private const val TOKENS_PER_KB_IMAGE = 85  // Rough estimate
    
    // Approximate output tokens per chunk
    private const val OUTPUT_TOKENS_PER_CHUNK = 150
    
    data class TokenPricing(
        val input: Double,   // Per 1M tokens
        val output: Double   // Per 1M tokens
    )
    
    data class CostEstimate(
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedCost: Double,
        val model: String
    )
    
    fun estimateCost(
        chunks: List<ChunkData>,
        contexts: List<CapturedContext>,
        model: String
    ): CostEstimate {
        val pricing = pricing[model] ?: return CostEstimate(0, 0, 0.0, model)
        
        // Calculate input tokens
        val imageBytes = chunks.sumOf { it.image.size }
        val imageTokens = (imageBytes / 1024) * TOKENS_PER_KB_IMAGE
        
        val contextTokens = contexts.sumOf { context ->
            when (context) {
                is CapturedContext.SelectedText -> context.text.length / 4
                is CapturedContext.RegionText -> context.text.length / 4
                is CapturedContext.RegionImage -> 500  // Image description
                is CapturedContext.AutoContext -> 50   // Metadata
            }
        }
        
        val promptTokens = 500  // Base prompt template
        
        val totalInputTokens = imageTokens + contextTokens + promptTokens
        
        // Estimate output tokens
        val outputTokens = chunks.size * OUTPUT_TOKENS_PER_CHUNK
        
        // Calculate cost
        val inputCost = (totalInputTokens / 1_000_000.0) * pricing.input
        val outputCost = (outputTokens / 1_000_000.0) * pricing.output
        val totalCost = inputCost + outputCost
        
        return CostEstimate(
            inputTokens = totalInputTokens,
            outputTokens = outputTokens,
            estimatedCost = totalCost,
            model = model
        )
    }
}
```

### Usage Tracking

```kotlin
class UsageTracker(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val TOTAL_COST_KEY = doublePreferencesKey("total_cost")
        private val MONTHLY_COST_KEY = doublePreferencesKey("monthly_cost")
        private val MONTHLY_SYNCS_KEY = intPreferencesKey("monthly_syncs")
        private val MONTH_KEY = intPreferencesKey("current_month")
    }
    
    val usageStats: Flow<UsageStats> = dataStore.data.map { prefs ->
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        val storedMonth = prefs[MONTH_KEY] ?: currentMonth
        
        if (currentMonth != storedMonth) {
            // New month, reset
            UsageStats(
                totalCost = prefs[TOTAL_COST_KEY] ?: 0.0,
                monthlyCost = 0.0,
                monthlySyncs = 0
            )
        } else {
            UsageStats(
                totalCost = prefs[TOTAL_COST_KEY] ?: 0.0,
                monthlyCost = prefs[MONTHLY_COST_KEY] ?: 0.0,
                monthlySyncs = prefs[MONTHLY_SYNCS_KEY] ?: 0
            )
        }
    }
    
    suspend fun recordSync(cost: Double) {
        dataStore.edit { prefs ->
            val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
            val storedMonth = prefs[MONTH_KEY] ?: currentMonth
            
            if (currentMonth != storedMonth) {
                // Reset monthly counters
                prefs[MONTHLY_COST_KEY] = cost
                prefs[MONTHLY_SYNCS_KEY] = 1
                prefs[MONTH_KEY] = currentMonth
            } else {
                prefs[MONTHLY_COST_KEY] = (prefs[MONTHLY_COST_KEY] ?: 0.0) + cost
                prefs[MONTHLY_SYNCS_KEY] = (prefs[MONTHLY_SYNCS_KEY] ?: 0) + 1
            }
            
            prefs[TOTAL_COST_KEY] = (prefs[TOTAL_COST_KEY] ?: 0.0) + cost
        }
    }
}

data class UsageStats(
    val totalCost: Double,
    val monthlyCost: Double,
    val monthlySyncs: Int
)
```

### Cost Display UI

```kotlin
@Composable
fun SyncConfirmation(
    sessions: List<Session>,
    costEstimate: CostEstimate,
    onSync: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Ready to sync ${sessions.size} sessions",
                style = MaterialTheme.typography.titleMedium
            )
            
            val totalChunks = sessions.sumOf { it.chunks.size }
            val totalSize = sessions.sumOf { session ->
                session.chunks.sumOf { it.imageSize }
            }
            
            Text(
                "$totalChunks chunks • ${formatSize(totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (costEstimate.estimatedCost > 0) {
                Text(
                    "Estimated cost: $${String.format("%.4f", costEstimate.estimatedCost)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Free (${costEstimate.model})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onSync,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync All")
            }
        }
    }
}

@Composable
fun UsageStatsCard(stats: UsageStats) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Usage", style = MaterialTheme.typography.titleMedium)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("This month:")
                Text("$${String.format("%.2f", stats.monthlyCost)} (${stats.monthlySyncs} syncs)")
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All time:")
                Text("$${String.format("%.2f", stats.totalCost)}")
            }
        }
    }
}
```

---

## Feature 6: Offline Queue & Sync Status

### Queue Data Model

```kotlin
enum class SyncStatus {
    PENDING,        // Waiting in queue
    QUEUED,         // No network, will retry
    SYNCING,        // Currently processing
    SUCCESS,        // Completed
    FAILED          // Failed after retries
}

data class QueuedSync(
    val sessionId: String,
    val destinations: List<String>,
    val status: SyncStatus,
    val attempts: Int,
    val lastError: SyncError?,
    val queuedAt: Long,
    val completedAt: Long?
)
```

### Sync Manager

```kotlin
class SyncManager(
    private val destinationRepository: DestinationRepository,
    private val transcriptionService: TranscriptionService,
    private val connectivityManager: ConnectivityManager,
    private val dataStore: DataStore<Preferences>
) {
    private val _syncQueue = MutableStateFlow<List<QueuedSync>>(emptyList())
    val syncQueue: StateFlow<List<QueuedSync>> = _syncQueue
    
    private val maxRetries = 3
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Network back, process queue
            processQueue()
        }
    }
    
    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }
    
    suspend fun queueSync(
        session: Session,
        destinations: List<String>,
        contexts: List<CapturedContext>
    ) {
        val queueItem = QueuedSync(
            sessionId = session.id,
            destinations = destinations,
            status = SyncStatus.PENDING,
            attempts = 0,
            lastError = null,
            queuedAt = System.currentTimeMillis(),
            completedAt = null
        )
        
        _syncQueue.update { it + queueItem }
        
        processQueueItem(queueItem, session, contexts)
    }
    
    private suspend fun processQueueItem(
        item: QueuedSync,
        session: Session,
        contexts: List<CapturedContext>
    ) {
        // Check network
        if (!isNetworkAvailable()) {
            updateStatus(item.sessionId, SyncStatus.QUEUED)
            return
        }
        
        updateStatus(item.sessionId, SyncStatus.SYNCING)
        
        try {
            // Transcribe
            val result = transcriptionService.processTranscription(
                chunks = session.chunks.map { it.toChunkData() },
                contexts = contexts,
                settings = getTranscriptionSettings()
            )
            
            // Send to each destination
            val destinationResults = item.destinations.map { destId ->
                val destination = destinationRepository.getDestination(destId)!!
                val syncContent = buildSyncContent(result)
                destId to destination.send(syncContent)
            }
            
            // Check results
            val failures = destinationResults.filter { it.second is SyncResult.Failure }
            
            if (failures.isEmpty()) {
                updateStatus(item.sessionId, SyncStatus.SUCCESS)
            } else if (failures.size < destinationResults.size) {
                // Partial success
                updateStatus(
                    item.sessionId, 
                    SyncStatus.SUCCESS,
                    partialFailures = failures.map { it.first }
                )
            } else {
                handleFailure(item, (failures.first().second as SyncResult.Failure).error)
            }
            
        } catch (e: Exception) {
            handleFailure(item, SyncError.UNKNOWN)
        }
    }
    
    private suspend fun handleFailure(item: QueuedSync, error: SyncError) {
        val newAttempts = item.attempts + 1
        
        if (error == SyncError.NETWORK) {
            // Network error - queue for retry when online
            updateStatus(item.sessionId, SyncStatus.QUEUED, error = error)
        } else if (newAttempts >= maxRetries) {
            // Max retries reached
            updateStatus(item.sessionId, SyncStatus.FAILED, error = error)
        } else {
            // Retry with backoff
            delay(1000L * newAttempts * newAttempts)  // Exponential backoff
            val updatedItem = item.copy(attempts = newAttempts, lastError = error)
            // Retry logic...
        }
    }
    
    private fun processQueue() {
        viewModelScope.launch {
            _syncQueue.value
                .filter { it.status == SyncStatus.QUEUED }
                .forEach { item ->
                    // Re-process queued items
                }
        }
    }
    
    suspend fun retryFailed(sessionId: String) {
        val item = _syncQueue.value.find { it.sessionId == sessionId } ?: return
        updateStatus(sessionId, SyncStatus.PENDING)
        // Re-process...
    }
    
    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    private fun updateStatus(
        sessionId: String, 
        status: SyncStatus,
        error: SyncError? = null,
        partialFailures: List<String>? = null
    ) {
        _syncQueue.update { queue ->
            queue.map { item ->
                if (item.sessionId == sessionId) {
                    item.copy(
                        status = status,
                        lastError = error,
                        completedAt = if (status == SyncStatus.SUCCESS) System.currentTimeMillis() else null
                    )
                } else item
            }
        }
    }
}
```

### Queue UI

```kotlin
@Composable
fun SessionListItem(
    session: Session,
    syncStatus: QueuedSync?,
    onRetry: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnails
            ChunkThumbnailRow(
                chunks = session.chunks,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Status indicator
            when (syncStatus?.status) {
                SyncStatus.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                SyncStatus.QUEUED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = "Queued",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Queued",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                SyncStatus.SYNCING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Syncing...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                SyncStatus.SUCCESS -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Synced",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                SyncStatus.FAILED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
                null -> {
                    // Not synced yet
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = "Ready to sync",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
```

---

## Feature 7: Updated Onboarding

### Onboarding Screens

```kotlin
sealed class OnboardingStep {
    object Welcome : OnboardingStep()
    object OverlayPermission : OnboardingStep()
    object AccessibilityPermission : OnboardingStep()
    object DestinationSetup : OnboardingStep()
    object ApiKeySetup : OnboardingStep()
    object Complete : OnboardingStep()
}

@Composable
fun OnboardingFlow(
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf<OnboardingStep>(OnboardingStep.Welcome) }
    
    when (currentStep) {
        OnboardingStep.Welcome -> WelcomeScreen(
            onNext = { currentStep = OnboardingStep.OverlayPermission }
        )
        OnboardingStep.OverlayPermission -> OverlayPermissionScreen(
            onGranted = { currentStep = OnboardingStep.AccessibilityPermission },
            onSkip = { currentStep = OnboardingStep.AccessibilityPermission }
        )
        OnboardingStep.AccessibilityPermission -> AccessibilityPermissionScreen(
            onEnabled = { currentStep = OnboardingStep.DestinationSetup },
            onSkip = { currentStep = OnboardingStep.DestinationSetup }
        )
        OnboardingStep.DestinationSetup -> DestinationSetupScreen(
            onComplete = { currentStep = OnboardingStep.ApiKeySetup },
            onSkip = { currentStep = OnboardingStep.ApiKeySetup }
        )
        OnboardingStep.ApiKeySetup -> ApiKeySetupScreen(
            onComplete = { currentStep = OnboardingStep.Complete },
            onSkip = { currentStep = OnboardingStep.Complete }
        )
        OnboardingStep.Complete -> CompleteScreen(
            onFinish = onComplete
        )
    }
}

@Composable
fun AccessibilityPermissionScreen(
    onEnabled: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Visibility,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "See what you're reading",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Synapse can capture text from your screen to give your notes context.\n\n" +
            "We never store or send anything you don't explicitly capture.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(onClick = onSkip) {
            Text("Maybe Later")
        }
    }
    
    // Check if enabled when returning
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (SynapseAccessibilityService.isEnabled(context)) {
                    onEnabled()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
```

---

## Feature 8: Context Management in Review

### Context Display & Editing

```kotlin
@Composable
fun SessionDetailScreen(
    session: Session,
    contexts: List<CapturedContext>,
    onDeleteContext: (String) -> Unit,
    onSync: () -> Unit
) {
    Column {
        // Context section
        if (contexts.isNotEmpty()) {
            Text(
                "Context",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            contexts.forEach { context ->
                ContextCard(
                    context = context,
                    onDelete = { onDeleteContext(context.id) }
                )
            }
        }
        
        // Chunks section
        Text(
            "Captures",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        // ... chunk display
    }
}

@Composable
fun ContextCard(
    context: CapturedContext,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (context) {
                    is CapturedContext.SelectedText -> Icons.Default.FormatQuote
                    is CapturedContext.RegionText -> Icons.Default.CropFree
                    is CapturedContext.RegionImage -> Icons.Default.Image
                    is CapturedContext.AutoContext -> Icons.Default.Language
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                when (context) {
                    is CapturedContext.AutoContext -> {
                        Text(
                            context.sourceApp.toAppName(),
                            style = MaterialTheme.typography.labelMedium
                        )
                        context.sourceUrl?.let { url ->
                            Text(
                                url.toDisplayUrl(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    is CapturedContext.SelectedText -> {
                        context.sourceApp?.let { app ->
                            Text(
                                app.toAppName() + (context.sourceUrl?.let { " • ${it.toDisplayUrl()}" } ?: ""),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Text(
                            context.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is CapturedContext.RegionText -> {
                        Text(
                            "Selected region",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            context.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is CapturedContext.RegionImage -> {
                        Text(
                            "Image region",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            context.description ?: "Will be described by AI",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
```

---

## Feature 9: Multi-Provider LLM

### Provider Configuration

```kotlin
data class LlmConfig(
    val transcriptionProvider: String,
    val transcriptionApiKey: String,
    val answeringProvider: String?,  // null = same as transcription
    val answeringApiKey: String?
)

class LlmProviderFactory(
    private val config: LlmConfig
) {
    fun getTranscriptionProvider(): LlmProvider {
        return createProvider(config.transcriptionProvider, config.transcriptionApiKey)
    }
    
    fun getAnsweringProvider(): LlmProvider {
        return if (config.answeringProvider != null && config.answeringApiKey != null) {
            createProvider(config.answeringProvider, config.answeringApiKey)
        } else {
            getTranscriptionProvider()
        }
    }
    
    private fun createProvider(provider: String, apiKey: String): LlmProvider {
        return when (provider) {
            "gemini-2.5-flash" -> GeminiProvider(apiKey, "gemini-2.5-flash")
            "gemini-1.5-pro" -> GeminiProvider(apiKey, "gemini-1.5-pro")
            "claude-3-haiku" -> ClaudeProvider(apiKey, "claude-3-haiku-20240307")
            "claude-3-sonnet" -> ClaudeProvider(apiKey, "claude-3-sonnet-20240229")
            "gpt-4o-mini" -> OpenAiProvider(apiKey, "gpt-4o-mini")
            "gpt-4o" -> OpenAiProvider(apiKey, "gpt-4o")
            "ollama" -> OllamaProvider()
            else -> throw IllegalArgumentException("Unknown provider: $provider")
        }
    }
}
```

### Settings UI

```kotlin
@Composable
fun LlmSettingsSection(
    config: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit
) {
    Column {
        Text(
            "TRANSCRIPTION",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        ProviderDropdown(
            selected = config.transcriptionProvider,
            onSelect = { provider ->
                onConfigChange(config.copy(transcriptionProvider = provider))
            }
        )
        
        OutlinedTextField(
            value = config.transcriptionApiKey,
            onValueChange = { key ->
                onConfigChange(config.copy(transcriptionApiKey = key))
            },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "ANSWERING (optional)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            "Use a different model for answering questions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        ProviderDropdown(
            selected = config.answeringProvider ?: "Same as above",
            includeNone = true,
            onSelect = { provider ->
                onConfigChange(config.copy(
                    answeringProvider = if (provider == "Same as above") null else provider
                ))
            }
        )
        
        if (config.answeringProvider != null) {
            OutlinedTextField(
                value = config.answeringApiKey ?: "",
                onValueChange = { key ->
                    onConfigChange(config.copy(answeringApiKey = key))
                },
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
```

---

## Output Format Changes

### Updated Output with Context

```kotlin
fun formatOutput(
    notes: List<TranscribedNote>,
    contexts: List<CapturedContext>,
    includeContextInOutput: Boolean = true
): String {
    val builder = StringBuilder()
    
    notes.forEach { note ->
        // Add context block if present and enabled
        if (includeContextInOutput && note.contextsUsed.isNotEmpty()) {
            val usedContexts = contexts.filter { it.id in note.contextsUsed }
            
            usedContexts.forEach { context ->
                when (context) {
                    is CapturedContext.AutoContext -> {
                        context.sourceUrl?.let { url ->
                            builder.appendLine("> Source: $url")
                        }
                    }
                    is CapturedContext.SelectedText -> {
                        context.sourceUrl?.let { url ->
                            builder.appendLine("> Source: $url")
                        }
                        builder.appendLine("> \"${context.text}\"")
                    }
                    is CapturedContext.RegionText -> {
                        builder.appendLine("> \"${context.text}\"")
                    }
                    is CapturedContext.RegionImage -> {
                        builder.appendLine("> [Image: ${context.description}]")
                    }
                }
            }
            builder.appendLine()
        }
        
        // Add note content
        builder.appendLine(note.text)
        builder.appendLine()
    }
    
    return builder.toString().trimEnd()
}
```

### Example Outputs

**Simple note:**
```markdown
Coffee processing has three main types: washed, natural, and honey.
```

**Note with context:**
```markdown
> Source: nytimes.com/article/economy-q2
> "The economy grew by 3% in Q2, exceeding analyst expectations."

This seems overstated. The Fed data from last month showed different trends. Need to check the original BLS report.
```

**Task:**
```markdown
- [ ] Email Jana about Q3 budget by Friday
```

**Question with answer:**
```markdown
## What is the capital of Estonia?

**Answer:** Tallinn
```

---

## Updated Settings Structure

```kotlin
data class SynapseSettings(
    // Capture
    val chunkTimeoutSeconds: Int = 3,
    val fadeAnimationSeconds: Float = 0.3f,
    val sessionAutoEndMinutes: Int = 15,
    
    // Review
    val defaultViewMode: ViewMode = ViewMode.STITCHED,
    
    // LLM
    val transcriptionProvider: String = "gemini-2.5-flash",
    val transcriptionApiKey: String = "",
    val answeringProvider: String? = null,
    val answeringApiKey: String? = null,
    val cleanupMode: Boolean = true,
    val advancedFormatting: Boolean = true,
    val rateLimitMode: RateLimitMode = RateLimitMode.SAFE,
    val promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,
    
    // Destinations
    val mainDestination: String = "clipboard",
    val configuredDestinations: List<DestinationConfig> = emptyList(),
    
    // Context
    val autoContextEnabled: Boolean = true,
    val includeContextInOutput: Boolean = true
)
```

---

## Implementation Priority

### Phase 1: Core Expansion (Week 1-2)
1. Destination abstraction interface
2. Clipboard destination
3. Share intent destination
4. Per-session destination selection UI

### Phase 2: Context Capture (Week 2-3)
5. Accessibility service implementation
6. Auto-context capture (app, URL)
7. Selected text capture
8. Context display in overlay
9. Context management in review

### Phase 3: Region Capture (Week 3-4)
10. Hold-drag gesture detection
11. Region text extraction
12. MediaProjection fallback for images
13. Multiple region selection

### Phase 4: Intent Detection (Week 4-5)
14. Updated LLM prompt with intent detection
15. Intent confirmation UI
16. Question answering flow
17. Reminder/alarm creation

### Phase 5: Polish (Week 5-6)
18. Multi-provider LLM settings
19. Cost estimation and tracking
20. Offline queue improvements
21. Updated onboarding flow
22. Notion integration (stretch)
23. Google Docs integration (stretch)

---

## Summary

This specification adds six major capabilities to Synapse:

1. **Multi-destination sync** - Beyond Obsidian to any app
2. **Context capture** - Know what user was reading
3. **Region capture** - Grab specific screen content
4. **Intent detection** - Understand what user wants done
5. **Smart LLM routing** - Right model for each task
6. **Cost transparency** - Track API spending

Design principles maintained:
- Every permission skippable
- Free tier friendly
- Privacy by default (context deletable)
- Minimal friction (LLM handles complexity)
