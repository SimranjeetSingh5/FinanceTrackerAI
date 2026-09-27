package com.financetracker.ai.util

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.ai.ModelDownloader

/**
 * Downloads the Gemma model in the background so it survives the user leaving the app.
 *
 * Runs as a foreground service so an ~800 MB transfer isn't killed after the 10-minute
 * background limit, and reports progress in the notification shade. Progress is resumable: if
 * the process is killed or the network drops, the .part file on disk is reused and the next
 * attempt continues with an HTTP Range request.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FinanceApp
        val files = GemmaModelFiles(applicationContext)
        val destFile = files.modelFile()
        val partFile = files.partFile()

        // Nothing to do if a previous run already finished.
        if (destFile.exists() && destFile.length() > 0) {
            ModelDownloader.log("worker: model already present, skipping")
            return Result.success()
        }

        setForeground(createForegroundInfo(ModelDownloader.partialBytes(partFile), -1L))

        val outcome = ModelDownloader.download(partFile, destFile) { bytesDone, totalBytes ->
            // These are the only two channels that still work once the user has left the app.
            setForegroundAsync(createForegroundInfo(bytesDone, totalBytes))
            setProgressAsync(
                workDataOf(
                    Constants.PROGRESS_BYTES_DONE to bytesDone,
                    Constants.PROGRESS_BYTES_TOTAL to totalBytes
                )
            )
        }

        return when (outcome) {
            is ModelDownloader.Result.Success -> {
                ModelDownloader.log("worker: download complete, loading model")
                // Load it so the app opens ready to use. A failure here surfaces through the
                // ViewModel's model state, so it isn't a download failure.
                app.gemmaHelper.initialize()
                    .onSuccess { app.isModelLoaded = true }
                    .onFailure { ModelDownloader.log("worker: model load failed: ${it.message}") }
                Result.success()
            }

            is ModelDownloader.Result.Failure -> {
                ModelDownloader.log(
                    "worker: failed (${outcome.message}), attempt ${runAttemptCount + 1}"
                )
                // Retry while keeping the .part file — the next run resumes with a Range request.
                if (runAttemptCount + 1 < Constants.MAX_DOWNLOAD_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    private fun createForegroundInfo(bytesDone: Long, totalBytes: Long): ForegroundInfo {
        val notification = buildNotification(bytesDone, totalBytes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Constants.NOTIFICATION_ID_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(Constants.NOTIFICATION_ID_DOWNLOAD, notification)
        }
    }

    private fun buildNotification(bytesDone: Long, totalBytes: Long): Notification {
        val hasTotal = totalBytes > 0
        val pct = if (hasTotal) ((bytesDone * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

        val text = when {
            bytesDone <= 0L -> "Starting…"
            hasTotal -> "${Constants.formatSize(bytesDone)} of ${Constants.formatSize(totalBytes)} ($pct%)"
            else -> "${Constants.formatSize(bytesDone)} downloaded"
        }

        return NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading AI model")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // An indeterminate bar until the server reports a content length.
            .setProgress(100, pct, !hasTotal)
            .build()
    }

    companion object {
        /**
         * Enqueues the download, replacing any in-flight job. Requires connectivity and waits
         * for the battery not to be low, so a long transfer isn't killed on a flat battery.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(Constants.WORK_NAME_DOWNLOAD)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORK_NAME_DOWNLOAD,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(Constants.WORK_NAME_DOWNLOAD)
        }
    }
}

/**
 * Resolves the model's on-device paths. Kept in one place so the worker and
 * GemmaInferenceHelper can't drift apart on where the file lives.
 */
internal class GemmaModelFiles(private val context: Context) {
    private val dir get() = context.filesDir
    fun modelFile() = java.io.File(dir, "gemma_local_model.task")
    fun partFile() = java.io.File(dir, "gemma_local_model.task.part")
}
