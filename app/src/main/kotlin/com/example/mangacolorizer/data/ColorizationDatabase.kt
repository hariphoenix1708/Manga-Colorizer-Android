package com.example.mangacolorizer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey val src: String,
    val referer: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_state")
data class AppState(
    @PrimaryKey val id: Int = 1,
    val isPaused: Boolean = true,
    val processedCount: Int = 0,
    val totalInQueue: Int = 0,
    val currentUrl: String = "https://www.google.com"
)

@Dao
interface ColorizationDao {
    @Query("SELECT * FROM queue_items ORDER BY addedAt ASC")
    fun getQueueItems(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_items ORDER BY addedAt ASC LIMIT 1")
    suspend fun getNextItem(): QueueItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: QueueItem)

    @Delete
    suspend fun deleteItem(item: QueueItem)

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    @Query("SELECT * FROM app_state WHERE id = 1")
    fun getAppState(): Flow<AppState?>

    @Query("SELECT * FROM app_state WHERE id = 1")
    suspend fun getAppStateSync(): AppState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateAppState(state: AppState)
}

@Database(entities = [QueueItem::class, AppState::class], version = 1, exportSchema = false)
abstract class ColorizationDatabase : RoomDatabase() {
    abstract fun dao(): ColorizationDao
}
