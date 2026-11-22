package com.screen.buddy.di

import android.content.Context
import androidx.room.Room
import com.screen.buddy.data.db.BuddyDatabase
import com.screen.buddy.data.db.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BuddyDatabase {
        return Room.databaseBuilder(context, BuddyDatabase::class.java, "buddy.db").build()
    }

    @Provides
    fun provideMessageDao(db: BuddyDatabase): MessageDao = db.messageDao()
}
