package com.example.mangacolorizer.data

data class ProcessingState(
    val processState: ProcessState = ProcessState.IDLE,
    val queueSize: Int = 0,
    val pendingCount: Int = 0,
    val completedCount: Int = 0,
    val totalInSession: Int = 0,
    val currentItemSrc: String? = null,
    val currentStatusText: String = "Idle",
    val sessionToken: String = ""
)
