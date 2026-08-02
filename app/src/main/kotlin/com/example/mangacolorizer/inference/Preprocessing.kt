package com.example.mangacolorizer.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.mangacolorizer.utils.Logger
import java.nio.FloatBuffer

object Preprocessing {

    const val MODEL_INPUT_SIZE = 576

    /**
     * Resizes and pads the bitmap to [MODEL_INPUT_SIZE] x [MODEL_INPUT_SIZE].
     * Returns a new Bitmap and the padding information [padBottom, padRight].
     */
    fun resizeAndPad(bitmap: Bitmap): Pair<Bitmap, IntArray> {
        // Downscale huge images to prevent OOM before we even start
        var source = bitmap
        if (bitmap.width > 2000 || bitmap.height > 2000) {
            val scale = 2000f / Math.max(bitmap.width, bitmap.height)
            source = Bitmap.createScaledBitmap(
                bitmap, 
                (bitmap.width * scale).toInt(), 
                (bitmap.height * scale).toInt(), 
                true
            )
            Logger.d("Downscaled huge image from ${bitmap.width}x${bitmap.height} to ${source.width}x${source.height}")
        }

        val width = source.width
        val height = source.height

        val ratio: Float
        val newWidth: Int
        val newHeight: Int

        if (height < width) {
            ratio = height.toFloat() / (MODEL_INPUT_SIZE * 1.5f)
            newWidth = Math.ceil((width / ratio).toDouble()).toInt()
            newHeight = (MODEL_INPUT_SIZE * 1.5f).toInt()
        } else {
            ratio = width.toFloat() / MODEL_INPUT_SIZE
            newWidth = MODEL_INPUT_SIZE
            newHeight = Math.ceil((height / ratio).toDouble()).toInt()
        }

        val resized = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)

        // Pad to multiple of 32
        val paddedWidth = if (newWidth % 32 == 0) newWidth else newWidth + (32 - newWidth % 32)
        val paddedHeight = if (newHeight % 32 == 0) newHeight else newHeight + (32 - newHeight % 32)

        val padRight = paddedWidth - newWidth
        val padBottom = paddedHeight - newHeight

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.WHITE) // Padding with maximum (white in grayscale)
        canvas.drawBitmap(resized, 0f, 0f, Paint())

        return Pair(paddedBitmap, intArrayOf(padBottom, padRight))
    }

    /**
     * Converts a Bitmap to a FloatBuffer for ONNX Runtime.
     * AlacGAN input: [1, 5, H, W]
     * Channel 0: Grayscale (normalized [0, 1])
     * Channels 1-4: Hints (zeros)
     */
    fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val capacity = 1 * 5 * width * height
        val buffer = FloatBuffer.allocate(capacity)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Channel 0: Grayscale
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            buffer.put(gray)
        }

        // Channels 1-4: Hints (zeros)
        // Note: ORT expects data in NCHW format. 
        // Channel 0 is done above. Now put zeros for the other 4 channels.
        for (c in 1..4) {
            for (i in 0 until (width * height)) {
                buffer.put(0.0f)
            }
        }

        buffer.rewind()
        return buffer
    }
}
