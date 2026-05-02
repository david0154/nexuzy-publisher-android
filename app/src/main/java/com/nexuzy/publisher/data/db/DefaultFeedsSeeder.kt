package com.nexuzy.publisher.data.db

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.model.RssFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DefaultFeedsSeeder
 *
 * Seeds the Room DB with 100+ pre-configured RSS feeds on first install
 * (when rss_feeds table is empty).
 *
 * Geographic focus:
 *   USA + Europe (Western)  : ~50%  — local + international news
 *   Asia + Middle East       : ~20%
 *   North America + China    : ~10%
 *   Global / International   : ~20%
 *
 * Categories (14 total — matching jiveglow.com taxonomy):
 *   AI & Machine Learning, Business, Career, Cryptocurrency,
 *   Economy, Fashion, General, Health, Politics, Science,
 *   Sports, Stock Market, Tech, Technology, Travel
 *
 * jiveglow.com feeds are included as primary sources for every category.
 *
 * Usage — call once after DB is ready:
 *   DefaultFeedsSeeder.seedIfEmpty(context)
 */
object DefaultFeedsSeeder {

    private const val TAG = "DefaultFeedsSeeder"

    suspend fun seedIfEmpty(context: Context) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val count = db.rssFeedDao().getCount()
            if (count > 0) {
                Log.d(TAG, "DB already has $count feeds — skipping seed")
                return@withContext
            }
            val feeds = buildDefaultFeeds()
            feeds.forEach { db.rssFeedDao().insert(it) }
            Log.i(TAG, "✅ Seeded ${feeds.size} default RSS feeds")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feed list — 14 categories, 100+ feeds
    // Format: feed(name, url, category)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildDefaultFeeds(): List<RssFeed> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        // AI & MACHINE LEARNING
        // ══════════════════════════════════════════════════════════════════════
        // JiveGlow primary
        feed("JiveGlow AI & Machine Learning", "https://jiveglow.com/category/ai-machine-learning/feed/", "AI & Machine Learning"),
        // USA / Europe (50%)
        feed("MIT Technology Review – AI",   "https://www.technologyreview.com/feed/",                        "AI & Machine Learning"),
        feed("VentureBeat AI",               "https://venturebeat.com/category/ai/feed/",                    "AI & Machine Learning"),
        feed("The Verge – AI",               "https://www.theverge.com/ai-artificial-intelligence/rss/index.xml", "AI & Machine Learning"),
        feed("Wired – AI",                   "https://www.wired.com/feed/tag/artificial-intelligence/rss",   "AI & Machine Learning"),
        feed("DeepMind Blog",                "https://deepmind.google/blog/rss.xml",                         "AI & Machine Learning"),
        feed("OpenAI Blog",                  "https://openai.com/blog/rss/",                                 "AI & Machine Learning"),
        feed("TechCrunch AI",                "https://techcrunch.com/category/artificial-intelligence/feed/","AI & Machine Learning"),
        // Asia / Global
        feed("NVIDIA AI Blog",               "https://blogs.nvidia.com/feed/",                               "AI & Machine Learning"),
        feed("Google AI Blog",               "https://ai.googleblog.com/atom.xml",                           "AI & Machine Learning"),
        feed("Analytics Vidhya",             "https://www.analyticsvidhya.com/blog/feed/",                   "AI & Machine Learning"),

        // ══════════════════════════════════════════════════════════════════════
        // BUSINESS
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Business",            "https://jiveglow.com/category/business/feed/",                 "Business"),
        // USA / Europe (50%)
        feed("BBC Business",                 "https://feeds.bbci.co.uk/news/business/rss.xml",               "Business"),
        feed("Reuters Business",             "https://feeds.reuters.com/reuters/businessNews",               "Business"),
        feed("Financial Times",              "https://www.ft.com/rss/home",                                  "Business"),
        feed("Forbes",                       "https://www.forbes.com/real-time/feed2/",                      "Business"),
        feed("Business Insider",             "https://feeds.businessinsider.com/custom/all",                 "Business"),
        feed("The Economist",                "https://www.economist.com/business/rss.xml",                   "Business"),
        feed("Fast Company",                 "https://www.fastcompany.com/rss.xml",                          "Business"),
        feed("Inc. Magazine",                "https://www.inc.com/rss",                                      "Business"),
        // Asia / Middle East
        feed("Nikkei Asia – Business",       "https://asia.nikkei.com/rss/feed/business",                   "Business"),
        feed("Economic Times",               "https://economictimes.indiatimes.com/rssfeedstopstories.cms", "Business"),

        // ══════════════════════════════════════════════════════════════════════
        // CAREER
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Career",              "https://jiveglow.com/category/career/feed/",                   "Career"),
        feed("LinkedIn Talent Blog",         "https://business.linkedin.com/talent-solutions/blog/rss",      "Career"),
        feed("Harvard Business Review",      "https://hbr.org/subscribe?email=rss",                         "Career"),
        feed("Forbes Careers",               "https://www.forbes.com/careers/feed/",                        "Career"),
        feed("Glassdoor Blog",               "https://www.glassdoor.com/blog/feed/",                         "Career"),
        feed("Fast Company Work Life",       "https://www.fastcompany.com/work-life/rss",                    "Career"),
        feed("Indeed Career Guide",          "https://www.indeed.com/career-advice/rss",                     "Career"),
        feed("The Muse",                     "https://www.themuse.com/advice/rss",                           "Career"),

        // ══════════════════════════════════════════════════════════════════════
        // CRYPTOCURRENCY
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Cryptocurrency",      "https://jiveglow.com/category/cryptocurrency/feed/",           "Cryptocurrency"),
        feed("CoinDesk",                     "https://www.coindesk.com/arc/outboundfeeds/rss/",              "Cryptocurrency"),
        feed("CoinTelegraph",                "https://cointelegraph.com/rss",                               "Cryptocurrency"),
        feed("Decrypt",                      "https://decrypt.co/feed",                                     "Cryptocurrency"),
        feed("The Block",                    "https://www.theblock.co/rss.xml",                             "Cryptocurrency"),
        feed("Bitcoin Magazine",             "https://bitcoinmagazine.com/feed",                            "Cryptocurrency"),
        feed("CryptoSlate",                  "https://cryptoslate.com/feed/",                               "Cryptocurrency"),
        feed("Crypto Briefing",              "https://cryptobriefing.com/feed/",                            "Cryptocurrency"),
        // Asia / Global
        feed("Binance Blog",                 "https://www.binance.com/en/blog/rss.xml",                     "Cryptocurrency"),

        // ══════════════════════════════════════════════════════════════════════
        // ECONOMY
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Economy",             "https://jiveglow.com/category/economy/feed/",                  "Economy"),
        feed("Reuters Economy",              "https://feeds.reuters.com/reuters/economyNews",                "Economy"),
        feed("BBC Economy",                  "https://feeds.bbci.co.uk/news/business/economy/rss.xml",       "Economy"),
        feed("IMF Blog",                     "https://www.imf.org/en/Blogs/rss",                             "Economy"),
        feed("World Bank Blog",              "https://blogs.worldbank.org/rss.xml",                          "Economy"),
        feed("The Economist – Economy",      "https://www.economist.com/finance-and-economics/rss.xml",     "Economy"),
        feed("Brookings Institution",        "https://www.brookings.edu/feed/",                              "Economy"),
        feed("Project Syndicate – Economy",  "https://www.project-syndicate.org/rss/section/economy",      "Economy"),
        // Asia / Middle East
        feed("Arab News – Economy",          "https://www.arabnews.com/economy/rss.xml",                    "Economy"),
        feed("South China Morning Post – Economy", "https://www.scmp.com/rss/318210/feed",                  "Economy"),

        // ══════════════════════════════════════════════════════════════════════
        // FASHION
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Fashion",             "https://jiveglow.com/category/fashion/feed/",                  "Fashion"),
        feed("Vogue",                        "https://www.vogue.com/feed/rss",                               "Fashion"),
        feed("Harper's Bazaar",              "https://www.harpersbazaar.com/rss/all.xml/",                   "Fashion"),
        feed("WWD",                          "https://wwd.com/feed/",                                        "Fashion"),
        feed("Business of Fashion",          "https://www.businessoffashion.com/rss",                       "Fashion"),
        feed("Elle",                         "https://www.elle.com/rss/all.xml/",                            "Fashion"),
        feed("InStyle",                      "https://www.instyle.com/rss/all.xml",                          "Fashion"),
        // Europe
        feed("Dezeen – Fashion",             "https://www.dezeen.com/fashion/feed/",                        "Fashion"),

        // ══════════════════════════════════════════════════════════════════════
        // GENERAL
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow General",             "https://jiveglow.com/category/general/feed/",                  "General"),
        // USA / Europe (50%)
        feed("BBC Top Stories",              "https://feeds.bbci.co.uk/news/rss.xml",                       "General"),
        feed("Reuters Top News",             "https://feeds.reuters.com/reuters/topNews",                    "General"),
        feed("AP News",                      "https://rsshub.app/apnews/topics/apf-topnews",                 "General"),
        feed("NPR News",                     "https://feeds.npr.org/1001/rss.xml",                           "General"),
        feed("The Guardian",                 "https://www.theguardian.com/world/rss",                       "General"),
        feed("CNN Top Stories",              "https://rss.cnn.com/rss/edition.rss",                         "General"),
        feed("NBC News",                     "https://feeds.nbcnews.com/nbcnews/public/news",                "General"),
        feed("Sky News",                     "https://feeds.skynews.com/feeds/rss/home.xml",                "General"),
        feed("DW News (Germany)",            "https://rss.dw.com/rdf/rss-en-all",                           "General"),
        feed("France 24",                    "https://www.france24.com/en/rss",                             "General"),
        // Asia / Middle East
        feed("Al Jazeera",                   "https://www.aljazeera.com/xml/rss/all.xml",                   "General"),
        feed("Times of India – Top",         "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",  "General"),
        // North America
        feed("CBC News",                     "https://www.cbc.ca/cmlink/rss-topstories",                    "General"),
        // China
        feed("Xinhua – World",               "http://www.xinhuanet.com/english/rss/worldrss.xml",           "General"),
        // Global
        feed("UN News",                      "https://news.un.org/feed/subscribe/en/news/all/rss.xml",      "General"),

        // ══════════════════════════════════════════════════════════════════════
        // HEALTH
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Health",              "https://jiveglow.com/category/health/feed/",                   "Health"),
        feed("WHO News",                     "https://www.who.int/rss-feeds/news-english.xml",               "Health"),
        feed("WebMD",                        "https://rssfeeds.webmd.com/rss/rss.aspx?RSSSource=RSS_PUBLIC","Health"),
        feed("Healthline",                   "https://www.healthline.com/health-news/rss",                   "Health"),
        feed("Medical News Today",           "https://www.medicalnewstoday.com/newsfeeds.rss",               "Health"),
        feed("Reuters Health",               "https://feeds.reuters.com/reuters/healthNews",                 "Health"),
        feed("BBC Health",                   "https://feeds.bbci.co.uk/news/health/rss.xml",                 "Health"),
        feed("Harvard Health Blog",          "https://www.health.harvard.edu/blog/feed",                     "Health"),
        feed("CDC Newsroom",                 "https://tools.cdc.gov/api/v2/resources/media/316422.rss",      "Health"),
        // Asia
        feed("Times of India – Health",     "https://timesofindia.indiatimes.com/rssfeeds/3908999.cms",    "Health"),

        // ══════════════════════════════════════════════════════════════════════
        // POLITICS
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Politics",            "https://jiveglow.com/category/politics/feed/",                 "Politics"),
        // USA (heavy focus)
        feed("Politico",                     "https://www.politico.com/rss/politicopicks.xml",               "Politics"),
        feed("The Hill",                     "https://thehill.com/news/feed/",                               "Politics"),
        feed("Washington Post – Politics",   "https://feeds.washingtonpost.com/rss/politics",               "Politics"),
        feed("New York Times – Politics",    "https://rss.nytimes.com/services/xml/rss/nyt/Politics.xml",   "Politics"),
        feed("NPR Politics",                 "https://feeds.npr.org/1014/rss.xml",                           "Politics"),
        // Europe
        feed("BBC Politics",                 "https://feeds.bbci.co.uk/news/politics/rss.xml",               "Politics"),
        feed("EUobserver",                   "https://euobserver.com/rss.xml",                               "Politics"),
        feed("Politico Europe",              "https://www.politico.eu/feed/",                                "Politics"),
        // Asia / Middle East
        feed("Arab News – Politics",         "https://www.arabnews.com/politics/rss.xml",                   "Politics"),
        feed("South China Morning Post – Politics", "https://www.scmp.com/rss/91/feed",                    "Politics"),
        // Canada
        feed("CBC Politics",                 "https://www.cbc.ca/cmlink/rss-politics",                      "Politics"),

        // ══════════════════════════════════════════════════════════════════════
        // SCIENCE
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Science",             "https://jiveglow.com/category/science/feed/",                  "Science"),
        feed("NASA Breaking News",           "https://www.nasa.gov/news-release/feed/",                      "Science"),
        feed("New Scientist",                "https://www.newscientist.com/subject/technology/feed/",        "Science"),
        feed("Science Daily",                "https://www.sciencedaily.com/rss/all.xml",                     "Science"),
        feed("Nature News",                  "https://www.nature.com/nature.rss",                            "Science"),
        feed("Scientific American",          "https://www.scientificamerican.com/platform/morganlefay/v1/rss/feed/", "Science"),
        feed("BBC Science",                  "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml","Science"),
        feed("ESA Space News",               "https://www.esa.int/rssfeed/our_activities/space_science",    "Science"),
        feed("EurekAlert",                   "https://www.eurekalert.org/rss.xml",                           "Science"),
        // Asia
        feed("ISRO News",                    "https://www.isro.gov.in/rss/newsarchive.rss",                  "Science"),

        // ══════════════════════════════════════════════════════════════════════
        // SPORTS
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Sports",              "https://jiveglow.com/category/sports/feed/",                   "Sports"),
        // USA / Europe (50%)
        feed("ESPN",                         "https://www.espn.com/espn/rss/news",                           "Sports"),
        feed("BBC Sport",                    "https://feeds.bbci.co.uk/sport/rss.xml",                       "Sports"),
        feed("Sky Sports",                   "https://www.skysports.com/rss/12040",                         "Sports"),
        feed("CBS Sports",                   "https://www.cbssports.com/rss/headlines",                      "Sports"),
        feed("The Athletic",                 "https://theathletic.com/rss/feed",                             "Sports"),
        feed("Bleacher Report",              "https://bleacherreport.com/articles/feed",                     "Sports"),
        feed("Goal.com",                     "https://www.goal.com/feeds/en/news",                           "Sports"),
        // Asia / Middle East
        feed("ESPN Cricinfo",                "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",   "Sports"),
        feed("Sport360 (Middle East)",       "https://sport360.com/feed/",                                   "Sports"),
        // Canada
        feed("TSN",                          "https://www.tsn.ca/rss/tsn-top-stories",                      "Sports"),

        // ══════════════════════════════════════════════════════════════════════
        // STOCK MARKET
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Stock Market",        "https://jiveglow.com/category/stock-market/feed/",             "Stock Market"),
        feed("MarketWatch",                  "https://feeds.marketwatch.com/marketwatch/topstories",         "Stock Market"),
        feed("Yahoo Finance",                "https://finance.yahoo.com/news/rssindex",                      "Stock Market"),
        feed("CNBC Markets",                 "https://www.cnbc.com/id/10000664/device/rss/rss.html",         "Stock Market"),
        feed("Seeking Alpha",                "https://seekingalpha.com/market_currents.xml",                 "Stock Market"),
        feed("Investopedia",                 "https://www.investopedia.com/feeds/all.aspx",                  "Stock Market"),
        feed("Bloomberg Markets",            "https://feeds.bloomberg.com/markets/news.rss",                 "Stock Market"),
        feed("Wall Street Journal – Markets","https://feeds.a.dj.com/rss/RSSMarketsMain.xml",               "Stock Market"),
        // Asia
        feed("Nikkei Asia – Markets",        "https://asia.nikkei.com/rss/feed/markets",                    "Stock Market"),
        feed("Economic Times – Markets",     "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms", "Stock Market"),

        // ══════════════════════════════════════════════════════════════════════
        // TECH
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Tech",                "https://jiveglow.com/category/tech/feed/",                     "Tech"),
        feed("TechCrunch",                   "https://techcrunch.com/feed/",                                 "Tech"),
        feed("The Verge",                    "https://www.theverge.com/rss/index.xml",                       "Tech"),
        feed("Engadget",                     "https://www.engadget.com/rss.xml",                             "Tech"),
        feed("Ars Technica",                 "https://feeds.arstechnica.com/arstechnica/index",              "Tech"),
        feed("ZDNet",                        "https://www.zdnet.com/news/rss.xml",                           "Tech"),
        feed("Gizmodo",                      "https://gizmodo.com/rss",                                      "Tech"),
        feed("9to5Google",                   "https://9to5google.com/feed/",                                 "Tech"),
        feed("9to5Mac",                      "https://9to5mac.com/feed/",                                    "Tech"),
        feed("Android Authority",            "https://www.androidauthority.com/feed/",                       "Tech"),
        // Europe
        feed("Heise Online (Germany)",       "https://www.heise.de/rss/heise-atom.xml",                     "Tech"),
        // Asia
        feed("Tech in Asia",                 "https://www.techinasia.com/feed",                             "Tech"),

        // ══════════════════════════════════════════════════════════════════════
        // TECHNOLOGY
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Technology",          "https://jiveglow.com/category/technology/feed/",               "Technology"),
        feed("MIT Technology Review",        "https://www.technologyreview.com/feed/",                       "Technology"),
        feed("Wired",                        "https://www.wired.com/feed/rss",                               "Technology"),
        feed("IEEE Spectrum",                "https://spectrum.ieee.org/feeds/feed.rss",                    "Technology"),
        feed("Tech Republic",                "https://www.techrepublic.com/rssfeeds/articles/",              "Technology"),
        feed("InfoQ",                        "https://feed.infoq.com/",                                      "Technology"),
        feed("Hackernoon",                   "https://hackernoon.com/feed",                                  "Technology"),
        feed("Dev.to",                       "https://dev.to/feed",                                          "Technology"),
        feed("GitHub Blog",                  "https://github.blog/feed/",                                    "Technology"),
        // Europe
        feed("The Next Web",                 "https://thenextweb.com/feed/",                                 "Technology"),
        // Asia / China
        feed("36Kr (China Tech EN)",         "https://36kr.com/feed",                                       "Technology"),
        feed("KrASIA",                       "https://kr.asia/feed",                                         "Technology"),

        // ══════════════════════════════════════════════════════════════════════
        // TRAVEL
        // ══════════════════════════════════════════════════════════════════════
        feed("JiveGlow Travel",              "https://jiveglow.com/category/travel/feed/",                   "Travel"),
        feed("Lonely Planet",                "https://www.lonelyplanet.com/news/feed",                       "Travel"),
        feed("Travel + Leisure",             "https://www.travelandleisure.com/feeds/all",                  "Travel"),
        feed("Condé Nast Traveler",          "https://www.cntraveler.com/feed/rss",                         "Travel"),
        feed("National Geographic Travel",   "https://www.nationalgeographic.com/travel/rss/",              "Travel"),
        feed("Skift",                        "https://skift.com/feed/",                                      "Travel"),
        feed("The Points Guy",               "https://thepointsguy.com/feed/",                               "Travel"),
        // Europe
        feed("Eurostar Travel Blog",         "https://www.eurostar.com/rss.xml",                            "Travel"),
        // Asia / Middle East
        feed("Travel Wire Asia",             "https://www.travelwireasia.com/feed/",                        "Travel")
    )

    private fun feed(name: String, url: String, category: String) = RssFeed(
        name     = name,
        url      = url,
        category = category,
        isActive = true
    )
}
