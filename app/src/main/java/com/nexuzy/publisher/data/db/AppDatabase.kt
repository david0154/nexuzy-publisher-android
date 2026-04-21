package com.nexuzy.publisher.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nexuzy.publisher.data.dao.ArticleDao
import com.nexuzy.publisher.data.dao.RssFeedDao
import com.nexuzy.publisher.data.dao.WordPressSiteDao
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.data.model.WordPressSite

@Database(
    entities = [Article::class, RssFeed::class, WordPressSite::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun rssFeedDao(): RssFeedDao
    abstract fun wordPressSiteDao(): WordPressSiteDao

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
                    .fallbackToDestructiveMigration() // handles schema change from v1 to v2
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
