package com.nexuzy.publisher.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a single item from an RSS feed.
 *
 * Fields added in v2:
 *  - [fullContent]     : full article body text scraped from [link], used by Gemini for rewriting.
 *  - [localImagePath]  : local file path of downloaded article image (populated by AiPipeline).
 *
 * [description] : short RSS <description> snippet (1-3 sentences).
 * [fullContent] : complete article text from the article page (~500-3000 words).
 * Gemini always prefers [fullContent] when available; falls back to [description].
 */
@Parcelize
data class RssItem(
    val title: String,
    val description: String,
    val link: String,
    val pubDate: String,
    val imageUrl: String = "",
    /** Local file path of the article image, set by ImageDownloader during AiPipeline. */
    val localImagePath: String = "",
    /** Full article body text scraped from [link] during RSS fetch. Powers Gemini rewriting. */
    val fullContent: String = "",
    val feedName: String = "",
    val feedCategory: String = ""
) : Parcelable
