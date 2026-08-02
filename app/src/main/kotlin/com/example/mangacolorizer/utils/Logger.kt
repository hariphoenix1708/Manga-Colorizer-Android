package com.example.mangacolorizer.utils

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val TAG = "MangaColorizer"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: android.content.Context) {
        try {
            // Attempt to use the public /sdcard/MangaColorizer folder
            val root = File("/sdcard/MangaColorizer")
            if (!root.exists()) root.mkdirs()
            
            logFile = File(root, "session_logs.txt")
            if (logFile?.exists() == true) {
                // Keep the previous log but limit size or just clear for new session
                logFile?.delete()
            }
            logFile?.createNewFile()
            i("Logger initialized at: ${logFile?.absolutePath}")
            i("Device: ${android.os.Build.MODEL}, Android: ${android.os.Build.VERSION.RELEASE}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init logger on /sdcard, falling back to private storage", e)
            // Fallback to internal if sdcard fails (e.g. no permission yet)
            val folder = context.getExternalFilesDir("logs")
            if (folder != null) {
                logFile = File(folder, "session_logs.txt")
                logFile?.createNewFile()
            }
        }
    }

    fun i(message: String) {
        Log.i(TAG, message)
        writeToFile("INFO", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        writeToFile("ERROR", "$message ${throwable?.stackTraceToString() ?: ""}")
    }

    fun w(message: String) {
        Log.w(TAG, message)
        writeToFile("WARN", message)
    }

    fun d(message: String) {
        Log.d(TAG, message)
        writeToFile("DEBUG", message)
    }

    private fun writeToFile(level: String, message: String) {
        val target = logFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = "$timestamp [$level] $message\n"
            synchronized(this) {
                FileOutputStream(target, true).use { 
                    it.write(logEntry.toByteArray())
                    it.flush()
                }
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }
    
    fun clearLogs() {
        logFile?.let { if (it.exists()) it.delete(); it.createNewFile() }
    }
}
