package com.nexuzy.publisher.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nexuzy.publisher.data.dao.ArticleDao
import com.nexuzy.publisher.data.dao.RssFeedDao
import com.nexuzy.publisher.data.dao.UserProfileDao
import com.nexuzy.publisher.data.dao.WordPressSiteDao
import com.nexuzy.publisher.data.db.entity.UserProfile
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.data.model.WordPressSite

@Database(
    entities = [Article::class, RssFeed::class, WordPressSite::class, UserProfile::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun rssFeedDao(): RssFeedDao
    abstract fun wordPressSiteDao(): WordPressSiteDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nexuzy_publisher_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
