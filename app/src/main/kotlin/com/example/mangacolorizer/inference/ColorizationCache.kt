package com.example.mangacolorizer.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ColorizationCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "colorized_pages").apply {
        if (!exists()) mkdirs()
    }

    private fun getCacheKey(uri: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(uri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(uri: String): Bitmap? {
        val key = getCacheKey(uri)
        val file = File(cacheDir, key)
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }

    fun put(uri: String, bitmap: Bitmap) {
        val key = getCacheKey(uri)
        val file = File(cacheDir, key)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
        }
    }
}
