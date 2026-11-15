package com.netfusion.app.di

import android.content.Context
import androidx.room.Room
import com.netfusion.app.data.local.NetFusionDatabase
import com.netfusion.app.data.local.SpeedTestDao
import com.netfusion.app.data.local.UsageStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NetFusionDatabase {
        return Room.databaseBuilder(
            context,
            NetFusionDatabase::class.java,
            "netfusion.db"
        ).fallbackToDestructiveMigration(dropAllTables = false).build()
    }

    @Provides
    fun provideSpeedTestDao(db: NetFusionDatabase): SpeedTestDao = db.speedTestDao()

    @Provides
    fun provideUsageStatsDao(db: NetFusionDatabase): UsageStatsDao = db.usageStatsDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
