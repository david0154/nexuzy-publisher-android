package com.nexuzy.publisher.data.model

/**
 * All supported news categories for RSS feeds and WordPress publishing.
 *
 * Hierarchy: sub-categories include their parent when pushed to WordPress.
 * Example: "AI & Machine Learning" → WP gets IDs for ["Technology", "AI & Machine Learning"]
 *
 * Maintained by: David | Nexuzy Lab
 */
object NewsCategory {

    /** Full flat list — used in RSS feed category picker spinner */
    val ALL: List<String> = listOf(
        "General", "Breaking News", "Top Stories",
        "Politics", "Government", "Elections", "International Relations",
        "Business", "Economy", "Finance", "Markets", "Stock Market", "Cryptocurrency", "Startups",
        "Technology", "AI & Machine Learning", "Gadgets", "Software", "Cybersecurity", "Gaming",
        "Science", "Health", "Medicine", "Research", "Space", "Environment", "Climate Change",
        "Sports", "Football", "Cricket", "Basketball", "Tennis", "Olympics", "Esports",
        "Entertainment", "Movies", "TV Shows", "Music", "Celebrities", "Hollywood", "Bollywood",
        "Lifestyle", "Fashion", "Beauty", "Travel", "Food", "Cooking", "Parenting",
        "World News", "Asia", "Europe", "Americas", "Africa", "Middle East", "India", "USA", "UK",
        "Education", "Career", "Crime", "Law", "Weather", "Automotive", "Opinion"
    )

    /**
     * Parent category map.
     * Key = child category name, Value = parent category name.
     * When pushing to WordPress, BOTH parent + child category IDs are assigned.
     */
    private val PARENT_MAP: Map<String, String> = mapOf(
        // Politics group
        "Government"              to "Politics",
        "Elections"               to "Politics",
        "International Relations" to "Politics",

        // Business group
        "Economy"        to "Business",
        "Finance"        to "Business",
        "Markets"        to "Business",
        "Stock Market"   to "Business",
        "Cryptocurrency" to "Business",
        "Startups"       to "Business",

        // Technology group
        "AI & Machine Learning" to "Technology",
        "Gadgets"               to "Technology",
        "Software"              to "Technology",
        "Cybersecurity"         to "Technology",
        "Gaming"                to "Technology",

        // Science group
        "Medicine"      to "Health",
        "Research"      to "Science",
        "Space"         to "Science",
        "Environment"   to "Science",
        "Climate Change"to "Science",

        // Sports group
        "Football"   to "Sports",
        "Cricket"    to "Sports",
        "Basketball" to "Sports",
        "Tennis"     to "Sports",
        "Olympics"   to "Sports",
        "Esports"    to "Sports",

        // Entertainment group
        "Movies"      to "Entertainment",
        "TV Shows"    to "Entertainment",
        "Music"       to "Entertainment",
        "Celebrities" to "Entertainment",
        "Hollywood"   to "Entertainment",
        "Bollywood"   to "Entertainment",

        // Lifestyle group
        "Fashion"   to "Lifestyle",
        "Beauty"    to "Lifestyle",
        "Travel"    to "Lifestyle",
        "Food"      to "Lifestyle",
        "Cooking"   to "Lifestyle",
        "Parenting" to "Lifestyle",

        // World News / Regions group
        "Asia"        to "World News",
        "Europe"      to "World News",
        "Americas"    to "World News",
        "Africa"      to "World News",
        "Middle East" to "World News",
        "India"       to "World News",
        "USA"         to "World News",
        "UK"          to "World News"
    )

    /**
     * Returns the full category chain for WordPress assignment.
     *
     * "AI & Machine Learning" → ["Technology", "AI & Machine Learning"]
     * "Technology"            → ["Technology"]
     * "General"               → ["General"]
     */
    fun getCategoryChain(category: String): List<String> {
        if (category.isBlank()) return listOf("General")
        val parent = PARENT_MAP[category]
        return if (parent != null) listOf(parent, category) else listOf(category)
    }

    /**
     * Returns the parent category for a given category, or null for top-level.
     */
    fun getParent(category: String): String? = PARENT_MAP[category]

    /**
     * Returns true if the given string is a known category.
     */
    fun isValid(category: String): Boolean = ALL.contains(category)

    /**
     * Detects the best-matching category from an RSS item title + description.
     * Used as fallback when the RSS feed doesn't provide a category.
     * Falls back to "General" when no keyword matches.
     */
    fun detectCategory(title: String, description: String = ""): String {
        val text = "$title $description".lowercase()
        return when {
            // Sports
            text.contains("cricket") || text.contains("ipl") || text.contains("test match") || text.contains("odi ") -> "Cricket"
            text.contains("football") || text.contains("fifa") || text.contains("premier league") || text.contains("bundesliga") || text.contains("la liga") -> "Football"
            text.contains("basketball") || text.contains("nba") -> "Basketball"
            text.contains("tennis") || text.contains("wimbledon") || text.contains("grand slam") || text.contains("us open") -> "Tennis"
            text.contains("olympic") -> "Olympics"
            text.contains("esport") || text.contains("e-sport") || text.contains("twitch stream") -> "Esports"

            // Entertainment
            text.contains("bollywood") || text.contains("hindi film") || text.contains("hindi movie") -> "Bollywood"
            text.contains("hollywood") -> "Hollywood"
            text.contains("celebrity") || text.contains("actor ") || text.contains("actress") -> "Celebrities"
            text.contains("tv show") || text.contains("web series") || text.contains("netflix") || text.contains("amazon prime") || text.contains("disney+") -> "TV Shows"
            text.contains("music") || text.contains(" song") || text.contains(" album") || text.contains("singer") -> "Music"
            text.contains("movie") || text.contains("film") || text.contains("cinema") || text.contains("box office") -> "Movies"

            // Technology
            text.contains("artificial intelligence") || text.contains(" ai ") || text.contains("machine learning") ||
                text.contains("chatgpt") || text.contains("gemini") || text.contains("llm") || text.contains("large language model") || text.contains("openai") -> "AI & Machine Learning"
            text.contains("cybersecur") || text.contains("hacker") || text.contains("malware") || text.contains("ransomware") || text.contains("data breach") -> "Cybersecurity"
            text.contains("smartphone") || text.contains("iphone") || text.contains("android phone") || text.contains("samsung galaxy") || text.contains("gadget") -> "Gadgets"
            text.contains("gaming") || text.contains("video game") || text.contains("playstation") || text.contains("xbox") || text.contains("nintendo") -> "Gaming"
            text.contains("software") || text.contains("app launch") || text.contains("app update") -> "Software"
            text.contains("technolog") -> "Technology"

            // Business / Finance
            text.contains("bitcoin") || text.contains("crypto") || text.contains("ethereum") || text.contains("blockchain") || text.contains("web3") -> "Cryptocurrency"
            text.contains("startup") || text.contains("unicorn") || text.contains("series a") || text.contains("series b") || text.contains("seed funding") -> "Startups"
            text.contains("stock market") || text.contains("nifty") || text.contains("sensex") || text.contains("nasdaq") || text.contains("dow jones") || text.contains("nyse") -> "Stock Market"
            text.contains("market") || text.contains("trading") || text.contains("bse") -> "Markets"
            text.contains("finance") || text.contains("loan") || text.contains(" rbi ") || text.contains("fed rate") || text.contains("interest rate") -> "Finance"
            text.contains("economy") || text.contains(" gdp") || text.contains("inflation") || text.contains("recession") -> "Economy"
            text.contains("business") || text.contains("company") || text.contains("corporate") || text.contains("merger") -> "Business"

            // Science
            text.contains("space") || text.contains("nasa") || text.contains("isro") || text.contains("rocket") || text.contains("satellite") || text.contains("mars") -> "Space"
            text.contains("climate change") || text.contains("global warming") -> "Climate Change"
            text.contains("environment") || text.contains("pollution") || text.contains("green energy") || text.contains("solar energy") -> "Environment"
            text.contains("research") || text.contains("scientists") || text.contains("study finds") -> "Research"

            // Health
            text.contains("medicine") || text.contains("drug") || text.contains("treatment") || text.contains("hospital") || text.contains("surgery") -> "Medicine"
            text.contains("health") || text.contains("disease") || text.contains("virus") || text.contains("vaccine") || text.contains("pandemic") -> "Health"
            text.contains("science") -> "Science"

            // Politics
            text.contains("election") || text.contains("voting") || text.contains(" vote") -> "Elections"
            text.contains("government") || text.contains("ministry") || text.contains("parliament") || text.contains("cabinet") -> "Government"
            text.contains("politic") || text.contains("congress") || text.contains("bjp") || text.contains("senate") || text.contains("president") || text.contains("prime minister") -> "Politics"

            // Regions
            text.contains("india") && (text.contains("government") || text.contains("modi") || text.contains("delhi")) -> "India"
            text.contains("united states") || text.contains("white house") || text.contains("trump") || text.contains("biden") -> "USA"
            text.contains("united kingdom") || text.contains("britain") || text.contains("london") -> "UK"
            text.contains("china") || text.contains("japan") || text.contains("korea") || text.contains("asia") -> "Asia"
            text.contains("europe") || text.contains("france") || text.contains("germany") || text.contains("european union") -> "Europe"
            text.contains("africa") -> "Africa"
            text.contains("middle east") || text.contains("israel") || text.contains("iran") || text.contains("saudi") || text.contains("gaza") -> "Middle East"
            text.contains("world") || text.contains("global") || text.contains("international") -> "World News"

            // Lifestyle
            text.contains("fashion") || text.contains("style") || text.contains("clothing") || text.contains("outfit") -> "Fashion"
            text.contains("travel") || text.contains("tourism") || text.contains("trip") || text.contains("vacation") -> "Travel"
            text.contains("food") || text.contains("recipe") || text.contains("restaurant") || text.contains("cuisine") -> "Food"

            // Other
            text.contains("education") || text.contains("school") || text.contains("university") || text.contains("exam") -> "Education"
            text.contains("career") || text.contains("job") || text.contains("hiring") || text.contains("layoff") -> "Career"
            text.contains("crime") || text.contains("murder") || text.contains("arrest") || text.contains("police") -> "Crime"
            text.contains("law") || text.contains("court") || text.contains("verdict") || text.contains("judge") -> "Law"
            text.contains("weather") || text.contains("rain") || text.contains("cyclone") || text.contains("flood") -> "Weather"
            text.contains("car") || text.contains("auto") || text.contains("vehicle") || text.contains("electric vehicle") || text.contains("ev ") -> "Automotive"
            text.contains("opinion") || text.contains("editorial") || text.contains("column") -> "Opinion"
            text.contains("breaking") -> "Breaking News"
            else -> "General"
        }
    }
}
