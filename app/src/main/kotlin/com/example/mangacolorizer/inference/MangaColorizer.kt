package com.example.mangacolorizer.inference

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.mangacolorizer.utils.Logger
import java.io.File
import java.util.Collections
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers

class MangaColorizer(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    var isNpuSupported: Boolean = false
        private set

    private val _currentProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val currentProgress = _currentProgress.asStateFlow()
    
    private val _isBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val semaphore = Semaphore(1)

    fun loadModel(modelPath: String) {
        Logger.i("AI: Loading model $modelPath")
        try {
            val modelFile = File(context.filesDir, modelPath)
            val dataFile = File(context.filesDir, "$modelPath.data")

            if (!modelFile.exists()) {
                Logger.d("AI: Copying model assets to internal storage")
                copyAssetToFile(modelPath, modelFile)
                copyAssetToFile("$modelPath.data", dataFile)
            }

            val options = OrtSession.SessionOptions()
            
            try {
                Logger.d("AI: Attempting NPU acceleration (QNN/NNAPI)")
                try {
                    options.addConfigEntry("session.use_qnn", "1")
                    options.addConfigEntry("qnn.backend_path", "libQnnHtp.so")
                    Logger.i("AI: QNN HTP Backend enabled")
                } catch (e: Exception) {
                    Logger.w("AI: QNN HTP unavailable, trying NNAPI: ${e.message}")
                    options.addConfigEntry("session.use_nnapi", "1")
                }
                
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(4)
                isNpuSupported = true
            } catch (e: Exception) {
                isNpuSupported = false
                Logger.e("AI: NPU initialization failed, using CPU", e)
            }

            session = env.createSession(modelFile.absolutePath, options)
            Logger.i("AI: Session created (NPU=$isNpuSupported)")
        } catch (e: Exception) {
            Logger.e("AI: CRITICAL - Failed to load model", e)
            throw e
        }
    }

    private fun copyAssetToFile(assetName: String, outputFile: File) {
        context.assets.open(assetName).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun isAlreadyColored(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        var totalDiff = 0f
        val sampleSize = 100
        
        for (i in 0 until sampleSize) {
            val x = (Math.random() * width).toInt()
            val y = (Math.random() * height).toInt()
            val pixel = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            
            val gray = (r + g + b) / 3f
            totalDiff += Math.abs(r - gray) + Math.abs(g - gray) + Math.abs(b - gray)
        }
        
        val avgDiff = totalDiff / sampleSize
        return avgDiff > 30.0f
    }

    suspend fun colorize(bitmap: Bitmap): ColorizationResult = semaphore.withPermit {
        val ortSession = session ?: run {
            Logger.w("AI: colorize called before model loaded")
            return ColorizationResult.Error(IllegalStateException("Model not loaded"))
        }

        if (isAlreadyColored(bitmap)) {
            Logger.d("AI: Image already contains color, skipping")
            return ColorizationResult.Skipped(bitmap)
        }

        _isBusy.value = true
        _currentProgress.value = 0.05f
        
        try {
            val startTime = System.currentTimeMillis()
            Logger.d("AI: Starting inference for ${bitmap.width}x${bitmap.height}")
            
            val (paddedBitmap, pads) = Preprocessing.resizeAndPad(bitmap)
            _currentProgress.value = 0.1f
            
            val floatBuffer = Preprocessing.bitmapToFloatBuffer(paddedBitmap)
            _currentProgress.value = 0.2f
            
            val inputShape = longArrayOf(1, 5, paddedBitmap.height.toLong(), paddedBitmap.width.toLong())
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)
            val inputs = Collections.singletonMap("input", inputTensor)
            
            _currentProgress.value = 0.3f
            
            val resultBitmap = withTimeout(60000) {
                ortSession.run(inputs).use { results ->
                    Logger.d("AI: Core inference done in ${System.currentTimeMillis() - startTime}ms")
                    _currentProgress.value = 0.8f
                    
                    val outputTensor = results[0] as OnnxTensor
                    val floatArray = outputTensor.floatBuffer.array()
                    
                    Postprocessing.tensorToBitmap(
                        floatArray,
                        paddedBitmap.width,
                        paddedBitmap.height,
                        pads[0],
                        pads[1]
                    )
                }
            }
            
            Logger.i("AI: Colorization complete in ${System.currentTimeMillis() - startTime}ms")
            _currentProgress.value = 1.0f
            return ColorizationResult.Success(resultBitmap)
        } catch (e: Exception) {
            Logger.e("AI: Error during colorization", e)
            return ColorizationResult.Error(e)
        } finally {
            _isBusy.value = false
            _currentProgress.value = 0f
        }
    }

    fun close() {
        Logger.i("AI: Closing session and environment")
        session?.close()
        env.close()
    }
}
