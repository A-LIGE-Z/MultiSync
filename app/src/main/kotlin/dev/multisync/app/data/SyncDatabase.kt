package dev.multisync.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleEntity(@PrimaryKey val id: String, val kind: String, val payload: String)

@Entity(tableName = "logs")
data class TaskLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val ruleName: String,
    val stage: String,
    val message: String = "",
    val level: String = "info",
)

@Entity(tableName = "transfers")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val direction: String, // "upload" or "download"
    val name: String,
    val packageName: String = "",
    val versionName: String = "",
    val size: Long = 0,
    val status: String = "success", // success / failed
    val detail: String = "",
)

@Entity(tableName = "observations")
data class Observation(@PrimaryKey val key: String, val size: Long, val modified: Long, val stableSince: Long)

@Dao
interface SyncDao {
    @Query("SELECT * FROM rules ORDER BY rowid") fun observeRules(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules ORDER BY rowid") suspend fun rules(): List<RuleEntity>
    @Upsert suspend fun putRule(rule: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id") suspend fun deleteRule(id: String)
    @Query("DELETE FROM rules") suspend fun clearRules()
    @Query("DELETE FROM observations") suspend fun clearObservations()
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 200") fun observeLogs(): Flow<List<TaskLog>>
    @Query("SELECT * FROM logs ORDER BY id ASC") suspend fun allLogs(): List<TaskLog>
    @Insert suspend fun addLog(log: TaskLog)
    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT 500)") suspend fun trimLogs()
    @Insert suspend fun addTransfer(record: TransferRecord)
    @Query("SELECT * FROM transfers ORDER BY id DESC LIMIT 300") fun observeTransfers(): Flow<List<TransferRecord>>
    @Query("DELETE FROM transfers") suspend fun clearTransfers()
    @Query("SELECT * FROM observations WHERE `key` = :key") suspend fun observation(key: String): Observation?
    @Upsert suspend fun putObservation(observation: Observation)
    @Query("DELETE FROM observations WHERE `key` = :key") suspend fun deleteObservation(key: String)
}

@Database(entities = [RuleEntity::class, TaskLog::class, TransferRecord::class, Observation::class], version = 2, exportSchema = true)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `direction` TEXT NOT NULL, `name` TEXT NOT NULL, `packageName` TEXT NOT NULL, `versionName` TEXT NOT NULL, `size` INTEGER NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL)")
            }
        }
    }
}
