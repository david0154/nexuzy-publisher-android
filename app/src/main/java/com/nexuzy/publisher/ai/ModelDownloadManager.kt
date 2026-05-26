package com.nexuzy.publisher.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ModelDownloadManager
 * ══════════════════════════════════════════════════════════════════════
 * Auto-downloads Devil AI 2B (Gemma 2B IT) on first launch.
 *
 * NO HuggingFace token required — uses Google’s public Kaggle CDN.
 * Model is free, non-gated, and downloads automatically in the background.
 *
 * Model: gemma-2b-it-gpu-int4.bin (~500 MB, GPU-accelerated INT4)
 * Fallback: gemma-2b-it-cpu-int8.bin (~1.6 GB, CPU INT8, all devices)
 *
 * Download happens automatically on first app launch via [autoDownloadIfNeeded].
 * Progress shown in Settings → Devil AI 2B status card.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        // ── Primary: Google public Kaggle CDN (no token, no account) ──
        // Gemma 2B IT INT4 GPU-optimised — ~500 MB
        const val MODEL_URL =
            "https://storage.googleapis.com/kaggle-models-data/models/google/gemma/tfLite/gemma-2b-it-gpu-int4/1/gemma-2b-it-gpu-int4.bin"

        // Fallback: CPU INT8 for devices without GPU delegate
        const val MODEL_URL_CPU_FALLBACK =
            "https://storage.googleapis.com/kaggle-models-data/models/google/gemma/tfLite/gemma-2b-it-cpu-int8/1/gemma-2b-it-cpu-int8.bin"

        const val MODEL_FILENAME     = "gemma-2b-it-gpu-int4.bin"
        const val MODEL_DISPLAY_NAME = "Devil AI 2B (Gemma 2B IT)"
        const val EXPECTED_MIN_BYTES = 400_000_000L   // 400 MB sanity check

        private const val PREF_FILE     = "devil_ai_prefs"
        private const val PREF_DL_ID    = "dl_download_id"
        private const val PREF_AUTO_DL  = "auto_dl_triggered"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun getModelFile(): File =
        File(context.getExternalFilesDir("models"), MODEL_FILENAME)

    fun isModelReady(): Boolean {
        val f  = getModelFile()
        val ok = f.exists() && f.length() >= EXPECTED_MIN_BYTES
        Log.d(TAG, "isModelReady=$ok  size=${f.length()}")
        return ok
    }

    fun getModelSizeOnDisk(): String {
        val b = getModelFile().length()
        return when {
            b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
            b >= 1_048_576L     -> "%.0f MB".format(b / 1_048_576.0)
            else                -> "$b B"
        }
    }

    /**
     * Call this from Application.onCreate() or MainActivity.onCreate().
     * Silently starts downloading the model in the background if it hasn’t been
     * downloaded yet. No user action required — no token, no login.
     */
    fun autoDownloadIfNeeded() {
        if (isModelReady()) {
            Log.d(TAG, "autoDownloadIfNeeded: model already ready, skipping")
            return
        }
        val prefs       = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val alreadyStarted = prefs.getLong(PREF_DL_ID, -1L) != -1L
        if (alreadyStarted) {
            Log.d(TAG, "autoDownloadIfNeeded: download already in progress")
            return
        }
        Log.i(TAG, "autoDownloadIfNeeded: starting background download")
        enqueueDownload(MODEL_URL)
    }

    /**
     * Manually trigger download (e.g. from Settings screen).
     * Returns a Flow of [DownloadProgress] updates.
     */
    fun downloadModel(): Flow<DownloadProgress> = callbackFlow {
        val destDir = context.getExternalFilesDir("models")
        destDir?.mkdirs()

        val dm    = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        // Cancel any previous failed/stale download
        val oldId = prefs.getLong(PREF_DL_ID, -1L)
        if (oldId != -1L) { try { dm.remove(oldId) } catch (_: Exception) {} }

        val downloadId = enqueueDownload(MODEL_URL)
        if (downloadId == -1L) {
            trySend(DownloadProgress(error = "Failed to start download. Please try again."))
            close()
            return@callbackFlow
        }

        trySend(DownloadProgress(bytesDownloaded = 0, totalBytes = 0, percent = 0))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) Log.i(TAG, "Broadcast: DOWNLOAD_COMPLETE id=$id")
            }
        }
        registerReceiver(receiver)

        try {
            var done      = false
            var stalledMs = 0L
            var lastBytes = -1L

            while (!done) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))

                if (cursor != null && cursor.moveToFirst()) {
                    val status     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total      = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reason     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    cursor.close()

                    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                    if (downloaded == lastBytes) stalledMs += 1000 else { stalledMs = 0; lastBytes = downloaded }

                    when (status) {
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING -> trySend(DownloadProgress(
                            bytesDownloaded = downloaded, totalBytes = total, percent = pct
                        ))
                        DownloadManager.STATUS_PAUSED -> {
                            val msg = when (reason) {
                                DownloadManager.PAUSED_QUEUED_FOR_WIFI     -> "Waiting for Wi-Fi… ($pct%)"
                                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network… ($pct%)"
                                DownloadManager.PAUSED_WAITING_TO_RETRY    -> "Retrying… ($pct%)"
                                else -> "Download paused ($pct%)"
                            }
                            trySend(DownloadProgress(bytesDownloaded = downloaded, totalBytes = total, percent = pct, statusMessage = msg))
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            trySend(DownloadProgress(bytesDownloaded = downloaded, totalBytes = total, percent = 100, isDone = true))
                            prefs.edit().remove(PREF_DL_ID).apply()
                            Log.i(TAG, "Download complete — ${getModelSizeOnDisk()}")
                            done = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val errMsg = "Download failed (code=$reason). Check internet connection."
                            Log.e(TAG, errMsg)
                            trySend(DownloadProgress(error = errMsg))
                            prefs.edit().remove(PREF_DL_ID).apply()
                            done = true
                        }
                    }

                    if (stalledMs >= 60_000 && !done) {
                        trySend(DownloadProgress(
                            bytesDownloaded = lastBytes, totalBytes = total, percent = pct,
                            statusMessage = "Download seems stalled. Check your connection."
                        ))
                    }
                } else {
                    cursor?.close()
                    trySend(DownloadProgress(error = "Download was cancelled."))
                    done = true
                }

                if (!done) delay(1000)
            }
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            close()
        }

        awaitClose {}
    }

    /** Suspend wrapper for pipeline callers (AiPipeline, ArticleGeneratorClient). */
    suspend fun downloadModelSync(
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var success = false
        downloadModel().collect { progress ->
            onProgress(progress)
            if (progress.isDone)             success = true
            if (progress.error.isNotBlank()) success = false
        }
        success
    }

    fun deleteModel(): Boolean {
        val f = getModelFile()
        return if (f.exists()) f.delete() else true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    private fun enqueueDownload(url: String): Long {
        return try {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            context.getExternalFilesDir("models")?.mkdirs()
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Downloading $MODEL_DISPLAY_NAME")
                setDescription("On-device AI model — ~500 MB, one-time download")
                setDestinationInExternalFilesDir(context, "models", MODEL_FILENAME)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
                )
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverRoaming(false)
                // No Authorization header needed — public CDN
            }
            val id = dm.enqueue(request)
            prefs.edit().putLong(PREF_DL_ID, id).apply()
            Log.i(TAG, "Enqueued download id=$id  url=$url")
            id
        } catch (e: Exception) {
            Log.e(TAG, "enqueueDownload failed: ${e.message}")
            -1L
        }
    }

    private fun registerReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress data class
    // ─────────────────────────────────────────────────────────────────────────

    data class DownloadProgress(
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long      = 0L,
        val percent: Int          = 0,
        val isDone: Boolean       = false,
        val statusMessage: String = "",
        val error: String         = ""
    ) {
        val mbDownloaded: Long get() = bytesDownloaded / 1_048_576
        val mbTotal: Long      get() = totalBytes      / 1_048_576
        val isError: Boolean   get() = error.isNotBlank()
        val isPaused: Boolean  get() = statusMessage.isNotBlank() && !isDone && !isError
    }
}
