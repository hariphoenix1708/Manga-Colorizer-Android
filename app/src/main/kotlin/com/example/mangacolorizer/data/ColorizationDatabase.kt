package com.example.mangacolorizer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class ItemStatus {
    PENDING, PROCESSING, COMPLETED, FAILED, SKIPPED
}

@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey val src: String,
    val referer: String,
    val addedAt: Long = System.currentTimeMillis(),
    val status: ItemStatus = ItemStatus.PENDING,
    val errorMessage: String? = null
)

enum class ProcessState {
    IDLE, RUNNING, PAUSED, COMPLETED
}

@Entity(tableName = "app_state")
data class AppState(
    @PrimaryKey val id: Int = 1,
    val processState: ProcessState = ProcessState.IDLE,
    val currentUrl: String = "https://www.google.com"
)

@Dao
interface ColorizationDao {
    @Query("SELECT * FROM queue_items ORDER BY addedAt ASC")
    fun getQueueItemsFlow(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_items WHERE status = 'PENDING' ORDER BY addedAt ASC LIMIT 1")
    suspend fun getNextPendingItem(): QueueItem?

    @Query("SELECT * FROM queue_items WHERE src = :src")
    suspend fun getItem(src: String): QueueItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: QueueItem)

    @Update
    suspend fun updateItem(item: QueueItem)

    @Delete
    suspend fun deleteItem(item: QueueItem)

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    @Query("DELETE FROM queue_items WHERE status IN ('COMPLETED', 'FAILED', 'SKIPPED')")
    suspend fun clearFinishedItems()

    @Query("UPDATE queue_items SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun resetStuckItems()

    @Query("SELECT * FROM app_state WHERE id = 1")
    fun getAppStateFlow(): Flow<AppState?>

    @Query("SELECT * FROM app_state WHERE id = 1")
    suspend fun getAppStateSync(): AppState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateAppState(state: AppState)
}

@Database(entities = [QueueItem::class, AppState::class], version = 3, exportSchema = false)
abstract class ColorizationDatabase : RoomDatabase() {
    abstract fun dao(): ColorizationDao
}
