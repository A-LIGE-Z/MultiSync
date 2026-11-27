package dev.multisync.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
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
    @Query("SELECT * FROM observations WHERE `key` = :key") suspend fun observation(key: String): Observation?
    @Upsert suspend fun putObservation(observation: Observation)
    @Query("DELETE FROM observations WHERE `key` = :key") suspend fun deleteObservation(key: String)
}

@Database(entities = [RuleEntity::class, TaskLog::class, Observation::class], version = 1, exportSchema = true)
abstract class SyncDatabase : RoomDatabase() { abstract fun dao(): SyncDao }
