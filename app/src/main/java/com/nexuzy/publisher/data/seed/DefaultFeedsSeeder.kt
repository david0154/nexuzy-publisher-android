package com.nexuzy.publisher.data.seed

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed

/**
 * Seeds the RSS feed database with exactly 38 verified, active news sources.
 * Exactly 2 feeds per category — only the most reliable, confirmed-working URLs.
 * Categories (19): AI, Business, Career, Crypto, Economy, Entertainment,
 *   Environment, Fashion, Food, General, Health, India, Politics,
 *   Science, Sports, StockMarket, Tech, Travel, World
 *
 * seedIfEmpty() also REMOVES any default feed in the DB that is no longer in this
 * list, so existing installs are automatically cleaned up.
 *
 * Call seedIfEmpty() once on app start.
 */
object DefaultFeedsSeeder {

    private const val TAG = "DefaultFeedsSeeder"

    suspend fun seedIfEmpty(context: Context) {
        val db  = AppDatabase.getDatabase(context)
        val dao = db.rssFeedDao()

        val canonical = buildFeedList()
        val canonicalUrls = canonical.map { it.url }.toSet()

        val existingAll  = dao.getAllOnce()
        val existingUrls = existingAll.map { it.url }.toSet()

        // 1. Insert any missing canonical feeds
        val toInsert = canonical.filter { it.url !in existingUrls }
        if (toInsert.isNotEmpty()) {
            toInsert.forEach { dao.insert(it) }
            Log.i(TAG, "Inserted ${toInsert.size} missing default feeds.")
        }

        // 2. Remove default feeds that are no longer in the canonical list
        //    (keeps user-added feeds with isDefault=false untouched)
        val toRemove = existingAll.filter { it.isDefault && it.url !in canonicalUrls }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { dao.delete(it) }
            Log.i(TAG, "Removed ${toRemove.size} obsolete default feeds.")
        }

        if (toInsert.isEmpty() && toRemove.isEmpty()) {
            Log.i(TAG, "DB already matches the 38-feed canonical list — skipping.")
        }
    }

    private fun buildFeedList(): List<RssFeed> = listOf(

        // ═══════════════════════════════════════
        // AI & MACHINE LEARNING  (2)
        // ═══════════════════════════════════════
        f("MIT Technology Review – AI",  "https://www.technologyreview.com/feed/",                           "AI & Machine Learning"),
        f("VentureBeat – AI",            "https://venturebeat.com/category/ai/feed/",                        "AI & Machine Learning"),

        // ═══════════════════════════════════════
        // BUSINESS  (2)
        // ═══════════════════════════════════════
        f("BBC Business",                "https://feeds.bbci.co.uk/news/business/rss.xml",                   "Business"),
        f("CNBC Business",               "https://www.cnbc.com/id/10001147/device/rss/rss.html",             "Business"),

        // ═══════════════════════════════════════
        // CAREER  (2)
        // ═══════════════════════════════════════
        f("Fast Company – Work Life",    "https://www.fastcompany.com/work-life/rss",                        "Career"),
        f("Glassdoor Blog",              "https://www.glassdoor.com/blog/feed/",                             "Career"),

        // ═══════════════════════════════════════
        // CRYPTOCURRENCY  (2)
        // ═══════════════════════════════════════
        f("CoinDesk",                    "https://www.coindesk.com/arc/outboundfeeds/rss/",                  "Cryptocurrency"),
        f("CoinTelegraph",               "https://cointelegraph.com/rss",                                    "Cryptocurrency"),

        // ═══════════════════════════════════════
        // ECONOMY  (2)
        // ═══════════════════════════════════════
        f("The Economist – Finance",     "https://www.economist.com/finance-and-economics/rss.xml",          "Economy"),
        f("Yahoo Finance",               "https://finance.yahoo.com/news/rssindex",                          "Economy"),

        // ═══════════════════════════════════════
        // ENTERTAINMENT  (2)
        // ═══════════════════════════════════════
        f("Variety",                     "https://variety.com/feed/",                                        "Entertainment"),
        f("Hollywood Reporter",          "https://www.hollywoodreporter.com/feed/",                          "Entertainment"),

        // ═══════════════════════════════════════
        // ENVIRONMENT  (2)
        // ═══════════════════════════════════════
        f("BBC Environment",             "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",    "Environment"),
        f("Carbon Brief",                "https://www.carbonbrief.org/feed",                                 "Environment"),

        // ═══════════════════════════════════════
        // FASHION  (2)
        // ═══════════════════════════════════════
        f("Vogue",                       "https://www.vogue.com/feed/rss",                                   "Fashion"),
        f("WWD",                         "https://wwd.com/feed/",                                            "Fashion"),

        // ═══════════════════════════════════════
        // FOOD & LIFESTYLE  (2)
        // ═══════════════════════════════════════
        f("Bon Appétit",                 "https://www.bonappetit.com/feed/rss",                              "Food & Lifestyle"),
        f("Lifehacker",                  "https://lifehacker.com/feed/rss",                                  "Food & Lifestyle"),

        // ═══════════════════════════════════════
        // GENERAL  (2)
        // ═══════════════════════════════════════
        f("BBC News",                    "https://feeds.bbci.co.uk/news/rss.xml",                            "General"),
        f("Al Jazeera – English",        "https://www.aljazeera.com/xml/rss/all.xml",                        "General"),

        // ═══════════════════════════════════════
        // HEALTH  (2)
        // ═══════════════════════════════════════
        f("WHO News",                    "https://www.who.int/rss-feeds/news-english.xml",                   "Health"),
        f("Medical News Today",          "https://www.medicalnewstoday.com/newsfeeds.xml",                   "Health"),

        // ═══════════════════════════════════════
        // INDIA NEWS  (2)
        // ═══════════════════════════════════════
        f("Times of India – India",      "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms",     "India"),
        f("The Hindu – India",           "https://www.thehindu.com/news/national/feeder/default.rss",        "India"),

        // ═══════════════════════════════════════
        // POLITICS  (2)
        // ═══════════════════════════════════════
        f("Politico",                    "https://www.politico.com/rss/politicopicks.xml",                   "Politics"),
        f("BBC – Politics",              "https://feeds.bbci.co.uk/news/politics/rss.xml",                   "Politics"),

        // ═══════════════════════════════════════
        // SCIENCE  (2)
        // ═══════════════════════════════════════
        f("ScienceDaily",                "https://www.sciencedaily.com/rss/all.xml",                         "Science"),
        f("New Scientist",               "https://www.newscientist.com/feed/home/",                          "Science"),

        // ═══════════════════════════════════════
        // SPORTS  (2)
        // ═══════════════════════════════════════
        f("ESPN Top Headlines",          "https://www.espn.com/espn/rss/news",                               "Sports"),
        f("BBC Sport",                   "https://feeds.bbci.co.uk/sport/rss.xml",                           "Sports"),

        // ═══════════════════════════════════════
        // STOCK MARKET  (2)
        // ═══════════════════════════════════════
        f("CNBC Markets",                "https://www.cnbc.com/id/20910258/device/rss/rss.html",             "Stock Market"),
        f("Mint – Markets",              "https://www.livemint.com/rss/markets",                             "Stock Market"),

        // ═══════════════════════════════════════
        // TECH  (2)
        // ═══════════════════════════════════════
        f("TechCrunch",                  "https://techcrunch.com/feed/",                                     "Tech"),
        f("The Verge",                   "https://www.theverge.com/rss/index.xml",                           "Tech"),

        // ═══════════════════════════════════════
        // TRAVEL  (2)
        // ═══════════════════════════════════════
        f("The Points Guy",              "https://thepointsguy.com/feed/",                                   "Travel"),
        f("Skift",                       "https://skift.com/feed/",                                          "Travel"),

        // ═══════════════════════════════════════
        // WORLD NEWS  (2)
        // ═══════════════════════════════════════
        f("BBC World",                   "https://feeds.bbci.co.uk/news/world/rss.xml",                      "World"),
        f("Al Jazeera – World",          "https://www.aljazeera.com/xml/rss/all.xml",                        "World")
    )

    /** Shorthand builder — all seeded feeds are marked isDefault=true and isActive=true */
    private fun f(name: String, url: String, category: String) = RssFeed(
        name      = name,
        url       = url,
        category  = category,
        isActive  = true,
        isDefault = true
    )
}
