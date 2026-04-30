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
 * End-to-end workflow for the Nexuzy Publisher.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GEMINI API USAGE POLICY (saves quota):
 *
 *   fetchTodayHotNews()              → RSS only. ZERO API calls.
 *   fetchAndVerifyWithOpenAi()       → RSS + OpenAI verify only. ZERO Gemini.
 *   processAndPushSingleItem(item)   → Full pipeline (Gemini + OpenAI + Sarvam).
 *                                      Called ONLY when user taps "Write Article".
 *
 * Gemini is NEVER called automatically in the background.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class NewsWorkflowManager(private val context: Context) {

    private val db         = AppDatabase.getDatabase(context)
    private val rssParser  = RssFeedParser()
    private val aiPipeline = AiPipeline(context)
    private val wpClient   = WordPressApiClient()
    private val keyManager = ApiKeyManager(context)

    // ─── Data classes ─────────────────────────────────────────────────────────

    data class ScoredNews(
        val item: RssItem,
        val score: Int,
        val reason: String,
        val isHot: Boolean,
        val isPotentialViral: Boolean,
        /** Filled only by fetchAndVerifyWithOpenAi(), empty otherwise. */
        val credibilityScore: Float = 0f,
        val credible: Boolean = true
    )

    data class DailyNewsSnapshot(
        val allToday: List<RssItem>           = emptyList(),
        val topArticles: List<ScoredNews>     = emptyList(),
        val hotNews: List<ScoredNews>         = emptyList(),
        val potentialViral: List<ScoredNews>  = emptyList(),
        val relatedClusters: Map<String, List<RssItem>> = emptyMap()
    )

    data class WorkflowResult(
        val fetchedCount: Int       = 0,
        val processedCount: Int     = 0,
        val savedDraftCount: Int    = 0,
        val pushedDraftCount: Int   = 0,
        val failures: List<String>  = emptyList(),
        val snapshot: DailyNewsSnapshot = DailyNewsSnapshot()
    )

    // ─── FETCH ONLY: pure RSS, absolutely zero API calls ─────────────────────

    /**
     * Fetches RSS feeds, scores and categorises headlines.
     * NO AI of any kind. Pure HTTP RSS parsing only.
     * Safe to call as many times as needed — costs nothing.
     */
    suspend fun fetchTodayHotNews(limitPerFeed: Int = 20): DailyNewsSnapshot =
        withContext(Dispatchers.IO) {
            val allItems  = fetchFromAllFeeds(limitPerFeed)
            val todayItems = allItems.filter { isTodayNews(it) }
            val scored    = scoreNewsForToday(todayItems).sortedByDescending { it.score }
            DailyNewsSnapshot(
                allToday       = todayItems,
                topArticles    = scored.take(20),
                hotNews        = scored.filter { it.isHot }.take(10),
                potentialViral = scored.filter { it.isPotentialViral }.take(10),
                relatedClusters = findRelatedNewsClusters(todayItems)
            )
        }

    // ─── FETCH + OPENAI VERIFY: no Gemini, used for credibility tagging ──────

    /**
     * Fetches RSS + runs a lightweight OpenAI credibility check on each item.
     * Gemini is NOT called. Use this to populate the news list with
     * credibility badges before the user selects an article to write.
     */
    suspend fun fetchAndVerifyWithOpenAi(limitPerFeed: Int = 20): DailyNewsSnapshot =
        withContext(Dispatchers.IO) {
            val allItems   = fetchFromAllFeeds(limitPerFeed)
            val todayItems = allItems.filter { isTodayNews(it) }
            val scored     = scoreNewsForToday(todayItems)

            // Run OpenAI credibility check on top-30 items
            val enriched = scored.sortedByDescending { it.score }.take(30).map { candidate ->
                try {
                    val verify = aiPipeline.verifyOnlyWithOpenAi(candidate.item)
                    candidate.copy(
                        credible         = verify.credible,
                        credibilityScore = verify.confidenceScore
                    )
                } catch (e: Exception) {
                    Log.w("NewsWorkflow", "OpenAI quick-verify failed: ${e.message}")
                    candidate
                }
            }

            DailyNewsSnapshot(
                allToday        = todayItems,
                topArticles     = enriched,
                hotNews         = enriched.filter { it.isHot }.take(10),
                potentialViral  = enriched.filter { it.isPotentialViral }.take(10),
                relatedClusters = findRelatedNewsClusters(todayItems)
            )
        }

    // ─── SINGLE ITEM WRITE: full AI pipeline (Gemini + OpenAI + Sarvam) ──────

    /**
     * Full AI pipeline for ONE user-selected article.
     * This is the ONLY function that calls Gemini.
     * Called from AiWriterFragment when user taps "Write Article".
     */
    suspend fun processAndPushSingleItem(item: RssItem): WorkflowResult =
        withContext(Dispatchers.IO) {
            val failures    = mutableListOf<String>()
            val activeWpSite = db.wordPressSiteDao().getActiveSite()
            val adsCode     = keyManager.getWordPressAdsCode()

            // Duplicate check via URL — no AI needed
            if (item.link.isNotBlank() && db.articleDao().countBySourceUrl(item.link) > 0) {
                return@withContext WorkflowResult(
                    fetchedCount = 1,
                    failures     = listOf("Article already exists: ${item.link}")
                )
            }

            // Full pipeline: Gemini writes → OpenAI verifies → Sarvam grammar → SEO
            val pipelineResult = aiPipeline.processRssItem(
                rssItem         = item,
                wordpressSiteId = activeWpSite?.id ?: 0
            )

            if (!pipelineResult.success || pipelineResult.article == null) {
                return@withContext WorkflowResult(
                    fetchedCount = 1,
                    failures     = listOf(pipelineResult.error.ifBlank { "AI pipeline failed" })
                )
            }

            val localId = db.articleDao().insert(pipelineResult.article)
            var pushed  = 0

            if (activeWpSite != null) {
                val pub = wpClient.pushNewsDraftWithSeo(activeWpSite, pipelineResult.article, adsCode)
                if (pub.success) {
                    pushed = 1
                    db.articleDao().update(
                        pipelineResult.article.copy(
                            id              = localId,
                            wordpressPostId = pub.postId,
                            status          = "draft"
                        )
                    )
                } else {
                    failures.add(pub.error ?: "WordPress push failed")
                }
            } else {
                failures.add("No active WordPress site — saved locally only")
            }

            WorkflowResult(
                fetchedCount    = 1,
                processedCount  = 1,
                savedDraftCount = 1,
                pushedDraftCount = pushed,
                failures        = failures
            )
        }

    // ─── LEGACY BATCH: kept for compatibility ────────────────────────────────

    suspend fun fetchVerifyWriteSaveAndPushDraft(limitPerFeed: Int = 5): WorkflowResult =
        withContext(Dispatchers.IO) {
            val snapshot = fetchAndVerifyWithOpenAi(limitPerFeed)
            WorkflowResult(
                fetchedCount = snapshot.allToday.size,
                snapshot     = snapshot
            )
        }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private suspend fun fetchFromAllFeeds(limitPerFeed: Int): List<RssItem> {
        val feeds  = db.rssFeedDao().getActiveFeeds()
        val merged = mutableListOf<RssItem>()
        for (feed in feeds) {
            try {
                // FIX: parameter is `enrichItems` (not scrapeImages)
                val items = rssParser.fetchFeed(
                    feedUrl      = feed.url,
                    feedName     = feed.name,
                    feedCategory = feed.category,
                    enrichItems  = true
                ).take(limitPerFeed)
                merged.addAll(items)
                Log.d("NewsWorkflow", "Fetched ${items.size} from ${feed.name}")
            } catch (e: Exception) {
                Log.w("NewsWorkflow", "Feed fetch failed for ${feed.url}: ${e.message}")
            }
        }
        return merged.distinctBy { it.link }
    }

    private fun scoreNewsForToday(items: List<RssItem>): List<ScoredNews> {
        return items.map { item ->
            val text = "${item.title} ${item.description}".lowercase(Locale.getDefault())
            var score = 0
            val reasons = mutableListOf<String>()

            if (item.imageUrl.isNotBlank())  { score += 10; reasons.add("has image") }
            if (text.length > 180)           { score += 10; reasons.add("rich details") }

            val hotWords   = listOf("breaking", "live", "urgent", "election", "war", "market", "ai", "launch")
            val viralWords = listOf("viral", "shocking", "trend", "celebrity", "record", "millions", "billion")
            val hotHits   = hotWords.count   { text.contains(it) }
            val viralHits = viralWords.count { text.contains(it) }

            if (hotHits   > 0) { score += hotHits   * 8; reasons.add("hot x$hotHits")   }
            if (viralHits > 0) { score += viralHits * 7; reasons.add("viral x$viralHits") }
            if (isTodayNews(item)) { score += 15; reasons.add("today") }

            ScoredNews(
                item              = item,
                score             = score,
                reason            = reasons.joinToString(", "),
                isHot             = score >= 30,
                isPotentialViral  = score >= 35 && viralHits > 0
            )
        }
    }

    private fun findRelatedNewsClusters(items: List<RssItem>): Map<String, List<RssItem>> {
        val clusters = linkedMapOf<String, MutableList<RssItem>>()
        for (item in items) {
            clusters.getOrPut(detectPrimaryTopic(item)) { mutableListOf() }.add(item)
        }
        return clusters.filterValues { it.size >= 2 }
    }

    private fun detectPrimaryTopic(item: RssItem): String {
        val text = "${item.title} ${item.description}".lowercase(Locale.getDefault())
        return when {
            listOf("ai", "openai", "gemini", "chatgpt", "llm").any { text.contains(it) }             -> "AI"
            listOf("market", "stock", "economy", "inflation", "crypto").any { text.contains(it) }    -> "Finance"
            listOf("election", "government", "policy", "minister", "parliament").any { text.contains(it) } -> "Politics"
            listOf("match", "league", "cup", "goal", "cricket", "football").any { text.contains(it) } -> "Sports"
            listOf("movie", "actor", "celebrity", "music", "show").any { text.contains(it) }         -> "Entertainment"
            else -> item.feedCategory.ifBlank { "General" }
        }
    }

    private fun isTodayNews(item: RssItem): Boolean {
        val source = item.pubDate.trim()
        if (source.isBlank()) return true
        val now    = Instant.now()
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
            try { return ZonedDateTime.parse(normalized, fmt).toInstant() } catch (_: Exception) {}
            try { return Instant.from(fmt.parse(normalized)) }             catch (_: Exception) {}
        }

        val localPatterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
            "dd MMM yyyy HH:mm:ss", "dd MMM yyyy HH:mm"
        )
        for (pattern in localPatterns) {
            try {
                return LocalDateTime
                    .parse(normalized, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                    .atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {}
        }

        val legacyPatterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd HH:mm:ss Z"
        )
        for (pattern in legacyPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(normalized)?.let { return it.toInstant() }
            } catch (_: Exception) {}
        }
        return null
    }
}
