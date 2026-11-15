package com.netfusion.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SpeedTestResultEntity::class, DailyUsageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class NetFusionDatabase : RoomDatabase() {
    abstract fun speedTestDao(): SpeedTestDao
    abstract fun usageStatsDao(): UsageStatsDao
}
