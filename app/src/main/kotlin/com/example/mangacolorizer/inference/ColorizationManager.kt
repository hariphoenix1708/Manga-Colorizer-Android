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
import java.util.UUID
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

    private val _processingState = MutableStateFlow(ProcessingState(sessionToken = UUID.randomUUID().toString()))
    val processingState = _processingState.asStateFlow()

    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableSet<(String) -> Unit>>()
    
    private val isLoopRunning = AtomicBoolean(false)
    private var processingJob: Job? = null

    private var completedCount = 0
    private var totalInSession = 0

    @Volatile
    private var activeSessionToken: String = _processingState.value.sessionToken

    init {
        Logger.i("ColorizationManager: Initializing architecture v11.0 (Strict Start/Stop)")
        managerScope.launch {
            // 1. Load persistent app state
            val state = dao.getAppStateSync() ?: AppState()

            // Clean up any stale processing items and reset stuck ones
            dao.clearFinishedItems()
            dao.resetStuckItems()

            // On startup, we always default to IDLE for safety, ensuring the user has to explicitly start
            changeState(ProcessState.IDLE)

            Logger.i("ColorizationManager: Restored state forced to IDLE")

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
                }
        }
    }

    private fun updateState(transform: (ProcessingState) -> ProcessingState) {
        _processingState.update { transform(it) }
    }

    fun startProcessing() {
        if (_processingState.value.processState == ProcessState.RUNNING) return
        Logger.i("ColorizationManager: Explicit START requested")
        
        activeSessionToken = UUID.randomUUID().toString()
        completedCount = 0
        val currentPending = _processingState.value.pendingCount
        totalInSession = currentPending

        updateState { it.copy(sessionToken = activeSessionToken, completedCount = 0, totalInSession = totalInSession) }
        changeState(ProcessState.RUNNING)
        triggerProcessing()
    }

    fun stopProcessing() {
        Logger.i("ColorizationManager: Explicit STOP requested (Hard Stop)")
        val previousToken = activeSessionToken
        activeSessionToken = UUID.randomUUID().toString()

        updateState { it.copy(processState = ProcessState.IDLE, currentStatusText = "Idle", sessionToken = activeSessionToken, currentItemSrc = null) }

        processingJob?.cancel(CancellationException("Hard Stop requested by user"))
        isLoopRunning.set(false)
        pendingCallbacks.clear()
        
        managerScope.launch {
            dao.resetStuckItems()
            dao.updateAppState(AppState(processState = ProcessState.IDLE))
            Logger.i("ColorizationManager: Hard stop completed. Token invalidated from $previousToken to $activeSessionToken")
        }
    }

    private fun changeState(newState: ProcessState) {
        val oldState = _processingState.value.processState
        Logger.i("ColorizationManager: State changed from $oldState to $newState")
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
        
        Logger.i("ColorizationManager: Launching new processing loop coroutine for token $activeSessionToken")
        val currentToken = activeSessionToken

        processingJob = managerScope.launch {
            var currentItem: QueueItem? = null
            try {
                while (isActive && _processingState.value.processState == ProcessState.RUNNING) {
                    if (currentToken != activeSessionToken) {
                        Logger.i("ColorizationManager: Loop token mismatch. Exiting loop.")
                        break
                    }

                    currentItem = dao.getNextPendingItem()
                    if (currentItem == null) {
                        // Keep the session alive in a waiting state
                        updateState { it.copy(currentStatusText = "Waiting for images...", currentItemSrc = null) }
                        delay(500) // Poll for new items while running
                        continue
                    }

                    val src = currentItem.src
                    Logger.i("ColorizationManager: Starting next item: $src")
                    updateState { it.copy(currentItemSrc = src, currentStatusText = "Colorizing: ${getFileName(src)}") }

                    dao.updateItem(currentItem.copy(status = ItemStatus.PROCESSING))

                    val resultBytes = colorizeImageUrlToBytes(src, currentItem.referer, currentToken)

                    if (currentToken != activeSessionToken) {
                         Logger.w("ColorizationManager: Token changed during inference for $src. Discarding result and rollback.")
                         dao.updateItem(currentItem.copy(status = ItemStatus.PENDING))
                         break
                    }

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
                        if (currentToken != activeSessionToken || !isActive) {
                             Logger.i("ColorizationManager: Loop interrupted safely for $src. Rolling back.")
                             dao.updateItem(currentItem.copy(status = ItemStatus.PENDING))
                             break
                        } else {
                            Logger.w("ColorizationManager: Item failed or skipped: $src")
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
                if (currentToken == activeSessionToken && _processingState.value.processState == ProcessState.RUNNING) {
                    Logger.w("ColorizationManager: Processing loop exited unexpectedly while still RUNNING. This shouldn't happen unless task was cancelled.")
                } else {
                    Logger.i("ColorizationManager: Loop exited cleanly. Final State=${_processingState.value.processState}")
                }
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

    private suspend fun colorizeImageUrlToBytes(imageUrl: String, referer: String, sessionToken: String): ByteArray? = withContext(Dispatchers.IO) {
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
            
            if (sessionToken != activeSessionToken) {
                Logger.w("ColorizationManager: Token expired before AI inference for $imageUrl")
                return@withContext null
            }

            Logger.d("ColorizationManager: Handing off to AI engine: $imageUrl")
            val result = colorizer.colorize(bitmap)
            
            if (sessionToken != activeSessionToken) {
                Logger.w("ColorizationManager: Token expired after AI inference for $imageUrl. Discarding output.")
                return@withContext null
            }

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
