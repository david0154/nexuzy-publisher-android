package com.nexuzy.publisher.data.seed

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed

/**
 * Seeds the RSS feed database with a comprehensive set of international news sources.
 *
 * Regional focus:
 *   - USA + Europe (Western)  : ~50% of feeds — local + international news
 *   - North America (Canada)  : ~10%
 *   - Asia                    : ~10%
 *   - Middle East             : ~20%
 *   - China                   : ~10%
 *   - Other international     : balance
 *
 * Call seedIfEmpty() once on app start — it now also fills in any
 * default feeds that were deleted or never seeded (URL-based dedup).
 * Users can delete ANY feed (including defaults) and add their own custom URLs.
 */
object DefaultFeedsSeeder {

    private const val TAG = "DefaultFeedsSeeder"

    suspend fun seedIfEmpty(context: Context) {
        val db  = AppDatabase.getDatabase(context)
        val dao = db.rssFeedDao()

        val existingUrls = dao.getAllOnce().map { it.url }.toSet()

        if (existingUrls.isEmpty()) {
            // Fresh install — insert all default feeds
            val feeds = buildFeedList()
            feeds.forEach { dao.insert(it) }
            Log.i(TAG, "Seeded ${feeds.size} RSS feeds into DB.")
        } else {
            // Re-seed only missing default feeds (handles partial deletes / upgrades)
            val missing = buildFeedList().filter { it.url !in existingUrls }
            if (missing.isNotEmpty()) {
                missing.forEach { dao.insert(it) }
                Log.i(TAG, "Re-seeded ${missing.size} missing default feeds.")
            } else {
                Log.i(TAG, "DB already has all default feeds — skipping seed.")
            }
        }
    }

    private fun buildFeedList(): List<RssFeed> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        // AI & MACHINE LEARNING
        // ══════════════════════════════════════════════════════════════════════
        f("MIT Technology Review – AI",       "https://www.technologyreview.com/feed/", "AI & Machine Learning"),
        f("VentureBeat – AI",                 "https://venturebeat.com/category/ai/feed/", "AI & Machine Learning"),
        f("The Verge – AI",                   "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml", "AI & Machine Learning"),
        f("Wired – AI",                       "https://www.wired.com/feed/tag/artificial-intelligence/rss", "AI & Machine Learning"),
        f("Google AI Blog",                   "https://blog.research.google/feeds/posts/default?alt=rss", "AI & Machine Learning"),
        f("DeepMind Blog",                    "https://deepmind.google/blog/rss.xml", "AI & Machine Learning"),
        f("Towards Data Science",             "https://towardsdatascience.com/feed", "AI & Machine Learning"),
        f("AI News",                          "https://www.artificialintelligence-news.com/feed/", "AI & Machine Learning"),
        f("OpenAI Blog",                      "https://openai.com/news/rss.xml", "AI & Machine Learning"),
        f("Ars Technica – AI",                "https://feeds.arstechnica.com/arstechnica/technology-lab", "AI & Machine Learning"),

        // ══════════════════════════════════════════════════════════════════════
        // BUSINESS
        // ══════════════════════════════════════════════════════════════════════
        f("BBC Business",                     "https://feeds.bbci.co.uk/news/business/rss.xml", "Business"),
        f("Reuters Business",                 "https://feeds.reuters.com/reuters/businessNews", "Business"),
        f("Financial Times",                  "https://www.ft.com/rss/home", "Business"),
        f("Forbes",                           "https://www.forbes.com/business/feed/", "Business"),
        f("Bloomberg Business",               "https://feeds.bloomberg.com/business/news.rss", "Business"),
        f("The Economist",                    "https://www.economist.com/business/rss.xml", "Business"),
        f("Harvard Business Review",          "https://hbr.org/stories_rss.xml", "Business"),
        f("Nikkei Asia – Business",           "https://asia.nikkei.com/rss/feed/index", "Business"),
        f("Arabian Business",                 "https://www.arabianbusiness.com/rss", "Business"),
        f("CNBC Business",                    "https://www.cnbc.com/id/10001147/device/rss/rss.html", "Business"),

        // ══════════════════════════════════════════════════════════════════════
        // CAREER
        // ══════════════════════════════════════════════════════════════════════
        f("LinkedIn Talent Blog",             "https://www.linkedin.com/blog/talent/rss", "Career"),
        f("Harvard Business Review – Career", "https://hbr.org/stories_rss.xml", "Career"),
        f("The Muse",                         "https://www.themuse.com/advice/rss", "Career"),
        f("Fast Company – Work Life",         "https://www.fastcompany.com/work-life/rss", "Career"),
        f("Indeed Career Guide",              "https://www.indeed.com/career-advice/rss", "Career"),
        f("Glassdoor Blog",                   "https://www.glassdoor.com/blog/feed/", "Career"),
        f("Monster Career Advice",            "https://www.monster.com/career-advice/feed", "Career"),

        // ══════════════════════════════════════════════════════════════════════
        // CRYPTOCURRENCY
        // ══════════════════════════════════════════════════════════════════════
        f("CoinDesk",                         "https://www.coindesk.com/arc/outboundfeeds/rss/", "Cryptocurrency"),
        f("CoinTelegraph",                    "https://cointelegraph.com/rss", "Cryptocurrency"),
        f("Decrypt",                          "https://decrypt.co/feed", "Cryptocurrency"),
        f("The Block",                        "https://www.theblock.co/rss.xml", "Cryptocurrency"),
        f("Bitcoin Magazine",                 "https://bitcoinmagazine.com/feed", "Cryptocurrency"),
        f("CryptoNews",                       "https://cryptonews.com/news/feed/", "Cryptocurrency"),
        f("NewsBTC",                          "https://www.newsbtc.com/feed/", "Cryptocurrency"),
        f("BeInCrypto",                       "https://beincrypto.com/feed/", "Cryptocurrency"),

        // ══════════════════════════════════════════════════════════════════════
        // ECONOMY
        // ══════════════════════════════════════════════════════════════════════
        f("Reuters Economy",                  "https://feeds.reuters.com/reuters/businessNews", "Economy"),
        f("IMF News",                         "https://www.imf.org/en/News/rss", "Economy"),
        f("World Bank News",                  "https://www.worldbank.org/en/news/rss", "Economy"),
        f("The Economist – Finance",          "https://www.economist.com/finance-and-economics/rss.xml", "Economy"),
        f("Bloomberg Economics",              "https://feeds.bloomberg.com/economics/news.rss", "Economy"),
        f("AP Business",                      "https://rsshub.app/ap/topics/business", "Economy"),
        f("Arab News – Economy",              "https://www.arabnews.com/economy/rss.xml", "Economy"),
        f("South China Morning Post – Economy","https://www.scmp.com/rss/91/feed", "Economy"),
        f("Xinhua Finance",                   "http://www.xinhuanet.com/english/rss/businessrss.xml", "Economy"),

        // ══════════════════════════════════════════════════════════════════════
        // FASHION
        // ══════════════════════════════════════════════════════════════════════
        f("Vogue",                            "https://www.vogue.com/feed/rss", "Fashion"),
        f("Harper's Bazaar",                  "https://www.harpersbazaar.com/rss/all.xml/", "Fashion"),
        f("WWD",                              "https://wwd.com/feed/", "Fashion"),
        f("Elle Magazine",                    "https://www.elle.com/rss/all.xml/", "Fashion"),
        f("Fashionista",                      "https://fashionista.com/.rss/excerpt/", "Fashion"),
        f("Business of Fashion",              "https://www.businessoffashion.com/rss", "Fashion"),
        f("Dezeen – Fashion",                 "https://www.dezeen.com/fashion/feed/", "Fashion"),

        // ══════════════════════════════════════════════════════════════════════
        // GENERAL
        // ══════════════════════════════════════════════════════════════════════
        f("BBC News",                         "https://feeds.bbci.co.uk/news/rss.xml", "General"),
        f("Reuters Top News",                 "https://feeds.reuters.com/reuters/topNews", "General"),
        f("AP Top News",                      "https://rsshub.app/ap/topics/apf-topnews", "General"),
        f("The Guardian",                     "https://www.theguardian.com/world/rss", "General"),
        f("CNN Top Stories",                  "http://rss.cnn.com/rss/edition.rss", "General"),
        f("NPR News",                         "https://feeds.npr.org/1001/rss.xml", "General"),
        f("Deutsche Welle",                   "https://rss.dw.com/rdf/rss-en-all", "General"),
        f("France 24 – English",              "https://www.france24.com/en/rss", "General"),
        f("Al Jazeera – English",             "https://www.aljazeera.com/xml/rss/all.xml", "General"),
        f("UN News",                          "https://news.un.org/feed/subscribe/en/news/all/rss.xml", "General"),
        f("Euronews",                         "https://www.euronews.com/rss?level=theme&name=news", "General"),
        f("The Independent",                  "https://www.independent.co.uk/news/rss", "General"),
        f("ABC News (US)",                    "https://abcnews.go.com/abcnews/topstories", "General"),
        f("CBS News",                         "https://www.cbsnews.com/latest/rss/main", "General"),
        f("NBC News",                         "https://feeds.nbcnews.com/nbcnews/public/news", "General"),

        // ══════════════════════════════════════════════════════════════════════
        // HEALTH
        // ══════════════════════════════════════════════════════════════════════
        f("WHO News",                         "https://www.who.int/rss-feeds/news-english.xml", "Health"),
        f("WebMD Health",                     "https://rssfeeds.webmd.com/rss/rss.aspx?RSSSource=RSS_PUBLIC", "Health"),
        f("Healthline News",                  "https://www.healthline.com/rss/news", "Health"),
        f("Harvard Health Blog",              "https://www.health.harvard.edu/blog/feed", "Health"),
        f("CDC Newsroom",                     "https://tools.cdc.gov/api/v2/resources/media/132608.rss", "Health"),
        f("NHS News",                         "https://www.nhs.uk/news/rss.xml", "Health"),
        f("Medical News Today",               "https://www.medicalnewstoday.com/newsfeeds.xml", "Health"),
        f("ScienceDaily – Health",            "https://www.sciencedaily.com/rss/health_medicine.xml", "Health"),
        f("Al Jazeera – Health",              "https://www.aljazeera.com/xml/rss/all.xml", "Health"),

        // ══════════════════════════════════════════════════════════════════════
        // POLITICS
        // ══════════════════════════════════════════════════════════════════════
        f("Politico",                         "https://www.politico.com/rss/politicopicks.xml", "Politics"),
        f("The Hill",                         "https://thehill.com/rss/syndicator/19109/feed/", "Politics"),
        f("Washington Post – Politics",       "https://feeds.washingtonpost.com/rss/politics", "Politics"),
        f("New York Times – Politics",        "https://rss.nytimes.com/services/xml/rss/nyt/Politics.xml", "Politics"),
        f("BBC – Politics",                   "https://feeds.bbci.co.uk/news/politics/rss.xml", "Politics"),
        f("EUobserver",                       "https://euobserver.com/rss", "Politics"),
        f("Arab News – Politics",             "https://www.arabnews.com/politics/rss.xml", "Politics"),
        f("South China Morning Post – Politics","https://www.scmp.com/rss/2/feed", "Politics"),
        f("The Guardian – Politics",          "https://www.theguardian.com/politics/rss", "Politics"),
        f("France 24 – Politics",             "https://www.france24.com/en/politics/rss", "Politics"),

        // ══════════════════════════════════════════════════════════════════════
        // SCIENCE
        // ══════════════════════════════════════════════════════════════════════
        f("NASA Breaking News",               "https://www.nasa.gov/news-release/feed/", "Science"),
        f("Nature – Latest Research",         "https://www.nature.com/nature.rss", "Science"),
        f("ScienceDaily",                     "https://www.sciencedaily.com/rss/all.xml", "Science"),
        f("New Scientist",                    "https://www.newscientist.com/feed/home/", "Science"),
        f("Scientific American",              "https://www.scientificamerican.com/platform/syndication/rss/", "Science"),
        f("BBC Science",                      "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "Science"),
        f("ESA News",                         "https://www.esa.int/rssfeed/Our_Activities/Space_Science", "Science"),
        f("ISRO News",                        "https://www.isro.gov.in/rss", "Science"),
        f("Phys.org",                         "https://phys.org/rss-feed/", "Science"),

        // ══════════════════════════════════════════════════════════════════════
        // SPORTS
        // ══════════════════════════════════════════════════════════════════════
        f("ESPN Top Headlines",               "https://www.espn.com/espn/rss/news", "Sports"),
        f("BBC Sport",                        "https://feeds.bbci.co.uk/sport/rss.xml", "Sports"),
        f("Sky Sports – Latest",              "https://www.skysports.com/rss/12040", "Sports"),
        f("The Guardian – Sport",             "https://www.theguardian.com/sport/rss", "Sports"),
        f("ESPN Cricket",                     "https://www.espncricinfo.com/rss/content/story/feeds/0.xml", "Sports"),
        f("Sport360 – Middle East",           "https://sport360.com/feed", "Sports"),
        f("NFL.com",                          "https://www.nfl.com/rss/rsslanding.html?contentType=news", "Sports"),
        f("NBA.com",                          "https://www.nba.com/rss/nba_rss.xml", "Sports"),
        f("Goal.com – Football",              "https://www.goal.com/feeds/en/news", "Sports"),
        f("Yahoo Sports",                     "https://sports.yahoo.com/rss/", "Sports"),

        // ══════════════════════════════════════════════════════════════════════
        // STOCK MARKET
        // ══════════════════════════════════════════════════════════════════════
        f("MarketWatch",                      "https://feeds.marketwatch.com/marketwatch/topstories/", "Stock Market"),
        f("Yahoo Finance",                    "https://finance.yahoo.com/news/rssindex", "Stock Market"),
        f("CNBC Markets",                     "https://www.cnbc.com/id/20910258/device/rss/rss.html", "Stock Market"),
        f("Bloomberg Markets",                "https://feeds.bloomberg.com/markets/news.rss", "Stock Market"),
        f("Wall Street Journal – Markets",    "https://feeds.a.dj.com/rss/RSSMarketsMain.xml", "Stock Market"),
        f("Seeking Alpha",                    "https://seekingalpha.com/feed.xml", "Stock Market"),
        f("Investopedia News",                "https://www.investopedia.com/feedbuilder/feed/getfeed?feedName=rss_articles", "Stock Market"),
        f("Nikkei – Markets",                 "https://asia.nikkei.com/rss/feed/index", "Stock Market"),
        f("Reuters – Markets",                "https://feeds.reuters.com/reuters/businessNews", "Stock Market"),

        // ══════════════════════════════════════════════════════════════════════
        // TECH
        // ══════════════════════════════════════════════════════════════════════
        f("TechCrunch",                       "https://techcrunch.com/feed/", "Tech"),
        f("The Verge",                        "https://www.theverge.com/rss/index.xml", "Tech"),
        f("Ars Technica",                     "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
        f("Android Authority",                "https://www.androidauthority.com/feed/", "Tech"),
        f("9to5Google",                       "https://9to5google.com/feed/", "Tech"),
        f("9to5Mac",                          "https://9to5mac.com/feed/", "Tech"),
        f("Engadget",                         "https://www.engadget.com/rss.xml", "Tech"),
        f("CNET",                             "https://www.cnet.com/rss/news/", "Tech"),
        f("Mashable",                         "https://mashable.com/feeds/rss/all", "Tech"),
        f("Gizmodo",                          "https://gizmodo.com/feed", "Tech"),
        f("ZDNet",                            "https://www.zdnet.com/news/rss.xml", "Tech"),
        f("Slashdot",                         "https://rss.slashdot.org/Slashdot/slashdotMain", "Tech"),
        f("Hacker News (top)",                "https://hnrss.org/frontpage", "Tech"),
        f("Dev.to",                           "https://dev.to/feed", "Tech"),
        f("GitHub Blog",                      "https://github.blog/feed/", "Tech")
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
