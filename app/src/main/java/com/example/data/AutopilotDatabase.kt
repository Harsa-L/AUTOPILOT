package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "flight_sessions")
data class FlightSessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val protocolId: String,
  val protocolName: String,
  val hostIp: String,
  val startedAtMs: Long,
  val endedAtMs: Long?,
  val packetsSent: Long,
  val packetsReceived: Long,
  val droppedPackets: Long,
  val framesDecoded: Long,
  val isSyntheticHarness: Boolean,
  val terminationReason: String,
)

@Serializable
@Entity(tableName = "diagnostic_traces")
data class DiagnosticTraceEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val timestampMs: Long,
  val protocolId: String,
  val subsystem: String,
  val severity: String,
  val message: String,
  val packetHex: String?,
)

@Serializable
@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
  @PrimaryKey val id: String,
  val name: String,
  val appearance: String,
  val jsonPayload: String,
  val updatedAtMs: Long,
)

@Dao
interface FlightSessionDao {
  @Query("SELECT * FROM flight_sessions ORDER BY startedAtMs DESC LIMIT 50")
  fun observeSessions(): Flow<List<FlightSessionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: FlightSessionEntity): Long

  @Query(
    "UPDATE flight_sessions SET endedAtMs = :endedAtMs, packetsSent = :sent, packetsReceived = :received, droppedPackets = :dropped, framesDecoded = :frames, terminationReason = :reason WHERE id = :id"
  )
  suspend fun finishSession(
    id: Long,
    endedAtMs: Long,
    sent: Long,
    received: Long,
    dropped: Long,
    frames: Long,
    reason: String,
  )

  @Query("DELETE FROM flight_sessions")
  suspend fun clearSessions()
}

@Dao
interface DiagnosticTraceDao {
  @Query("SELECT * FROM diagnostic_traces ORDER BY timestampMs DESC LIMIT 120")
  fun observeTraces(): Flow<List<DiagnosticTraceEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTrace(trace: DiagnosticTraceEntity)

  @Query("DELETE FROM diagnostic_traces")
  suspend fun clearTraces()
}

@Dao
interface CustomThemeDao {
  @Query("SELECT * FROM custom_themes ORDER BY updatedAtMs DESC")
  fun observeThemes(): Flow<List<CustomThemeEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertTheme(theme: CustomThemeEntity)

  @Query("DELETE FROM custom_themes WHERE id = :id")
  suspend fun deleteTheme(id: String)
}

@Database(
  entities = [FlightSessionEntity::class, DiagnosticTraceEntity::class, CustomThemeEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class AutopilotDatabase : RoomDatabase() {
  abstract fun flightSessionDao(): FlightSessionDao

  abstract fun diagnosticTraceDao(): DiagnosticTraceDao

  abstract fun customThemeDao(): CustomThemeDao

  companion object {
    @Volatile private var INSTANCE: AutopilotDatabase? = null

    fun getInstance(context: Context): AutopilotDatabase {
      return INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AutopilotDatabase::class.java,
                "autopilot_flight_store.db",
              )
              .build()
              .also { INSTANCE = it }
        }
    }
  }
}

class AutopilotRepository(private val db: AutopilotDatabase) {
  val flightSessions: Flow<List<FlightSessionEntity>> = db.flightSessionDao().observeSessions()
  val diagnosticTraces: Flow<List<DiagnosticTraceEntity>> = db.diagnosticTraceDao().observeTraces()
  val customThemes: Flow<List<CustomThemeEntity>> = db.customThemeDao().observeThemes()

  suspend fun startFlightSession(
    protocolId: String,
    protocolName: String,
    hostIp: String,
    isSyntheticHarness: Boolean,
  ): Long =
    db.flightSessionDao()
      .insertSession(
        FlightSessionEntity(
          protocolId = protocolId,
          protocolName = protocolName,
          hostIp = hostIp,
          startedAtMs = System.currentTimeMillis(),
          endedAtMs = null,
          packetsSent = 0L,
          packetsReceived = 0L,
          droppedPackets = 0L,
          framesDecoded = 0L,
          isSyntheticHarness = isSyntheticHarness,
          terminationReason = "Active",
        )
      )

  suspend fun finishFlightSession(
    sessionId: Long,
    sent: Long,
    received: Long,
    dropped: Long,
    frames: Long,
    reason: String,
  ) {
    db.flightSessionDao()
      .finishSession(
        id = sessionId,
        endedAtMs = System.currentTimeMillis(),
        sent = sent,
        received = received,
        dropped = dropped,
        frames = frames,
        reason = reason,
      )
  }

  suspend fun recordTrace(
    protocolId: String,
    subsystem: String,
    severity: String,
    message: String,
    packetHex: String? = null,
  ) {
    db.diagnosticTraceDao()
      .insertTrace(
        DiagnosticTraceEntity(
          timestampMs = System.currentTimeMillis(),
          protocolId = protocolId,
          subsystem = subsystem,
          severity = severity,
          message = message,
          packetHex = packetHex,
        )
      )
  }

  suspend fun clearFlightHistory() {
    db.flightSessionDao().clearSessions()
    db.diagnosticTraceDao().clearTraces()
  }

  suspend fun saveCustomTheme(entity: CustomThemeEntity) = db.customThemeDao().upsertTheme(entity)

  suspend fun deleteCustomTheme(id: String) = db.customThemeDao().deleteTheme(id)
}
