package com.nexuzy.publisher.workflow

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.ai.AiPipeline
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssItem
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.network.RssFeedParser
import com.nexuzy.publisher.network.WordPressApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

/**
 * End-to-end workflow for:
 * 1) Fetch RSS from all saved feeds
 * 2) Identify today's top/hot/viral candidates
 * 3) Verify + rewrite through AI pipeline
 * 4) Save local drafts and push WordPress drafts
 */
class NewsWorkflowManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val rssParser = RssFeedParser()
    private val aiPipeline = AiPipeline(context)
    private val wpClient = WordPressApiClient()
    private val keyManager = ApiKeyManager(context)

    data class ScoredNews(
        val item: RssItem,
        val score: Int,
        val reason: String,
        val isHot: Boolean,
        val isPotentialViral: Boolean
    )

    data class DailyNewsSnapshot(
        val allToday: List<RssItem> = emptyList(),
        val topArticles: List<ScoredNews> = emptyList(),
        val hotNews: List<ScoredNews> = emptyList(),
        val potentialViral: List<ScoredNews> = emptyList(),
        val relatedClusters: Map<String, List<RssItem>> = emptyMap()
    )

    data class WorkflowResult(
        val fetchedCount: Int = 0,
        val processedCount: Int = 0,
        val savedDraftCount: Int = 0,
        val pushedDraftCount: Int = 0,
        val failures: List<String> = emptyList(),
        val snapshot: DailyNewsSnapshot = DailyNewsSnapshot()
    )

    // ─── Full batch: fetch → score → verify → push top-15 ───────────────────
    suspend fun fetchVerifyWriteSaveAndPushDraft(limitPerFeed: Int = 5): WorkflowResult =
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()
            val activeWpSite = db.wordPressSiteDao().getActiveSite()
            val adsCode = keyManager.getWordPressAdsCode()

            val allItems = fetchFromAllFeeds(limitPerFeed)
            if (allItems.isEmpty()) {
                return@withContext WorkflowResult(
                    failures = listOf("No active RSS feeds configured or no news fetched")
                )
            }

            val todayItems = allItems.filter { isTodayNews(it) }
            val scored = scoreNewsForToday(todayItems)
            val top = scored.sortedByDescending { it.score }.take(15)
            val hot = top.filter { it.isHot }
            val viral = top.filter { it.isPotentialViral }
            val clusters = findRelatedNewsClusters(todayItems)

            var processedCount = 0
            var savedDraftCount = 0
            var pushedDraftCount = 0

            for (candidate in top) {
                try {
                    val item = candidate.item
                    if (!isLikelyNews(item)) {
                        failures.add("Skipped non-news item: ${item.title}")
                        continue
                    }
                    val alreadySaved = db.articleDao().countBySourceUrl(item.link) > 0
                    if (alreadySaved) {
                        failures.add("Skipped duplicate: ${item.link}")
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
                        val publish = wpClient.pushNewsDraftWithSeo(
                            activeWpSite, pipelineResult.article, adsCode
                        )
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
                            failures.add(
                                "WP push failed for '${item.title}': ${publish.error}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NewsWorkflow", "Error: ${e.message}")
                    failures.add("Error: ${e.message}")
                }
            }

            WorkflowResult(
                fetchedCount = allItems.size,
                processedCount = processedCount,
                savedDraftCount = savedDraftCount,
                pushedDraftCount = pushedDraftCount,
                failures = failures,
                snapshot = DailyNewsSnapshot(
                    allToday = todayItems,
                    topArticles = top,
                    hotNews = hot,
                    potentialViral = viral,
                    relatedClusters = clusters
                )
            )
        }

    // ─── Fetch only — used by RssFragment Fetch button ──────────────────────
    suspend fun fetchTodayHotNews(limitPerFeed: Int = 20): DailyNewsSnapshot =
        withContext(Dispatchers.IO) {
            val allItems = fetchFromAllFeeds(limitPerFeed)
            val todayItems = allItems.filter { isTodayNews(it) }
            val scored = scoreNewsForToday(todayItems).sortedByDescending { it.score }
            DailyNewsSnapshot(
                allToday = todayItems,
                topArticles = scored.take(20),
                hotNews = scored.filter { it.isHot }.take(10),
                potentialViral = scored.filter { it.isPotentialViral }.take(10),
                relatedClusters = findRelatedNewsClusters(todayItems)
            )
        }

    // ─── Single item: AI pipeline + save + push (called from Article Editor) ─
    suspend fun processAndPushSingleItem(item: RssItem): WorkflowResult =
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()
            val activeWpSite = db.wordPressSiteDao().getActiveSite()
            val adsCode = keyManager.getWordPressAdsCode()

            // Duplicate check
            if (item.link.isNotBlank() && db.articleDao().countBySourceUrl(item.link) > 0) {
                return@withContext WorkflowResult(
                    fetchedCount = 1,
                    failures = listOf("Article already exists in local DB: ${item.link}")
                )
            }

            val pipelineResult = aiPipeline.processRssItem(
                rssItem = item,
                wordpressSiteId = activeWpSite?.id ?: 0
            )

            if (!pipelineResult.success || pipelineResult.article == null) {
                return@withContext WorkflowResult(
                    fetchedCount = 1,
                    failures = listOf(pipelineResult.error.ifBlank { "AI pipeline failed" })
                )
            }

            val localId = db.articleDao().insert(pipelineResult.article)
            var pushed = 0

            if (activeWpSite != null) {
                val pub = wpClient.pushNewsDraftWithSeo(
                    activeWpSite, pipelineResult.article, adsCode
                )
                if (pub.success) {
                    pushed = 1
                    db.articleDao().update(
                        pipelineResult.article.copy(
                            id = localId,
                            wordpressPostId = pub.postId,
                            status = "draft"
                        )
                    )
                } else {
                    failures.add(pub.error ?: "WordPress push failed")
                }
            } else {
                failures.add("No active WordPress site configured — saved locally only")
            }

            WorkflowResult(
                fetchedCount = 1,
                processedCount = 1,
                savedDraftCount = 1,
                pushedDraftCount = pushed,
                failures = failures
            )
        }

    // ─── Internal helpers ────────────────────────────────────────────────────
    private suspend fun fetchFromAllFeeds(limitPerFeed: Int): List<RssItem> {
        val feeds = db.rssFeedDao().getActiveFeeds()
        val merged = mutableListOf<RssItem>()
        for (feed in feeds) {
            val items = rssParser.fetchFeed(
                feedUrl = feed.url,
                feedName = feed.name,
                feedCategory = feed.category,
                scrapeImages = true
            ).take(limitPerFeed)
            merged.addAll(items)
        }
        return merged.distinctBy { it.link }
    }

    private fun scoreNewsForToday(items: List<RssItem>): List<ScoredNews> {
        return items.map { item ->
            val text = "${item.title} ${item.description}".lowercase(Locale.getDefault())
            var score = 0
            val reasons = mutableListOf<String>()

            if (item.imageUrl.isNotBlank()) { score += 10; reasons.add("has image") }
            if (text.length > 180) { score += 10; reasons.add("rich details") }

            val hotWords = listOf("breaking", "live", "urgent", "election", "war", "market", "ai", "launch")
            val hotHits = hotWords.count { text.contains(it) }
            if (hotHits > 0) { score += hotHits * 8; reasons.add("hot keywords x$hotHits") }

            val viralWords = listOf("viral", "shocking", "trend", "celebrity", "record", "millions", "billion")
            val viralHits = viralWords.count { text.contains(it) }
            if (viralHits > 0) { score += viralHits * 7; reasons.add("viral keywords x$viralHits") }

            if (isTodayNews(item)) { score += 15; reasons.add("published today") }

            ScoredNews(
                item = item,
                score = score,
                reason = reasons.joinToString(", "),
                isHot = score >= 30,
                isPotentialViral = score >= 35 && viralHits > 0
            )
        }
    }

    private fun findRelatedNewsClusters(items: List<RssItem>): Map<String, List<RssItem>> {
        val clusters = linkedMapOf<String, MutableList<RssItem>>()
        for (item in items) {
            val topic = detectPrimaryTopic(item)
            clusters.getOrPut(topic) { mutableListOf() }.add(item)
        }
        return clusters.filterValues { it.size >= 2 }
    }

    private fun detectPrimaryTopic(item: RssItem): String {
        val text = "${item.title} ${item.description}".lowercase(Locale.getDefault())
        return when {
            listOf("ai", "openai", "gemini", "chatgpt", "llm").any { text.contains(it) } -> "AI"
            listOf("market", "stock", "economy", "inflation", "crypto").any { text.contains(it) } -> "Finance"
            listOf("election", "government", "policy", "minister", "parliament").any { text.contains(it) } -> "Politics"
            listOf("match", "league", "cup", "goal", "cricket", "football").any { text.contains(it) } -> "Sports"
            listOf("movie", "actor", "celebrity", "music", "show").any { text.contains(it) } -> "Entertainment"
            else -> item.feedCategory.ifBlank { "General" }
        }
    }

    private fun isLikelyNews(item: RssItem): Boolean {
        if (item.title.isBlank() || item.link.isBlank()) return false
        val blocked = listOf("advertisement", "sponsored", "coupon", "deal")
        return blocked.none { "${item.title} ${item.description}".lowercase().contains(it) }
    }

    private fun isTodayNews(item: RssItem): Boolean {
        val source = item.pubDate.trim()
        if (source.isBlank()) return true
        val now = Instant.now()
        val cutoff = now.minusSeconds(24 * 60 * 60)
        val parsed = parseRssInstant(source) ?: return false
        return parsed >= cutoff && parsed <= now
    }

    private fun parseRssInstant(raw: String): Instant? {
        val normalized = raw
            .replace(" IST", " +0530")
            .replace(" GMT", " +0000")
            .trim()

        val zoneAware = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("EEE, dd MMM yyyy HH:mm:ss Z")
                .toFormatter(Locale.ENGLISH)
        )
        for (fmt in zoneAware) {
            try { return ZonedDateTime.parse(normalized, fmt).toInstant() }
            catch (_: DateTimeParseException) {}
            catch (_: Exception) {}
            try { return Instant.from(fmt.parse(normalized)) }
            catch (_: Exception) {}
        }

        val localDateTimeFormats = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
            "dd MMM yyyy HH:mm:ss", "dd MMM yyyy HH:mm"
        )
        for (pattern in localDateTimeFormats) {
            try {
                val ldt = LocalDateTime.parse(
                    normalized, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                )
                return ldt.atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {}
        }

        val legacyFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd HH:mm:ss Z"
        )
        for (pattern in legacyFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(normalized)
                if (date != null) return date.toInstant()
            } catch (_: Exception) {}
        }
        return null
    }
}
