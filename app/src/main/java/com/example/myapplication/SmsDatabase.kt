package com.example.myapplication

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Defines the schema of the SMS log table.
 */
@Entity(tableName = "sms_logs")
data class SmsLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "phone_number") val phoneNumber: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "status") val status: String, // e.g., "SUCCESS", "FAILED: NO SERVICE"
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Data Access Object (DAO) for the SmsLog entity.
 */
@Dao
interface SmsLogDao {
    @Insert
    suspend fun insert(log: SmsLog)

    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<SmsLog>>
}

/**
 * The Room database for the application.
 */
@Database(entities = [SmsLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsLogDao(): SmsLogDao

    companion object {
        // Singleton prevents multiple instances of database opening at the same time.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_log_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}