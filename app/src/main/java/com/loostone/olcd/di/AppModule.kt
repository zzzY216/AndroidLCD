package com.loostone.olcd.di

import android.content.Context
import com.loostone.olcd.data.ble.BleManager
import com.loostone.olcd.data.media.MediaConverter
import com.loostone.olcd.data.repository.OlcdRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBleManager(@ApplicationContext context: Context): BleManager {
        return BleManager(context)
    }

    @Provides
    @Singleton
    fun provideMediaConverter(@ApplicationContext context: Context): MediaConverter {
        return MediaConverter(context)
    }

    @Provides
    @Singleton
    fun provideOlcdRepository(
        bleManager: BleManager,
        mediaConverter: MediaConverter,
    ): OlcdRepository {
        return OlcdRepository(bleManager, mediaConverter)
    }
}
