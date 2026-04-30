package com.nexuzy.publisher.data.firebase

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.firebase.auth.FirebaseAuth
import com.nexuzy.publisher.data.db.AppDatabase
import com.nexuzy.publisher.data.model.RssFeed
import com.nexuzy.publisher.data.prefs.ApiKeyManager
import com.nexuzy.publisher.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RssFirestoreSync
 *
 * Bridges Room DB (local) and Firestore (cloud) for RSS feed URLs.
 *
 * Firestore path: users/{uid}/rss_links/{rssId}
 *
 * HOW TO USE in your RSS Fragment / ViewModel:
 *
 *   val sync = RssFirestoreSync(requireContext())
 *
 *   // Add a feed:
 *   sync.addFeed(lifecycleScope, url = "https://...", name = "BBC", category = "World")
 *
 *   // Delete a feed:
 *   sync.deleteFeed(lifecycleScope, localId = feed.id.toString())
 *
 *   // On fragment start — restore from Firestore if Room is empty:
 *   sync.restoreFeedsIfEmpty(lifecycleScope) { restored ->
 *       if (restored) feedAdapter.notifyDataSetChanged()
 *   }
 */
class RssFirestoreSync(private val context: Context) {

    private val TAG = "RssFirestoreSync"

    private val db            = AppDatabase.getDatabase(context)
    private val keyManager    = ApiKeyManager(context)
    private val appPrefs      = AppPreferences(context)
    private val firestoreRepo = FirestoreUserRepository(keyManager, appPrefs)

    // ──────────────────────────────────────────────────────────────────────────
    // Add a new RSS feed (Room + Firestore)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Inserts a feed into Room and syncs to Firestore.
     *
     * @param scope    Fragment/Activity lifecycleScope
     * @param url      RSS feed URL
     * @param name     Feed display name
     * @param category Feed category
     * @param onDone   Called with the new Room ID after insert (or -1 on error)
     */
    fun addFeed(
        scope: LifecycleCoroutineScope,
        url: String,
        name: String,
        category: String,
        onDone: ((roomId: Long) -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Insert into Room
                val feed = RssFeed(
                    url      = url.trim(),
                    name     = name.trim().ifBlank { url.trim() },
                    category = category.trim(),
                    isActive = true
                )
                val roomId = db.rssFeedDao().insert(feed)
                Log.d(TAG, "Feed inserted in Room: id=$roomId, url=$url")

                // 2. Sync to Firestore using roomId as the document ID
                val firestoreId = roomId.toString()
                firestoreRepo.addRssFeedToFirestore(
                    rssId    = firestoreId,
                    url      = url.trim(),
                    name     = name.trim().ifBlank { url.trim() },
                    category = category.trim()
                )

                withContext(Dispatchers.Main) { onDone?.invoke(roomId) }
            } catch (e: Exception) {
                Log.e(TAG, "addFeed failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone?.invoke(-1L) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete a feed (Room + Firestore)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a feed from both Room and Firestore.
     *
     * @param scope   Fragment/Activity lifecycleScope
     * @param feed    The RssFeed entity to delete
     * @param onDone  Called on main thread after deletion
     */
    fun deleteFeed(
        scope: LifecycleCoroutineScope,
        feed: RssFeed,
        onDone: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Delete from Room
                db.rssFeedDao().delete(feed)
                Log.d(TAG, "Feed deleted from Room: id=${feed.id}")

                // 2. Delete from Firestore (use Room ID as Firestore doc ID)
                firestoreRepo.deleteRssFeedFromFirestore(feed.id.toString())

                withContext(Dispatchers.Main) { onDone?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "deleteFeed failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone?.invoke() }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Restore feeds from Firestore if Room is empty (new device / reinstall)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * If Room DB has NO feeds, loads all feeds from Firestore and inserts them.
     * Safe to call every time the RSS screen opens — skips if Room already has data.
     *
     * @param scope     Fragment/Activity lifecycleScope
     * @param onDone    Called with true if feeds were restored, false if Room already had data
     */
    fun restoreFeedsIfEmpty(
        scope: LifecycleCoroutineScope,
        onDone: ((restored: Boolean) -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val localCount = db.rssFeedDao().getCount()
                if (localCount > 0) {
                    Log.d(TAG, "Room already has $localCount feeds — Firestore restore skipped")
                    withContext(Dispatchers.Main) { onDone?.invoke(false) }
                    return@launch
                }

                // Room is empty — restore from Firestore
                val firestoreFeeds = firestoreRepo.loadRssFeedsFromFirestore()
                if (firestoreFeeds.isEmpty()) {
                    Log.d(TAG, "No feeds in Firestore either")
                    withContext(Dispatchers.Main) { onDone?.invoke(false) }
                    return@launch
                }

                var inserted = 0
                for (entry in firestoreFeeds) {
                    try {
                        // Try to use Firestore doc ID (which equals original Room ID) as int
                        val intId = entry.firestoreId.toLongOrNull()
                        val feed = RssFeed(
                            id       = intId ?: 0L,
                            url      = entry.url,
                            name     = entry.name,
                            category = entry.category,
                            isActive = entry.isActive
                        )
                        db.rssFeedDao().insertOrReplace(feed)
                        inserted++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to insert restored feed ${entry.url}: ${e.message}")
                    }
                }

                Log.i(TAG, "✅ Restored $inserted feeds from Firestore into Room")
                withContext(Dispatchers.Main) { onDone?.invoke(inserted > 0) }
            } catch (e: Exception) {
                Log.e(TAG, "restoreFeedsIfEmpty failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone?.invoke(false) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Full sync: push ALL local Room feeds to Firestore (one-time migration)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Pushes all existing local Room feeds up to Firestore.
     * Useful for first-time users who already had feeds before Firestore was added.
     * Safe to call multiple times — Firestore set() is idempotent.
     */
    fun pushAllLocalFeedsToFirestore(
        scope: LifecycleCoroutineScope,
        onDone: ((pushedCount: Int) -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val localFeeds = db.rssFeedDao().getAllOnce()
                var pushed = 0
                for (feed in localFeeds) {
                    val ok = firestoreRepo.addRssFeedToFirestore(
                        rssId    = feed.id.toString(),
                        url      = feed.url,
                        name     = feed.name,
                        category = feed.category
                    )
                    if (ok.isNotBlank()) pushed++
                }
                Log.i(TAG, "One-time migration: pushed $pushed feeds to Firestore")
                withContext(Dispatchers.Main) { onDone?.invoke(pushed) }
            } catch (e: Exception) {
                Log.e(TAG, "pushAllLocalFeedsToFirestore failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone?.invoke(0) }
            }
        }
    }
}
