package com.nexuzy.publisher.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.nexuzy.publisher.data.model.Article

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY createdAt DESC")
    fun getAllArticles(): LiveData<List<Article>>

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY createdAt DESC")
    fun getArticlesByStatus(status: String): LiveData<List<Article>>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getArticleById(id: Long): Article?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(article: Article): Long

    @Update
    suspend fun update(article: Article)

    @Delete
    suspend fun delete(article: Article)

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM articles WHERE status = 'published'")
    suspend fun getPublishedCount(): Int

    @Query("SELECT COUNT(*) FROM articles WHERE status = 'draft'")
    suspend fun getDraftCount(): Int
}
