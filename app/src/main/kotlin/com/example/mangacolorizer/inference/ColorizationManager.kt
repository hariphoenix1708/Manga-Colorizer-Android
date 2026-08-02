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

    private val _isPaused = MutableStateFlow(true)
    val isPaused = _isPaused.asStateFlow()

    private val _isColorizing = MutableStateFlow(false)
    val isColorizing = _isColorizing.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize = _queueSize.asStateFlow()

    private val _currentStatus = MutableStateFlow("Idle")
    val currentStatus = _currentStatus.asStateFlow()

    private val _processedCount = MutableStateFlow(0)
    val processedCount = _processedCount.asStateFlow()

    private val _totalInQueue = MutableStateFlow(0)
    val totalInQueue = _totalInQueue.asStateFlow()

    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableSet<(String) -> Unit>>()
    
    private val isLoopRunning = AtomicBoolean(false)
    private var processingJob: Job? = null

    init {
        Logger.i("ColorizationManager: Initializing architecture v9.0")
        managerScope.launch {
            // 1. Load persistent app state
            val state = dao.getAppStateSync() ?: AppState()
            _isPaused.value = state.isPaused
            _processedCount.value = state.processedCount
            _totalInQueue.value = state.totalInQueue
            Logger.i("ColorizationManager: Restored state (isPaused=${state.isPaused}, processed=${state.processedCount}, total=${state.totalInQueue})")

            // 2. Observe queue changes
            dao.getQueueItems()
                .distinctUntilChanged()
                .collect { items ->
                    _queueSize.value = items.size
                    Logger.d("ColorizationManager: Queue changed, size=${items.size}")
                    
                    if (!_isPaused.value && items.isNotEmpty()) {
                        triggerProcessing()
                    }
                }
        }
    }

    fun togglePause() {
        val nextPausedState = !_isPaused.value
        Logger.i("ColorizationManager: User toggled pause to: $nextPausedState")
        
        _isPaused.value = nextPausedState
        
        managerScope.launch {
            val currentState = dao.getAppStateSync() ?: AppState()
            dao.updateAppState(currentState.copy(isPaused = nextPausedState))
            
            if (nextPausedState) {
                Logger.i("ColorizationManager: Cancelling processing job due to pause")
                processingJob?.cancel("User paused")
                isLoopRunning.set(false)
                _isColorizing.value = false
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
            _isColorizing.value = true
            try {
                while (isActive && !_isPaused.value) {
                    val item = dao.getNextItem()
                    if (item == null) {
                        Logger.i("ColorizationManager: Loop finished - Queue empty")
                        _totalInQueue.value = 0
                        _processedCount.value = 0
                        updatePersistentAppState()
                        break
                    }

                    val src = item.src
                    Logger.i("ColorizationManager: Starting next item: $src")
                    _currentStatus.value = "Colorizing: ${getFileName(src)}"

                    val resultBytes = colorizeImageUrlToBytes(src, item.referer)

                    if (resultBytes != null && isActive && !_isPaused.value) {
                        memoryCache[src] = resultBytes
                        Logger.i("ColorizationManager: Successfully colorized and cached: $src")
                        
                        // Notify UI
                        val callbacks = pendingCallbacks.remove(src)
                        withContext(Dispatchers.Main) {
                            callbacks?.forEach { it(src) }
                        }
                        
                        // Commit progress
                        dao.deleteItem(item)
                        _processedCount.value++
                    } else if (!isActive || _isPaused.value) {
                        Logger.i("ColorizationManager: Loop interrupted while processing: $src")
                        break
                    } else {
                        Logger.w("ColorizationManager: Item failed or skipped: $src")
                        dao.deleteItem(item)
                        _totalInQueue.value = maxOf(0, _totalInQueue.value - 1)
                    }
                    updatePersistentAppState()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Logger.e("ColorizationManager: Fatal exception in processing loop", e)
                }
            } finally {
                _isColorizing.value = false
                isLoopRunning.set(false)
                _currentStatus.value = if (_isPaused.value) "Paused" else if (_queueSize.value == 0) "Completed" else "Idle"
                Logger.i("ColorizationManager: Loop exited. Status=${_currentStatus.value}")
            }
        }
    }

    private suspend fun updatePersistentAppState() {
        val state = AppState(
            isPaused = _isPaused.value,
            processedCount = _processedCount.value,
            totalInQueue = _totalInQueue.value
        )
        dao.updateAppState(state)
    }

    fun stopProcessing() {
        Logger.i("ColorizationManager: Explicit stop requested")
        _isPaused.value = true
        processingJob?.cancel("Stop requested")
        isLoopRunning.set(false)
        _isColorizing.value = false
        
        managerScope.launch {
            val currentState = dao.getAppStateSync() ?: AppState()
            dao.updateAppState(currentState.copy(isPaused = true))
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

    fun addImage(@Suppress("UNUSED_PARAMETER") id: String, src: String, referer: String, onComplete: (String) -> Unit) {
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
            
            // Recalculate total for UI precision
            val currentQueueSize = dao.getQueueItems().first().size
            _totalInQueue.value = _processedCount.value + currentQueueSize
            updatePersistentAppState()

            if (!_isPaused.value) {
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
            val colorized = colorizer.colorize(bitmap)
            
            Logger.d("ColorizationManager: AI colorization finished, saving to disk cache: $imageUrl")
            cache.put(imageUrl, colorized)
            
            bitmapToBytes(colorized)
        } catch (e: CancellationException) {
            Logger.i("ColorizationManager: Task cancelled (Coroutine) for: $imageUrl")
            throw e
        } catch (e: Exception) {
            Logger.e("ColorizationManager: Error during download/AI processing for $imageUrl", e)
            null
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
