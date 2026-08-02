package com.example.mangacolorizer.inference

import android.graphics.Bitmap

sealed class ColorizationResult {
    data class Success(val bitmap: Bitmap) : ColorizationResult()
    data class Skipped(val bitmap: Bitmap) : ColorizationResult()
    data class Error(val exception: Exception) : ColorizationResult()
}
