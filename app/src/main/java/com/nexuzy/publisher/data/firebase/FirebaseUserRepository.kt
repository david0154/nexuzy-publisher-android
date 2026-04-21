package com.nexuzy.publisher.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nexuzy.publisher.data.model.firebase.FirebaseRssLink
import com.nexuzy.publisher.data.model.firebase.FirebaseUserProfile
import kotlinx.coroutines.tasks.await

class FirebaseUserRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    suspend fun upsertUserProfile(profile: FirebaseUserProfile) {
        firestore.collection(USERS)
            .document(profile.uid)
            .set(profile, SetOptions.merge())
            .await()
    }

    suspend fun addRssLink(uid: String, link: FirebaseRssLink) {
        val id = link.id.ifBlank { firestore.collection(USERS).document(uid).collection(RSS_LINKS).document().id }
        firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .document(id)
            .set(link.copy(id = id), SetOptions.merge())
            .await()
    }

    suspend fun deleteRssLink(uid: String, rssId: String) {
        firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .document(rssId)
            .delete()
            .await()
    }

    suspend fun getRssLinks(uid: String): List<FirebaseRssLink> {
        val snapshot = firestore.collection(USERS)
            .document(uid)
            .collection(RSS_LINKS)
            .get()
            .await()

        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(FirebaseRssLink::class.java)?.copy(id = doc.id)
        }
    }

    companion object {
        private const val USERS = "users"
        private const val RSS_LINKS = "rss_links"
    }
}
