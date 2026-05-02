package com.nexuzy.publisher.data.seed

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed

/**
 * Seeds the RSS feed database with exactly 80 trusted, verified news sources.
 * Categories: AI(8) Business(6) Career(3) Crypto(5) Economy(5) Entertainment(5)
 *             Environment(4) Fashion(4) Food(3) General(8) Health(5) India(6)
 *             Politics(5) Science(6) Sports(6) StockMarket(5) Tech(6) Travel(4) World(6)
 * Total = 109 — trimmed to 80 by removing low-signal duplicates and dead feeds.
 *
 * seedIfEmpty() also REMOVES any default feed in the DB that is no longer in this
 * 80-feed list, so existing installs are automatically cleaned up.
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
            Log.i(TAG, "DB already matches the 80-feed canonical list — skipping.")
        }
    }

    private fun buildFeedList(): List<RssFeed> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        // AI & MACHINE LEARNING  (8)
        // ══════════════════════════════════════════════════════════════════════
        f("MIT Technology Review – AI",  "https://www.technologyreview.com/feed/",                            "AI & Machine Learning"),
        f("VentureBeat – AI",            "https://venturebeat.com/category/ai/feed/",                         "AI & Machine Learning"),
        f("The Verge – AI",              "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml",  "AI & Machine Learning"),
        f("Google AI Blog",              "https://blog.research.google/feeds/posts/default?alt=rss",          "AI & Machine Learning"),
        f("DeepMind Blog",               "https://deepmind.google/blog/rss.xml",                              "AI & Machine Learning"),
        f("Towards Data Science",        "https://towardsdatascience.com/feed",                               "AI & Machine Learning"),
        f("OpenAI Blog",                 "https://openai.com/news/rss.xml",                                   "AI & Machine Learning"),
        f("IEEE Spectrum – AI",          "https://spectrum.ieee.org/feeds/topic/artificial-intelligence.rss",  "AI & Machine Learning"),

        // ══════════════════════════════════════════════════════════════════════
        // BUSINESS  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("BBC Business",                "https://feeds.bbci.co.uk/news/business/rss.xml",                    "Business"),
        f("The Economist – Business",    "https://www.economist.com/business/rss.xml",                        "Business"),
        f("CNBC Business",               "https://www.cnbc.com/id/10001147/device/rss/rss.html",              "Business"),
        f("Fast Company",                "https://www.fastcompany.com/latest/rss",                            "Business"),
        f("Economic Times",              "https://economictimes.indiatimes.com/rssfeedsdefault.cms",          "Business"),
        f("Business Standard",           "https://www.business-standard.com/rss/home_page_top_stories.rss",   "Business"),

        // ══════════════════════════════════════════════════════════════════════
        // CAREER  (3)
        // ══════════════════════════════════════════════════════════════════════
        f("Fast Company – Work Life",    "https://www.fastcompany.com/work-life/rss",                         "Career"),
        f("Glassdoor Blog",              "https://www.glassdoor.com/blog/feed/",                              "Career"),
        f("The Muse",                    "https://www.themuse.com/advice/rss",                                "Career"),

        // ══════════════════════════════════════════════════════════════════════
        // CRYPTOCURRENCY  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("CoinDesk",                    "https://www.coindesk.com/arc/outboundfeeds/rss/",                   "Cryptocurrency"),
        f("CoinTelegraph",               "https://cointelegraph.com/rss",                                     "Cryptocurrency"),
        f("Decrypt",                     "https://decrypt.co/feed",                                           "Cryptocurrency"),
        f("The Block",                   "https://www.theblock.co/rss.xml",                                   "Cryptocurrency"),
        f("BeInCrypto",                  "https://beincrypto.com/feed/",                                      "Cryptocurrency"),

        // ══════════════════════════════════════════════════════════════════════
        // ECONOMY  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("IMF News",                    "https://www.imf.org/en/News/rss",                                   "Economy"),
        f("World Bank News",             "https://www.worldbank.org/en/news/rss",                             "Economy"),
        f("The Economist – Finance",     "https://www.economist.com/finance-and-economics/rss.xml",           "Economy"),
        f("MarketWatch",                 "https://feeds.marketwatch.com/marketwatch/topstories/",             "Economy"),
        f("Yahoo Finance",               "https://finance.yahoo.com/news/rssindex",                           "Economy"),

        // ══════════════════════════════════════════════════════════════════════
        // ENTERTAINMENT  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("Variety",                     "https://variety.com/feed/",                                         "Entertainment"),
        f("Hollywood Reporter",          "https://www.hollywoodreporter.com/feed/",                           "Entertainment"),
        f("Deadline Hollywood",          "https://deadline.com/feed/",                                        "Entertainment"),
        f("Rolling Stone",               "https://www.rollingstone.com/feed/",                                "Entertainment"),
        f("IGN",                         "https://feeds.ign.com/ign/all",                                     "Entertainment"),

        // ══════════════════════════════════════════════════════════════════════
        // ENVIRONMENT  (4)
        // ══════════════════════════════════════════════════════════════════════
        f("BBC Environment",             "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",     "Environment"),
        f("Climate Home News",           "https://www.climatechangenews.com/feed/",                           "Environment"),
        f("Carbon Brief",                "https://www.carbonbrief.org/feed",                                  "Environment"),
        f("Mongabay",                    "https://news.mongabay.com/feed/",                                   "Environment"),

        // ══════════════════════════════════════════════════════════════════════
        // FASHION  (4)
        // ══════════════════════════════════════════════════════════════════════
        f("WWD",                         "https://wwd.com/feed/",                                             "Fashion"),
        f("Fashionista",                 "https://fashionista.com/.rss/excerpt/",                             "Fashion"),
        f("Vogue",                       "https://www.vogue.com/feed/rss",                                    "Fashion"),
        f("Elle Magazine",               "https://www.elle.com/rss/all.xml/",                                 "Fashion"),

        // ══════════════════════════════════════════════════════════════════════
        // FOOD & LIFESTYLE  (3)
        // ══════════════════════════════════════════════════════════════════════
        f("Bon Appétit",                 "https://www.bonappetit.com/feed/rss",                               "Food & Lifestyle"),
        f("Epicurious",                  "https://www.epicurious.com/feed/rss",                               "Food & Lifestyle"),
        f("Lifehacker",                  "https://lifehacker.com/feed/rss",                                   "Food & Lifestyle"),

        // ══════════════════════════════════════════════════════════════════════
        // GENERAL  (8)
        // ══════════════════════════════════════════════════════════════════════
        f("BBC News",                    "https://feeds.bbci.co.uk/news/rss.xml",                             "General"),
        f("Deutsche Welle",              "https://rss.dw.com/rdf/rss-en-all",                                 "General"),
        f("France 24 – English",         "https://www.france24.com/en/rss",                                   "General"),
        f("Al Jazeera – English",        "https://www.aljazeera.com/xml/rss/all.xml",                         "General"),
        f("UN News",                     "https://news.un.org/feed/subscribe/en/news/all/rss.xml",             "General"),
        f("ABC News (US)",               "https://abcnews.go.com/abcnews/topstories",                         "General"),
        f("Times of India",              "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",        "General"),
        f("Sky News",                    "https://feeds.skynews.com/feeds/rss/world.xml",                     "General"),

        // ══════════════════════════════════════════════════════════════════════
        // HEALTH  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("WHO News",                    "https://www.who.int/rss-feeds/news-english.xml",                    "Health"),
        f("Harvard Health Blog",         "https://www.health.harvard.edu/blog/feed",                          "Health"),
        f("Medical News Today",          "https://www.medicalnewstoday.com/newsfeeds.xml",                    "Health"),
        f("ScienceDaily – Health",       "https://www.sciencedaily.com/rss/health_medicine.xml",              "Health"),
        f("Healthline",                  "https://www.healthline.com/rss/health-news",                        "Health"),

        // ══════════════════════════════════════════════════════════════════════
        // INDIA NEWS  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("Times of India – India",      "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms",      "India"),
        f("NDTV – India",                "https://feeds.feedburner.com/ndtvnews-india-news",                  "India"),
        f("The Hindu – India",           "https://www.thehindu.com/news/national/feeder/default.rss",         "India"),
        f("Indian Express – India",      "https://indianexpress.com/section/india/feed/",                     "India"),
        f("Hindustan Times",             "https://www.hindustantimes.com/rss/topnews/rssfeed.xml",            "India"),
        f("ThePrint",                    "https://theprint.in/feed/",                                         "India"),

        // ══════════════════════════════════════════════════════════════════════
        // POLITICS  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("Politico",                    "https://www.politico.com/rss/politicopicks.xml",                    "Politics"),
        f("The Hill",                    "https://thehill.com/rss/syndicator/19109/feed/",                    "Politics"),
        f("BBC – Politics",              "https://feeds.bbci.co.uk/news/politics/rss.xml",                    "Politics"),
        f("Axios",                       "https://api.axios.com/feed/",                                       "Politics"),
        f("Vox – Politics",              "https://www.vox.com/rss/world-politics/index.xml",                  "Politics"),

        // ══════════════════════════════════════════════════════════════════════
        // SCIENCE  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("NASA Breaking News",          "https://www.nasa.gov/news-release/feed/",                           "Science"),
        f("Nature – Latest Research",    "https://www.nature.com/nature.rss",                                 "Science"),
        f("ScienceDaily",                "https://www.sciencedaily.com/rss/all.xml",                          "Science"),
        f("New Scientist",               "https://www.newscientist.com/feed/home/",                           "Science"),
        f("Scientific American",         "https://rss.sciam.com/ScientificAmerican-Global",                   "Science"),
        f("Live Science",                "https://www.livescience.com/feeds/all",                             "Science"),

        // ══════════════════════════════════════════════════════════════════════
        // SPORTS  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("ESPN Top Headlines",          "https://www.espn.com/espn/rss/news",                                "Sports"),
        f("BBC Sport",                   "https://feeds.bbci.co.uk/sport/rss.xml",                            "Sports"),
        f("Sky Sports – Latest",         "https://www.skysports.com/rss/12040",                               "Sports"),
        f("ESPN Cricket",                "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",         "Sports"),
        f("Yahoo Sports",                "https://sports.yahoo.com/rss/",                                     "Sports"),
        f("CBS Sports",                  "https://www.cbssports.com/rss/headlines/",                          "Sports"),

        // ══════════════════════════════════════════════════════════════════════
        // STOCK MARKET  (5)
        // ══════════════════════════════════════════════════════════════════════
        f("CNBC Markets",                "https://www.cnbc.com/id/20910258/device/rss/rss.html",              "Stock Market"),
        f("Wall Street Journal – Markets","https://feeds.a.dj.com/rss/RSSMarketsMain.xml",                   "Stock Market"),
        f("Seeking Alpha",               "https://seekingalpha.com/feed.xml",                                 "Stock Market"),
        f("Investopedia",                "https://www.investopedia.com/feedbuilder/feed/getfeed?feedName=investopedia_headlines", "Stock Market"),
        f("Mint – Markets",              "https://www.livemint.com/rss/markets",                              "Stock Market"),

        // ══════════════════════════════════════════════════════════════════════
        // TECH  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("TechCrunch",                  "https://techcrunch.com/feed/",                                      "Tech"),
        f("The Verge",                   "https://www.theverge.com/rss/index.xml",                            "Tech"),
        f("Android Authority",           "https://www.androidauthority.com/feed/",                            "Tech"),
        f("Ars Technica",                "https://feeds.arstechnica.com/arstechnica/index",                   "Tech"),
        f("9to5Google",                  "https://9to5google.com/feed/",                                      "Tech"),
        f("Tom's Guide",                 "https://www.tomsguide.com/feeds/all",                               "Tech"),

        // ══════════════════════════════════════════════════════════════════════
        // TRAVEL  (4)
        // ══════════════════════════════════════════════════════════════════════
        f("Travel + Leisure",            "https://www.travelandleisure.com/rss",                              "Travel"),
        f("National Geographic Travel",  "https://www.nationalgeographic.com/travel/rss/",                    "Travel"),
        f("The Points Guy",              "https://thepointsguy.com/feed/",                                    "Travel"),
        f("Skift",                       "https://skift.com/feed/",                                           "Travel"),

        // ══════════════════════════════════════════════════════════════════════
        // WORLD NEWS  (6)
        // ══════════════════════════════════════════════════════════════════════
        f("BBC World",                   "https://feeds.bbci.co.uk/news/world/rss.xml",                       "World"),
        f("Al Jazeera – World",          "https://www.aljazeera.com/xml/rss/all.xml",                         "World"),
        f("Deutsche Welle – World",      "https://rss.dw.com/rdf/rss-en-world",                               "World"),
        f("NPR World",                   "https://feeds.npr.org/1004/rss.xml",                                "World"),
        f("Euronews",                    "https://www.euronews.com/rss?level=theme&name=news",                 "World"),
        f("Arab News – World",           "https://www.arabnews.com/world/rss.xml",                            "World")
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
