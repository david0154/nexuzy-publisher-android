package com.nexuzy.publisher.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RssItem(
    val title: String,
    val description: String,
    val link: String,
    val pubDate: String,
    val imageUrl: String = "",
    val feedName: String = "",
    val feedCategory: String = ""
) : Parcelable
