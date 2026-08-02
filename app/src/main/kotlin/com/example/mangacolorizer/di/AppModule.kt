package com.example.mangacolorizer.di

import android.content.Context
import com.example.mangacolorizer.data.ColorizationDatabase
import com.example.mangacolorizer.inference.ColorizationCache
import com.example.mangacolorizer.inference.MangaColorizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import androidx.room.Room

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ColorizationDatabase {
        return Room.databaseBuilder(
            context,
            ColorizationDatabase::class.java,
            "colorization_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideMangaColorizer(@ApplicationContext context: Context): MangaColorizer {
        return MangaColorizer(context)
    }

    @Provides
    @Singleton
    fun provideColorizationCache(@ApplicationContext context: Context): ColorizationCache {
        return ColorizationCache(context)
    }
}
