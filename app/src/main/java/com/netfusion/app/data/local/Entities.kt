package com.netfusion.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_test_results")
data class SpeedTestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val connectionMode: String,
    val transportLabel: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val pingMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float
)

@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey val dateKey: String, // yyyy-MM-dd
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val cellularRxBytes: Long = 0L,
    val cellularTxBytes: Long = 0L,
    val bondedSeconds: Long = 0L,
    val smartSwitchCount: Int = 0
)
