package com.nexuzy.publisher.data.seed

import android.content.Context
import android.util.Log
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed

/**
 * Seeds the RSS feed database with trusted, verified news sources.
 * Sources curated from FeedSpot top-rated feeds across all categories.
 *
 * REMOVED broken/slow feeds confirmed from logs:
 *   - Wired AI         → HTTP 400
 *   - Reuters feeds    → DNS failure (feeds.reuters.com dead)
 *   - Forbes           → media:thumbnail parse error
 *   - Bloomberg feeds  → media:thumbnail parse error
 *   - HBR              → HTTP 504 (timeout)
 *   - Nikkei Asia      → HTTP 404
 *   - Arabian Business → HTTP 403
 *   - Ars Technica AI  → media:credit parse error
 *   - NYTimes Politics → media:credit parse error
 *   - LinkedIn Blog    → unstable
 *   - Xinhua Finance   → HTTP (unreliable)
 *   - rsshub.app feeds → third-party proxy, unreliable
 *
 * NOTE: Articles without an image are automatically skipped in RssFeedParser.
 *
 * Call seedIfEmpty() once on app start.
 */
object DefaultFeedsSeeder {

    private const val TAG = "DefaultFeedsSeeder"

    suspend fun seedIfEmpty(context: Context) {
        val db  = AppDatabase.getDatabase(context)
        val dao = db.rssFeedDao()

        val existingUrls = dao.getAllOnce().map { it.url }.toSet()

        if (existingUrls.isEmpty()) {
            val feeds = buildFeedList()
            feeds.forEach { dao.insert(it) }
            Log.i(TAG, "Seeded ${feeds.size} RSS feeds into DB.")
        } else {
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
        f("MIT Technology Review – AI",   "https://www.technologyreview.com/feed/",                           "AI & Machine Learning"),
        f("VentureBeat – AI",             "https://venturebeat.com/category/ai/feed/",                        "AI & Machine Learning"),
        f("The Verge – AI",               "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml", "AI & Machine Learning"),
        f("Google AI Blog",               "https://blog.research.google/feeds/posts/default?alt=rss",         "AI & Machine Learning"),
        f("DeepMind Blog",                "https://deepmind.google/blog/rss.xml",                             "AI & Machine Learning"),
        f("Towards Data Science",         "https://towardsdatascience.com/feed",                              "AI & Machine Learning"),
        f("AI News",                      "https://www.artificialintelligence-news.com/feed/",                 "AI & Machine Learning"),
        f("OpenAI Blog",                  "https://openai.com/news/rss.xml",                                  "AI & Machine Learning"),
        f("Ars Technica – Tech",          "https://feeds.arstechnica.com/arstechnica/index",                   "AI & Machine Learning"),
        f("IEEE Spectrum – AI",           "https://spectrum.ieee.org/feeds/topic/artificial-intelligence.rss", "AI & Machine Learning"),

        // ══════════════════════════════════════════════════════════════════════
        // BUSINESS
        // ══════════════════════════════════════════════════════════════════════
        f("BBC Business",                 "https://feeds.bbci.co.uk/news/business/rss.xml",                   "Business"),
        f("Financial Times",              "https://www.ft.com/rss/home",                                      "Business"),
        f("The Economist – Business",     "https://www.economist.com/business/rss.xml",                       "Business"),
        f("CNBC Business",                "https://www.cnbc.com/id/10001147/device/rss/rss.html",             "Business"),
        f("Business Insider",             "https://feeds.businessinsider.com/custom/all",                     "Business"),
        f("Fast Company",                 "https://www.fastcompany.com/latest/rss",                           "Business"),
        f("Inc. Magazine",                "https://www.inc.com/rss",                                          "Business"),
        f("Economic Times",               "https://economictimes.indiatimes.com/rssfeedsdefault.cms",         "Business"),
        f("Business Standard",            "https://www.business-standard.com/rss/home_page_top_stories.rss",  "Business"),
        f("Investing.com",                "https://www.investing.com/rss/news.rss",                           "Business"),

        // ══════════════════════════════════════════════════════════════════════
        // CAREER
        // ══════════════════════════════════════════════════════════════════════
        f("Fast Company – Work Life",     "https://www.fastcompany.com/work-life/rss",                        "Career"),
        f("Glassdoor Blog",               "https://www.glassdoor.com/blog/feed/",                             "Career"),
        f("The Muse",                     "https://www.themuse.com/advice/rss",                               "Career"),
        f("Harvard Business Review",      "https://feeds.hbr.org/harvardbusiness",                            "Career"),
        f("LinkedIn Official Blog",       "https://blog.linkedin.com/feed",                                   "Career"),

        // ══════════════════════════════════════════════════════════════════════
        // CRYPTOCURRENCY
        // ══════════════════════════════════════════════════════════════════════
        f("CoinDesk",                     "https://www.coindesk.com/arc/outboundfeeds/rss/",                  "Cryptocurrency"),
        f("CoinTelegraph",                "https://cointelegraph.com/rss",                                    "Cryptocurrency"),
        f("Decrypt",                      "https://decrypt.co/feed",                                          "Cryptocurrency"),
        f("The Block",                    "https://www.theblock.co/rss.xml",                                  "Cryptocurrency"),
        f("Bitcoin Magazine",             "https://bitcoinmagazine.com/feed",                                 "Cryptocurrency"),
        f("CryptoNews",                   "https://cryptonews.com/news/feed/",                                "Cryptocurrency"),
        f("BeInCrypto",                   "https://beincrypto.com/feed/",                                     "Cryptocurrency"),

        // ══════════════════════════════════════════════════════════════════════
        // ECONOMY
        // ══════════════════════════════════════════════════════════════════════
        f("IMF News",                     "https://www.imf.org/en/News/rss",                                  "Economy"),
        f("World Bank News",              "https://www.worldbank.org/en/news/rss",                            "Economy"),
        f("The Economist – Finance",      "https://www.economist.com/finance-and-economics/rss.xml",          "Economy"),
        f("Arab News – Economy",          "https://www.arabnews.com/economy/rss.xml",                         "Economy"),
        f("South China Morning Post",     "https://www.scmp.com/rss/91/feed",                                 "Economy"),
        f("MarketWatch",                  "https://feeds.marketwatch.com/marketwatch/topstories/",            "Economy"),
        f("Yahoo Finance",                "https://finance.yahoo.com/news/rssindex",                          "Economy"),

        // ══════════════════════════════════════════════════════════════════════
        // ENTERTAINMENT
        // ══════════════════════════════════════════════════════════════════════
        f("Variety",                      "https://variety.com/feed/",                                        "Entertainment"),
        f("Hollywood Reporter",           "https://www.hollywoodreporter.com/feed/",                          "Entertainment"),
        f("Entertainment Weekly",         "https://ew.com/feed/",                                             "Entertainment"),
        f("Deadline Hollywood",           "https://deadline.com/feed/",                                       "Entertainment"),
        f("Rolling Stone",                "https://www.rollingstone.com/feed/",                               "Entertainment"),
        f("Pitchfork",                    "https://pitchfork.com/feed/feed.json",                             "Entertainment"),
        f("IGN",                          "https://feeds.ign.com/ign/all",                                    "Entertainment"),
        f("Polygon",                      "https://www.polygon.com/rss/index.xml",                            "Entertainment"),

        // ══════════════════════════════════════════════════════════════════════
        // ENVIRONMENT & CLIMATE
        // ══════════════════════════════════════════════════════════════════════
        f("BBC Environment",              "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",    "Environment"),
        f("The Guardian – Environment",   "https://www.theguardian.com/environment/rss",                      "Environment"),
        f("Climate Home News",            "https://www.climatechangenews.com/feed/",                          "Environment"),
        f("Carbon Brief",                 "https://www.carbonbrief.org/feed",                                 "Environment"),
        f("E&E News – Climate",           "https://www.eenews.net/rss/1",                                     "Environment"),
        f("Yale Environment 360",         "https://e360.yale.edu/feed",                                       "Environment"),
        f("Mongabay",                     "https://news.mongabay.com/feed/",                                  "Environment"),

        // ══════════════════════════════════════════════════════════════════════
        // FASHION
        // ══════════════════════════════════════════════════════════════════════
        f("WWD",                          "https://wwd.com/feed/",                                            "Fashion"),
        f("Fashionista",                  "https://fashionista.com/.rss/excerpt/",                            "Fashion"),
        f("Vogue",                        "https://www.vogue.com/feed/rss",                                   "Fashion"),
        f("Elle Magazine",                "https://www.elle.com/rss/all.xml/",                                "Fashion"),
        f("Harper's Bazaar",              "https://www.harpersbazaar.com/rss/all.xml/",                       "Fashion"),
        f("InStyle",                      "https://www.instyle.com/rss/all.xml",                              "Fashion"),

        // ══════════════════════════════════════════════════════════════════════
        // FOOD & LIFESTYLE
        // ══════════════════════════════════════════════════════════════════════
        f("Bon Appétit",                  "https://www.bonappetit.com/feed/rss",                              "Food & Lifestyle"),
        f("Food Network",                 "https://www.foodnetwork.com/fn-dish/feeds/fn-dish.xml",            "Food & Lifestyle"),
        f("Epicurious",                   "https://www.epicurious.com/feed/rss",                              "Food & Lifestyle"),
        f("Food52",                       "https://food52.com/blog/feed",                                     "Food & Lifestyle"),
        f("Lifehacker",                   "https://lifehacker.com/feed/rss",                                  "Food & Lifestyle"),

        // ══════════════════════════════════════════════════════════════════════
        // GENERAL
        // ══════════════════════════════════════════════════════════════════════
        f("BBC News",                     "https://feeds.bbci.co.uk/news/rss.xml",                            "General"),
        f("The Guardian",                 "https://www.theguardian.com/world/rss",                            "General"),
        f("CNN Top Stories",              "http://rss.cnn.com/rss/edition.rss",                               "General"),
        f("NPR News",                     "https://feeds.npr.org/1001/rss.xml",                               "General"),
        f("Deutsche Welle",               "https://rss.dw.com/rdf/rss-en-all",                               "General"),
        f("France 24 – English",          "https://www.france24.com/en/rss",                                  "General"),
        f("Al Jazeera – English",         "https://www.aljazeera.com/xml/rss/all.xml",                        "General"),
        f("UN News",                      "https://news.un.org/feed/subscribe/en/news/all/rss.xml",            "General"),
        f("Euronews",                     "https://www.euronews.com/rss?level=theme&name=news",                "General"),
        f("The Independent",              "https://www.independent.co.uk/news/rss",                           "General"),
        f("ABC News (US)",                "https://abcnews.go.com/abcnews/topstories",                        "General"),
        f("CBS News",                     "https://www.cbsnews.com/latest/rss/main",                          "General"),
        f("NBC News",                     "https://feeds.nbcnews.com/nbcnews/public/news",                    "General"),
        f("Times of India",               "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",       "General"),
        f("NDTV – Top Stories",           "https://feeds.feedburner.com/ndtvnews-top-stories",                "General"),
        f("Sky News",                     "https://feeds.skynews.com/feeds/rss/world.xml",                    "General"),
        f("Global News",                  "https://globalnews.ca/world/feed/",                               "General"),

        // ══════════════════════════════════════════════════════════════════════
        // HEALTH
        // ══════════════════════════════════════════════════════════════════════
        f("WHO News",                     "https://www.who.int/rss-feeds/news-english.xml",                   "Health"),
        f("WebMD Health",                 "https://rssfeeds.webmd.com/rss/rss.aspx?RSSSource=RSS_PUBLIC",     "Health"),
        f("Harvard Health Blog",          "https://www.health.harvard.edu/blog/feed",                         "Health"),
        f("NHS News",                     "https://www.nhs.uk/news/rss.xml",                                  "Health"),
        f("Medical News Today",           "https://www.medicalnewstoday.com/newsfeeds.xml",                   "Health"),
        f("ScienceDaily – Health",        "https://www.sciencedaily.com/rss/health_medicine.xml",             "Health"),
        f("Healthline",                   "https://www.healthline.com/rss/health-news",                       "Health"),
        f("Everyday Health",              "https://www.everydayhealth.com/rss/editorial-health.xml",          "Health"),

        // ══════════════════════════════════════════════════════════════════════
        // INDIA NEWS
        // ══════════════════════════════════════════════════════════════════════
        f("Times of India – India",       "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms",     "India"),
        f("NDTV – India",                 "https://feeds.feedburner.com/ndtvnews-india-news",                 "India"),
        f("The Hindu – India",            "https://www.thehindu.com/news/national/feeder/default.rss",        "India"),
        f("Indian Express – India",       "https://indianexpress.com/section/india/feed/",                    "India"),
        f("Hindustan Times",              "https://www.hindustantimes.com/rss/topnews/rssfeed.xml",           "India"),
        f("Firstpost – India",            "https://www.firstpost.com/commonfeeds/v1/mf/india/rss.xml",        "India"),
        f("ThePrint",                     "https://theprint.in/feed/",                                        "India"),
        f("Outlook India",                "https://www.outlookindia.com/rss",                                 "India"),

        // ══════════════════════════════════════════════════════════════════════
        // POLITICS
        // ══════════════════════════════════════════════════════════════════════
        f("Politico",                     "https://www.politico.com/rss/politicopicks.xml",                   "Politics"),
        f("The Hill",                     "https://thehill.com/rss/syndicator/19109/feed/",                   "Politics"),
        f("Washington Post – Politics",   "https://feeds.washingtonpost.com/rss/politics",                    "Politics"),
        f("BBC – Politics",               "https://feeds.bbci.co.uk/news/politics/rss.xml",                   "Politics"),
        f("EUobserver",                   "https://euobserver.com/rss",                                       "Politics"),
        f("Arab News – Politics",         "https://www.arabnews.com/politics/rss.xml",                        "Politics"),
        f("The Guardian – Politics",      "https://www.theguardian.com/politics/rss",                         "Politics"),
        f("France 24 – Politics",         "https://www.france24.com/en/politics/rss",                         "Politics"),
        f("South China Morning Post – Politics", "https://www.scmp.com/rss/2/feed",                          "Politics"),
        f("Axios",                        "https://api.axios.com/feed/",                                      "Politics"),
        f("Vox – Politics",               "https://www.vox.com/rss/world-politics/index.xml",                 "Politics"),

        // ══════════════════════════════════════════════════════════════════════
        // SCIENCE
        // ══════════════════════════════════════════════════════════════════════
        f("NASA Breaking News",           "https://www.nasa.gov/news-release/feed/",                          "Science"),
        f("Nature – Latest Research",     "https://www.nature.com/nature.rss",                                "Science"),
        f("ScienceDaily",                 "https://www.sciencedaily.com/rss/all.xml",                         "Science"),
        f("New Scientist",                "https://www.newscientist.com/feed/home/",                          "Science"),
        f("Scientific American",          "https://www.scientificamerican.com/platform/syndication/rss/",     "Science"),
        f("BBC Science",                  "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",    "Science"),
        f("Phys.org",                     "https://phys.org/rss-feed/",                                       "Science"),
        f("ISRO News",                    "https://www.isro.gov.in/rss",                                      "Science"),
        f("Space.com",                    "https://www.space.com/feeds/all",                                  "Science"),
        f("Live Science",                 "https://www.livescience.com/feeds/all",                            "Science"),

        // ══════════════════════════════════════════════════════════════════════
        // SPORTS
        // ══════════════════════════════════════════════════════════════════════
        f("ESPN Top Headlines",           "https://www.espn.com/espn/rss/news",                               "Sports"),
        f("BBC Sport",                    "https://feeds.bbci.co.uk/sport/rss.xml",                           "Sports"),
        f("Sky Sports – Latest",          "https://www.skysports.com/rss/12040",                              "Sports"),
        f("The Guardian – Sport",         "https://www.theguardian.com/sport/rss",                            "Sports"),
        f("ESPN Cricket",                 "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",        "Sports"),
        f("Yahoo Sports",                 "https://sports.yahoo.com/rss/",                                    "Sports"),
        f("Goal.com – Football",          "https://www.goal.com/feeds/en/news",                               "Sports"),
        f("NFL.com",                      "https://www.nfl.com/rss/rsslanding.html?contentType=news",         "Sports"),
        f("Bleacher Report",              "https://bleacherreport.com/articles/feed",                         "Sports"),
        f("Cricket.com",                  "https://www.cricket.com/rss.xml",                                  "Sports"),

        // ══════════════════════════════════════════════════════════════════════
        // STOCK MARKET
        // ══════════════════════════════════════════════════════════════════════
        f("MarketWatch – Markets",        "https://feeds.marketwatch.com/marketwatch/topstories/",            "Stock Market"),
        f("Yahoo Finance",                "https://finance.yahoo.com/news/rssindex",                          "Stock Market"),
        f("CNBC Markets",                 "https://www.cnbc.com/id/20910258/device/rss/rss.html",             "Stock Market"),
        f("Wall Street Journal – Markets","https://feeds.a.dj.com/rss/RSSMarketsMain.xml",                   "Stock Market"),
        f("Seeking Alpha",                "https://seekingalpha.com/feed.xml",                                "Stock Market"),
        f("Investopedia News",            "https://www.investopedia.com/feedbuilder/feed/getfeed?feedName=rss_articles", "Stock Market"),
        f("Fox Business",                 "https://moxie.foxbusiness.com/google-manager/feeds/latest.xml",   "Stock Market"),
        f("Mint – Markets",               "https://www.livemint.com/rss/markets",                             "Stock Market"),

        // ══════════════════════════════════════════════════════════════════════
        // TECH
        // ══════════════════════════════════════════════════════════════════════
        f("TechCrunch",                   "https://techcrunch.com/feed/",                                     "Tech"),
        f("The Verge",                    "https://www.theverge.com/rss/index.xml",                           "Tech"),
        f("Android Authority",            "https://www.androidauthority.com/feed/",                           "Tech"),
        f("9to5Google",                   "https://9to5google.com/feed/",                                     "Tech"),
        f("9to5Mac",                      "https://9to5mac.com/feed/",                                        "Tech"),
        f("Engadget",                     "https://www.engadget.com/rss.xml",                                 "Tech"),
        f("CNET",                         "https://www.cnet.com/rss/news/",                                   "Tech"),
        f("Gizmodo",                      "https://gizmodo.com/feed",                                         "Tech"),
        f("ZDNet",                        "https://www.zdnet.com/news/rss.xml",                               "Tech"),
        f("Hacker News (top)",            "https://hnrss.org/frontpage",                                      "Tech"),
        f("Dev.to",                       "https://dev.to/feed",                                              "Tech"),
        f("GitHub Blog",                  "https://github.blog/feed/",                                        "Tech"),
        f("Wired",                        "https://www.wired.com/feed/rss",                                   "Tech"),
        f("Mashable – Tech",              "https://mashable.com/feeds/rss/tech",                              "Tech"),

        // ══════════════════════════════════════════════════════════════════════
        // TRAVEL
        // ══════════════════════════════════════════════════════════════════════
        f("Lonely Planet",                "https://www.lonelyplanet.com/news/feed",                           "Travel"),
        f("Condé Nast Traveler",          "https://www.cntraveler.com/feed/rss",                              "Travel"),
        f("Travel + Leisure",             "https://www.travelandleisure.com/feeds/all",                       "Travel"),
        f("National Geographic Travel",   "https://www.nationalgeographic.com/travel/feed",                   "Travel"),
        f("The Points Guy",               "https://thepointsguy.com/feed/",                                   "Travel"),

        // ══════════════════════════════════════════════════════════════════════
        // WORLD NEWS
        // ══════════════════════════════════════════════════════════════════════
        f("BBC World",                    "https://feeds.bbci.co.uk/news/world/rss.xml",                      "World"),
        f("Al Jazeera – World",           "https://www.aljazeera.com/xml/rss/all.xml",                        "World"),
        f("France 24 – World",            "https://www.france24.com/en/rss",                                  "World"),
        f("Sky News – World",             "https://feeds.skynews.com/feeds/rss/world.xml",                    "World"),
        f("NPR World",                    "https://feeds.npr.org/1004/rss.xml",                               "World"),
        f("CNN World",                    "http://rss.cnn.com/rss/edition_world.rss",                         "World"),
        f("The Guardian – World",         "https://www.theguardian.com/world/rss",                            "World"),
        f("NYT World",                    "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",           "World"),
        f("Washington Post – World",      "https://feeds.washingtonpost.com/rss/world",                       "World"),
        f("South China Morning Post",     "https://www.scmp.com/rss/91/feed",                                 "World"),
        f("The Hindu – World",            "https://www.thehindu.com/news/international/feeder/default.rss",   "World"),
        f("Globe and Mail – World",       "https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/world/", "World"),
        f("Global News – World",          "https://globalnews.ca/world/feed/",                               "World")
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
