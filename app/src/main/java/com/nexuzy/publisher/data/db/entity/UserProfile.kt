package com.nexuzy.publisher.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey
    val uid: String,
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String = ""
)
