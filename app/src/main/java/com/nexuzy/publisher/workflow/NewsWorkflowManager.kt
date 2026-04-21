package com.nexuzy.publisher.workflow

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.ai.AiPipeline
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.Article
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.network.RssFeedParser
import com.nexuzy.publisher.network.WordPressApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * End-to-end workflow for:
 * 1) Fetch RSS
 * 2) Verify/score with OpenAI (inside AiPipeline)
 * 3) Generate title/content with Gemini
 * 4) Clean grammar with Sarvam
 * 5) Save local draft
 * 6) Push WordPress draft
 */
class NewsWorkflowManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val rssParser = RssFeedParser()
    private val aiPipeline = AiPipeline(context)
    private val wpClient = WordPressApiClient()
    private val keyManager = ApiKeyManager(context)

    data class WorkflowResult(
        val fetchedCount: Int = 0,
        val processedCount: Int = 0,
        val savedDraftCount: Int = 0,
        val pushedDraftCount: Int = 0,
        val failures: List<String> = emptyList()
    )

    suspend fun fetchVerifyWriteSaveAndPushDraft(limitPerFeed: Int = 5): WorkflowResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val feeds = db.rssFeedDao().getActiveFeeds()
        val activeWpSite = db.wordPressSiteDao().getActiveSite()
        val adsCode = keyManager.getWordPressAdsCode()

        if (feeds.isEmpty()) {
            return@withContext WorkflowResult(failures = listOf("No active RSS feeds configured"))
        }

        var fetchedCount = 0
        var processedCount = 0
        var savedDraftCount = 0
        var pushedDraftCount = 0

        for (feed in feeds) {
            val rssItems = rssParser.fetchFeed(
                feedUrl = feed.url,
                feedName = feed.name,
                feedCategory = feed.category,
                scrapeImages = true
            ).take(limitPerFeed)

            fetchedCount += rssItems.size

            for (item in rssItems) {
                try {
                    if (!isLikelyNews(item)) {
                        failures.add("Skipped non-news item: ${item.title}")
                        continue
                    }

                    val pipelineResult = aiPipeline.processRssItem(
                        rssItem = item,
                        wordpressSiteId = activeWpSite?.id ?: 0
                    )

                    if (!pipelineResult.success || pipelineResult.article == null) {
                        failures.add("Pipeline failed for '${item.title}': ${pipelineResult.error}")
                        continue
                    }

                    processedCount += 1

                    val localArticleId = db.articleDao().insert(pipelineResult.article)
                    savedDraftCount += 1

                    if (activeWpSite != null) {
                        val publish = wpClient.pushDraft(activeWpSite, pipelineResult.article, adsCode)
                        if (publish.success) {
                            pushedDraftCount += 1
                            db.articleDao().update(
                                pipelineResult.article.copy(
                                    id = localArticleId,
                                    wordpressPostId = publish.postId,
                                    status = "draft"
                                )
                            )
                        } else {
                            failures.add("WordPress draft push failed for '${item.title}': ${publish.error}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NewsWorkflow", "Error for item ${item.title}: ${e.message}")
                    failures.add("Error for '${item.title}': ${e.message}")
                }
            }
        }

        WorkflowResult(
            fetchedCount = fetchedCount,
            processedCount = processedCount,
            savedDraftCount = savedDraftCount,
            pushedDraftCount = pushedDraftCount,
            failures = failures
        )
    }

    private fun isLikelyNews(item: RssItem): Boolean {
        if (item.title.isBlank()) return false
        if (item.link.isBlank()) return false
        val text = "${item.title} ${item.description}".lowercase()
        val blocked = listOf("advertisement", "sponsored", "coupon", "deal")
        return blocked.none { text.contains(it) }
    }
}
