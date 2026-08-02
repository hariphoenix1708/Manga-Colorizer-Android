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

    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableSet<(String) -> Unit>>()
    
    private val isLoopRunning = AtomicBoolean(false)
    private var processingJob: Job? = null

    private var completedCount = 0
    private var totalInSession = 0

    init {
        Logger.i("ColorizationManager: Initializing architecture v10.0")
        managerScope.launch {
            // 1. Load persistent app state
            val state = dao.getAppStateSync() ?: AppState()

            // Clean up any stale processing items and reset stuck ones
            dao.clearFinishedItems()
            dao.resetStuckItems()

            // If we restore as RUNNING but loop isn't active, change to PAUSED
            val restoredState = if (state.processState == ProcessState.RUNNING) ProcessState.PAUSED else state.processState
            updateState { it.copy(processState = restoredState, currentStatusText = restoredState.name) }

            Logger.i("ColorizationManager: Restored state (processState=$restoredState)")

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
                    
                    if (_processingState.value.processState == ProcessState.RUNNING && pendingCount > 0 && !isLoopRunning.get()) {
                        triggerProcessing()
                    }
                }
        }
    }

    private fun updateState(transform: (ProcessingState) -> ProcessingState) {
        _processingState.update { transform(it) }
    }

    fun startProcessing() {
        Logger.i("ColorizationManager: Explicit START requested")
        if (_processingState.value.processState == ProcessState.RUNNING) return
        
        changeState(ProcessState.RUNNING)
        triggerProcessing()
    }

    fun pauseProcessing() {
        Logger.i("ColorizationManager: Explicit PAUSE requested")
        if (_processingState.value.processState != ProcessState.RUNNING) return

        changeState(ProcessState.PAUSED)
        cancelActiveJob("User paused")
    }

    fun resumeProcessing() {
        Logger.i("ColorizationManager: Explicit RESUME requested")
        if (_processingState.value.processState == ProcessState.RUNNING) return

        changeState(ProcessState.RUNNING)
        triggerProcessing()
    }

    fun stopProcessing() {
        Logger.i("ColorizationManager: Explicit STOP requested")
        changeState(ProcessState.STOPPING)
        cancelActiveJob("User stopped")
        
        managerScope.launch {
            dao.resetStuckItems()
            changeState(ProcessState.IDLE)
        }
    }

    private fun cancelActiveJob(reason: String) {
        processingJob?.cancel(CancellationException(reason))
        isLoopRunning.set(false)
    }

    private fun changeState(newState: ProcessState) {
        updateState { it.copy(processState = newState, currentStatusText = newState.name) }
        managerScope.launch {
            val appState = dao.getAppStateSync() ?: AppState()
            dao.updateAppState(appState.copy(processState = newState))
        }
    }

    private fun triggerProcessing() {
        if (isLoopRunning.getAndSet(true)) {
            Logger.d("ColorizationManager: triggerProcessing skipped, loop already running")
            return
        }
        
        Logger.i("ColorizationManager: Launching new processing loop coroutine")
        processingJob = managerScope.launch {
            var currentItem: QueueItem? = null
            try {
                while (isActive && _processingState.value.processState == ProcessState.RUNNING) {
                    currentItem = dao.getNextPendingItem()
                    if (currentItem == null) {
                        Logger.i("ColorizationManager: Loop finished - Queue empty")
                        changeState(ProcessState.COMPLETED)
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
                    } else {
                        Logger.w("ColorizationManager: Loop interrupted or item failed. Active=$isActive, State=${_processingState.value.processState}")
                        if (_processingState.value.processState == ProcessState.STOPPING || _processingState.value.processState == ProcessState.PAUSED || !isActive) {
                             dao.updateItem(currentItem.copy(status = ItemStatus.PENDING))
                             break
                        } else {
                            dao.updateItem(currentItem.copy(status = ItemStatus.FAILED, errorMessage = "Failed to colorize"))
                        }
                    }
                    currentItem = null
                }
            } catch (e: CancellationException) {
                Logger.i("ColorizationManager: Task cancelled (Coroutine). Reason: ${e.message}")
                currentItem?.let { dao.updateItem(it.copy(status = ItemStatus.PENDING)) }
            } catch (e: Exception) {
                Logger.e("ColorizationManager: Fatal exception in processing loop", e)
                currentItem?.let { dao.updateItem(it.copy(status = ItemStatus.FAILED, errorMessage = "Fatal error: ${e.message}")) }
            } finally {
                isLoopRunning.set(false)
                val finalState = _processingState.value.processState
                updateState { it.copy(currentStatusText = finalState.name, currentItemSrc = null) }
                Logger.i("ColorizationManager: Loop exited. Final State=$finalState")
            }
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

            // Verify if it is already in the queue to avoid duplication if JS bridge sends multiple
            val existing = dao.getItem(src)
            if (existing != null && (existing.status == ItemStatus.COMPLETED || existing.status == ItemStatus.PROCESSING)) {
                Logger.d("ColorizationManager: Image already processing or completed, skipping add: $src")
                val callbacks = pendingCallbacks.getOrPut(src) { mutableSetOf() }
                callbacks.add(onComplete)
                return@launch
            }

            Logger.d("ColorizationManager: Queuing new image: $src")
            val callbacks = pendingCallbacks.getOrPut(src) { mutableSetOf() }
            callbacks.add(onComplete)
            
            dao.insertItem(QueueItem(src, referer))
            
            totalInSession++
            updateState { it.copy(totalInSession = totalInSession) }
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
        } catch (e: Exception) {
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
