package com.nexuzy.publisher.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ModelDownloadManager
 * ══════════════════════════════════════════════════════════════════════
 * Downloads Devil AI 2B (Gemma 3n E2B LiteRT) from HuggingFace using
 * Android's built-in DownloadManager — supports WiFi-only, resume,
 * system notifications, and Bearer token auth.
 *
 * HuggingFace token MUST be set by user in Settings → HuggingFace Token.
 * No hardcoded token — the model is gated and requires personal approval.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        const val MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"

        const val MODEL_FILENAME      = "gemma-3n-E2B-it-int4.litertlm"
        const val MODEL_DISPLAY_NAME  = "Devil AI 2B (Gemma 3n E2B)"
        const val EXPECTED_MIN_BYTES  = 500_000_000L   // 500 MB sanity check

        private const val PREF_FILE      = "devil_ai_prefs"
        private const val PREF_DL_ID     = "hf_download_id"
        private const val PREF_HF_TOKEN  = "hf_token"
    }

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    fun getModelFile(): File =
        File(context.getExternalFilesDir("models"), MODEL_FILENAME)

    fun isModelReady(): Boolean {
        val f = getModelFile()
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

    /** Save HuggingFace token so it survives app restarts. */
    fun saveHuggingFaceToken(token: String) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_HF_TOKEN, token.trim()).apply()
        Log.i(TAG, "HF token saved (${token.length} chars)")
    }

    /**
     * Returns user-saved token. Returns blank if not set.
     * If blank, the download will fail with a clear error message.
     */
    fun getHuggingFaceToken(): String {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(PREF_HF_TOKEN, "") ?: ""
    }

    /**
     * Start (or resume) the model download.
     * Returns a Flow of [DownloadProgress] updates.
     */
    fun downloadModel(hfToken: String = getHuggingFaceToken()): Flow<DownloadProgress> =
        callbackFlow {
            if (hfToken.isNotBlank()) saveHuggingFaceToken(hfToken)

            val token = getHuggingFaceToken()
            if (token.isBlank()) {
                trySend(DownloadProgress(
                    error = "HuggingFace token not set. Please add it in Settings → HuggingFace Token."
                ))
                close()
                return@callbackFlow
            }

            val destDir = context.getExternalFilesDir("models")
            destDir?.mkdirs()

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val prefs   = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val oldDlId = prefs.getLong(PREF_DL_ID, -1L)
            if (oldDlId != -1L) {
                try { dm.remove(oldDlId) } catch (_: Exception) {}
            }

            val request = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
                addRequestHeader("Authorization", "Bearer $token")
                setTitle("Downloading $MODEL_DISPLAY_NAME")
                setDescription("Devil AI 2B model for offline article writing")
                setDestinationInExternalFilesDir(context, "models", MODEL_FILENAME)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
                )
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverRoaming(false)
            }

            val downloadId = dm.enqueue(request)
            prefs.edit().putLong(PREF_DL_ID, downloadId).apply()
            Log.i(TAG, "DownloadManager enqueued id=$downloadId  token=${token.take(8)}…")

            // Emit initial progress immediately so UI doesn't hang on first frame
            trySend(DownloadProgress(bytesDownloaded = 0, totalBytes = 0, percent = 0))

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) Log.i(TAG, "Broadcast: COMPLETE id=$id")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

            try {
                var done      = false
                var stalledMs = 0L
                var lastBytes = -1L

                while (!done) {
                    val query  = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)

                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        cursor.close()

                        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                        // Stall detection
                        if (downloaded == lastBytes) stalledMs += 1000
                        else { stalledMs = 0; lastBytes = downloaded }

                        when (status) {
                            DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PENDING -> {
                                trySend(DownloadProgress(
                                    bytesDownloaded = downloaded,
                                    totalBytes      = total,
                                    percent         = pct
                                ))
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                val msg = when (reason) {
                                    DownloadManager.PAUSED_QUEUED_FOR_WIFI      -> "Waiting for WiFi… ($pct%)"
                                    DownloadManager.PAUSED_WAITING_FOR_NETWORK  -> "Waiting for network… ($pct%)"
                                    DownloadManager.PAUSED_WAITING_TO_RETRY     -> "Retrying… ($pct%)"
                                    else -> "Download paused ($pct%)"
                                }
                                trySend(DownloadProgress(
                                    bytesDownloaded = downloaded,
                                    totalBytes      = total,
                                    percent         = pct,
                                    statusMessage   = msg
                                ))
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                trySend(DownloadProgress(
                                    bytesDownloaded = downloaded,
                                    totalBytes      = total,
                                    percent         = 100,
                                    isDone          = true
                                ))
                                prefs.edit().remove(PREF_DL_ID).apply()
                                Log.i(TAG, "Download complete — ${getModelSizeOnDisk()}")
                                done = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val errMsg = "Download failed (reason=$reason). Check internet and try again."
                                Log.e(TAG, errMsg)
                                trySend(DownloadProgress(error = errMsg))
                                prefs.edit().remove(PREF_DL_ID).apply()
                                done = true
                            }
                        }

                        // Warn if stalled >60s
                        if (stalledMs >= 60_000 && !done) {
                            trySend(DownloadProgress(
                                bytesDownloaded = lastBytes,
                                totalBytes      = total,
                                percent         = pct,
                                statusMessage   = "Download seems stalled. Check your connection."
                            ))
                        }

                    } else {
                        cursor?.close()
                        Log.w(TAG, "DownloadManager cursor null for id=$downloadId — cancelled?")
                        trySend(DownloadProgress(error = "Download was cancelled or not found."))
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

    /** Suspend wrapper for non-Flow callers (e.g. AiPipeline). */
    suspend fun downloadModelSync(
        hfToken: String = getHuggingFaceToken(),
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var success = false
        downloadModel(hfToken).collect { progress ->
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

    // ──────────────────────────────────────────────────────────────────
    // Data
    // ──────────────────────────────────────────────────────────────────

    data class DownloadProgress(
        val bytesDownloaded: Long  = 0L,
        val totalBytes: Long       = 0L,
        val percent: Int           = 0,
        val isDone: Boolean        = false,
        val statusMessage: String  = "",
        val error: String          = ""
    ) {
        val mbDownloaded: Long get() = bytesDownloaded / 1_048_576
        val mbTotal: Long      get() = totalBytes      / 1_048_576
        val isError: Boolean   get() = error.isNotBlank()
        val isPaused: Boolean  get() = statusMessage.isNotBlank() && !isDone && !isError
    }
}
