package com.example.mangacolorizer.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebResourceResponse
import com.example.mangacolorizer.data.*
import com.example.mangacolorizer.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ColorizationManager @Inject constructor(
    private val colorizer: MangaColorizer,
    private val cache: ColorizationCache,
    db: ColorizationDatabase,
) {
    private val dao = db.dao()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _processingState = MutableStateFlow(ProcessingState())
    val processingState = _processingState.asStateFlow()

    // For backwards compatibility and easier access
    val isPaused = _processingState.map { it.processState == ProcessState.PAUSED }.stateIn(managerScope, SharingStarted.Eagerly, true)
    val isColorizing = _processingState.map { it.processState == ProcessState.RUNNING }.stateIn(managerScope, SharingStarted.Eagerly, false)
    val queueSize = _processingState.map { it.pendingCount }.stateIn(managerScope, SharingStarted.Eagerly, 0)
    val currentStatus = _processingState.map { it.currentStatusText }.stateIn(managerScope, SharingStarted.Eagerly, "Idle")

    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableSet<(String) -> Unit>>()
    
    private val isLoopRunning = AtomicBoolean(false)
    private var processingJob: Job? = null

    private var completedCount = 0
    private var totalInSession = 0

    init {
        Logger.i("ColorizationManager: Initializing architecture v9.0")
        managerScope.launch {
            // 1. Load persistent app state
            val state = dao.getAppStateSync() ?: AppState()

            // Clean up any stale processing items and reset stuck ones
            dao.clearFinishedItems()
            dao.resetStuckItems()

            updateState { it.copy(processState = state.processState) }

            Logger.i("ColorizationManager: Restored state (processState=${state.processState})")

            // 2. Observe queue changes
            dao.getQueueItemsFlow()
                .collect { items ->
                    val pendingCount = items.count { it.status == ItemStatus.PENDING }

                    updateState {
                        it.copy(
                            queueSize = items.size,
                            pendingCount = pendingCount
                        )
                    }
                    Logger.d("ColorizationManager: Queue changed, size=${items.size}, pending=$pendingCount")
                    
                    if ((_processingState.value.processState == ProcessState.RUNNING || _processingState.value.processState == ProcessState.COMPLETED) && pendingCount > 0) {
                        triggerProcessing()
                    }
                }
        }
    }

    private fun updateState(transform: (ProcessingState) -> ProcessingState) {
        _processingState.update { transform(it) }
    }

    fun togglePause() {
        val currentState = _processingState.value.processState
        val nextState = if (currentState == ProcessState.PAUSED || currentState == ProcessState.IDLE || currentState == ProcessState.COMPLETED) {
            ProcessState.RUNNING
        } else {
            ProcessState.PAUSED
        }

        Logger.i("ColorizationManager: User toggled state to: $nextState")
        
        updateState { it.copy(processState = nextState, currentStatusText = if (nextState == ProcessState.PAUSED) "Paused" else "Running") }
        
        managerScope.launch {
            val appState = dao.getAppStateSync() ?: AppState()
            dao.updateAppState(appState.copy(processState = nextState))
            
            if (nextState == ProcessState.PAUSED) {
                Logger.i("ColorizationManager: Cancelling processing job due to pause")
                processingJob?.cancel("User paused")
                isLoopRunning.set(false)
            } else {
                triggerProcessing()
            }
        }
    }

    private fun triggerProcessing() {
        if (isLoopRunning.getAndSet(true)) {
            Logger.d("ColorizationManager: triggerProcessing skipped, loop already running")
            return
        }
        
        Logger.i("ColorizationManager: Launching new processing loop coroutine")
        processingJob = managerScope.launch {
            updateState { it.copy(processState = ProcessState.RUNNING) }
            var currentItem: QueueItem? = null
            try {
                while (isActive && _processingState.value.processState == ProcessState.RUNNING) {
                    currentItem = dao.getNextPendingItem()
                    if (currentItem == null) {
                        Logger.i("ColorizationManager: Loop finished - Queue empty")
                        updateState {
                            it.copy(
                                processState = ProcessState.COMPLETED,
                                currentStatusText = "Completed",
                                currentItemSrc = null
                            )
                        }
                        updatePersistentAppState()
                        break
                    }

                    val src = currentItem.src
                    Logger.i("ColorizationManager: Starting next item: $src")
                    updateState { it.copy(currentItemSrc = src, currentStatusText = "Colorizing: ${getFileName(src)}") }

                    dao.updateItem(currentItem.copy(status = ItemStatus.PROCESSING))

                    val resultBytes = colorizeImageUrlToBytes(src, currentItem.referer)

                    if (resultBytes != null && isActive && _processingState.value.processState == ProcessState.RUNNING) {
                        memoryCache[src] = resultBytes
                        Logger.i("ColorizationManager: Successfully colorized and cached: $src")
                        
                        // Notify UI
                        val callbacks = pendingCallbacks.remove(src)
                        withContext(Dispatchers.Main) {
                            callbacks?.forEach { it(src) }
                        }
                        
                        // Commit progress
                        dao.updateItem(currentItem.copy(status = ItemStatus.COMPLETED))
                        completedCount++
                        updateState { it.copy(completedCount = completedCount) }
                    } else if (!isActive || _processingState.value.processState == ProcessState.PAUSED) {
                        Logger.i("ColorizationManager: Loop interrupted while processing: $src")
                        dao.updateItem(currentItem.copy(status = ItemStatus.PENDING)) // Reset back to pending
                        break
                    } else {
                        Logger.w("ColorizationManager: Item failed or skipped: $src")
                        dao.updateItem(currentItem.copy(status = ItemStatus.FAILED, errorMessage = "Failed to colorize"))
                    }
                    updatePersistentAppState()
                    currentItem = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                   Logger.i("ColorizationManager: Task cancelled (Coroutine). Rolling back current item.")
                   currentItem?.let { dao.updateItem(it.copy(status = ItemStatus.PENDING)) }
                } else {
                   Logger.e("ColorizationManager: Fatal exception in processing loop", e)
                   currentItem?.let { dao.updateItem(it.copy(status = ItemStatus.FAILED, errorMessage = "Fatal error: ${e.message}")) }
                }
            } finally {
                isLoopRunning.set(false)
                val finalState = _processingState.value
                val statusText = when (finalState.processState) {
                    ProcessState.PAUSED -> "Paused"
                    ProcessState.COMPLETED -> "Completed"
                    ProcessState.IDLE -> "Idle"
                    ProcessState.RUNNING -> "Idle" // Should not happen but fallback
                }
                updateState { it.copy(currentStatusText = statusText, currentItemSrc = null) }
                Logger.i("ColorizationManager: Loop exited. Status=${statusText}")
            }
        }
    }

    private suspend fun updatePersistentAppState() {
        val appState = dao.getAppStateSync() ?: AppState()
        dao.updateAppState(appState.copy(processState = _processingState.value.processState))
    }

    fun stopProcessing() {
        Logger.i("ColorizationManager: Explicit stop requested")
        updateState { it.copy(processState = ProcessState.IDLE, currentStatusText = "Idle") }
        processingJob?.cancel("Stop requested")
        isLoopRunning.set(false)
        
        managerScope.launch {
            val appState = dao.getAppStateSync() ?: AppState()
            dao.updateAppState(appState.copy(processState = ProcessState.IDLE))
        }
    }

    private fun getFileName(url: String): String {
        return try {
            val name = url.substringAfterLast("/").substringBefore("?")
            if (name.length > 20) name.take(17) + "..." else name
        } catch (e: Exception) {
            "image"
        }
    }

    fun addImage(id: String, src: String, referer: String, onComplete: (String) -> Unit) {
        managerScope.launch {
            // Check memory then disk cache
            val cached = memoryCache[src] ?: cache.get(src)?.let { 
                val bytes = bitmapToBytes(it)
                memoryCache[src] = bytes
                bytes
            }
            
            if (cached != null) {
                Logger.d("ColorizationManager: Cache hit (Restoring): $src")
                withContext(Dispatchers.Main) { onComplete(src) }
                return@launch
            }

            Logger.d("ColorizationManager: Queuing new image: $src")
            val callbacks = pendingCallbacks.getOrPut(src) { mutableSetOf() }
            callbacks.add(onComplete)
            
            dao.insertItem(QueueItem(src, referer))
            
            totalInSession++
            updateState { it.copy(totalInSession = totalInSession) }
            updatePersistentAppState()

            if (_processingState.value.processState == ProcessState.RUNNING || _processingState.value.processState == ProcessState.COMPLETED) {
                triggerProcessing()
            }
        }
    }

    private suspend fun colorizeImageUrlToBytes(imageUrl: String, referer: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Logger.d("ColorizationManager: Fetching from network: $imageUrl")
            val url = URL(imageUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Poco F6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Referer", referer)
            
            try { 
                val rUrl = URL(referer)
                connection.setRequestProperty("Origin", "${rUrl.protocol}://${rUrl.host}")
            } catch(e: Exception) { }
            
            val cookie = android.webkit.CookieManager.getInstance().getCookie(imageUrl)
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)

            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            if (connection.responseCode != 200) {
                Logger.w("ColorizationManager: Network fetch failed (HTTP ${connection.responseCode}) for $imageUrl")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeStream(connection.inputStream) ?: run {
                Logger.e("ColorizationManager: Failed to decode downloaded bitmap: $imageUrl")
                return@withContext null
            }
            
            Logger.d("ColorizationManager: Handing off to AI engine: $imageUrl")
            val result = colorizer.colorize(bitmap)
            
            when (result) {
                is ColorizationResult.Success -> {
                    Logger.d("ColorizationManager: AI colorization finished, saving to disk cache: $imageUrl")
                    cache.put(imageUrl, result.bitmap)
                    bitmapToBytes(result.bitmap)
                }
                is ColorizationResult.Skipped -> {
                    Logger.d("ColorizationManager: AI colorization skipped, image already colored: $imageUrl")
                    cache.put(imageUrl, result.bitmap)
                    bitmapToBytes(result.bitmap)
                }
                is ColorizationResult.Error -> {
                    Logger.e("ColorizationManager: AI colorization failed for $imageUrl", result.exception)
                    null
                }
            }
        } catch (e: Exception) { // Also catches CancellationException now, handled up in loop
            throw e
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP, 75, outputStream)
        return outputStream.toByteArray()
    }

    fun getInterceptedResponse(url: String): WebResourceResponse? {
        val uri = android.net.Uri.parse(url)
        val encodedSrc = uri.getQueryParameter("src")
        val originalUrl = if (encodedSrc != null) {
            try {
                String(Base64.decode(encodedSrc, Base64.URL_SAFE))
            } catch (e: Exception) {
                Logger.e("ColorizationManager: Interceptor failed to decode src: $encodedSrc")
                null
            }
        } else {
            null
        }
        
        if (originalUrl == null) return null
        
        val data = memoryCache[originalUrl] ?: cache.get(originalUrl)?.let { 
            Logger.d("ColorizationManager: Disk cache hit during interceptor for $originalUrl")
            val bytes = bitmapToBytes(it)
            memoryCache[originalUrl] = bytes
            bytes
        }
        
        if (data == null) {
            Logger.w("ColorizationManager: Interceptor could not find colorized data for $originalUrl")
            return null
        }
        
        return WebResourceResponse("image/webp", "UTF-8", ByteArrayInputStream(data))
    }
}
