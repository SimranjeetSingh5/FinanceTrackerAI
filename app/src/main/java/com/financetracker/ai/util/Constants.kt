package com.financetracker.ai.util

/**
 * App-wide constants that were previously inline magic numbers.
 *
 * Grouped by concern so a value can be changed in one place and reasoned about in isolation.
 */
object Constants {

    // ---- Model download -------------------------------------------------------

    /** Unique work name for the background download job. */
    const val WORK_NAME_DOWNLOAD = "model_download"

    /** WorkManager progress keys, read by FinanceViewModel to drive the progress UI. */
    const val PROGRESS_BYTES_DONE = "bytes_done"
    const val PROGRESS_BYTES_TOTAL = "bytes_total"

    /** Attempts before a background download is reported as failed rather than retried. */
    const val MAX_DOWNLOAD_ATTEMPTS = 5

    /** Notification id for the foreground download notification. */
    const val NOTIFICATION_ID_DOWNLOAD = 42

    /** Network timeouts for the model transfer, in milliseconds. */
    const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
    const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /** How many HTTP redirects to follow before giving up. */
    const val MAX_DOWNLOAD_REDIRECTS = 5

    /** Stream buffer size for the transfer, in bytes. */
    const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    /** Hash buffer size for SHA-256 verification of the downloaded file, in bytes. */
    const val HASH_BUFFER_BYTES = 64 * 1024

    // ---- Notifications --------------------------------------------------------

    /** Notification ids for budget alerts, derived from the category id. */
    const val NOTIFICATION_ID_BUDGET_BASE = 1000

    /** Notification ids for recurring bill reminders, derived from the item id. */
    const val NOTIFICATION_ID_RECURRING_BASE = 2000

    // ---- Formatting -----------------------------------------------------------

    const val BYTES_PER_MB = 1_048_576L
    const val BYTES_PER_KB = 1024L

    /** Formats a byte count as "498 MB" / "12 KB" for progress text. */
    fun formatSize(bytes: Long): String = when {
        bytes >= BYTES_PER_MB -> "${bytes / BYTES_PER_MB} MB"
        bytes >= BYTES_PER_KB -> "${bytes / BYTES_PER_KB} KB"
        else -> "$bytes B"
    }
}
