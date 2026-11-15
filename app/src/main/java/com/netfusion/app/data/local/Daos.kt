package com.netfusion.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedTestDao {
    @Query("SELECT * FROM speed_test_results ORDER BY timestampMs DESC LIMIT 30")
    fun observeRecentResults(): Flow<List<SpeedTestResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: SpeedTestResultEntity)

    @Query("DELETE FROM speed_test_results")
    suspend fun clearAll()
}

@Dao
interface UsageStatsDao {
    @Query("SELECT * FROM daily_usage WHERE dateKey = :dateKey LIMIT 1")
    fun observeDay(dateKey: String): Flow<DailyUsageEntity?>

    @Query("SELECT * FROM daily_usage WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getDay(dateKey: String): DailyUsageEntity?

    @Query("SELECT * FROM daily_usage WHERE dateKey >= :startDateKey ORDER BY dateKey DESC")
    fun observeSince(startDateKey: String): Flow<List<DailyUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyUsageEntity)

    @Query("DELETE FROM daily_usage")
    suspend fun clearAll()
}
