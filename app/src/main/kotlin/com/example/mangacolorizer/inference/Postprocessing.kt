package com.example.mangacolorizer.inference

import android.graphics.Bitmap
import android.graphics.Color

object Postprocessing {

    /**
     * Converts the model output tensor to a Bitmap.
     * AlacGAN output: [1, 3, H, W], values typically [-1, 1] due to Tanh.
     * Denormalization: result * 0.5 + 0.5
     */
    fun tensorToBitmap(
        floatArray: FloatArray,
        width: Int,
        height: Int,
        padBottom: Int,
        padRight: Int
    ): Bitmap {
        val resultWidth = width - padRight
        val resultHeight = height - padBottom
        val bitmap = Bitmap.createBitmap(resultWidth, resultHeight, Bitmap.Config.ARGB_8888)

        // floatArray is in CHW format: [3, height, width]
        val channelSize = width * height
        
        for (y in 0 until resultHeight) {
            for (x in 0 until resultWidth) {
                val index = y * width + x
                
                // Denormalize from [-1, 1] to [0, 1]
                val r = (floatArray[index] * 0.5f + 0.5f).coerceIn(0f, 1f)
                val g = (floatArray[index + channelSize] * 0.5f + 0.5f).coerceIn(0f, 1f)
                val b = (floatArray[index + 2 * channelSize] * 0.5f + 0.5f).coerceIn(0f, 1f)

                bitmap.setPixel(x, y, Color.rgb(
                    (r * 255).toInt(),
                    (g * 255).toInt(),
                    (b * 255).toInt()
                ))
            }
        }

        return bitmap
    }
}
