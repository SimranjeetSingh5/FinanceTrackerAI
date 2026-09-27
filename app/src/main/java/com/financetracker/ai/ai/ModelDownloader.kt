package com.financetracker.ai.ai

import com.financetracker.ai.BuildConfig
import com.financetracker.ai.util.Constants
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable, mirror-falling-back downloader for the Gemma `.task` model.
 *
 * Lives outside the ViewModel so the background [com.financetracker.ai.util.ModelDownloadWorker]
 * and the UI share one implementation rather than two copies of the HTTP logic.
 */
object ModelDownloader {

    private const val LOG_TAG = "ModelDownload"

    sealed class Result {
        object Success : Result()

        /**
         * [fatal] means the partial download can't be trusted and should be discarded. A
         * non-fatal error (dropped connection, DNS blip) leaves the .part file alone so the
         * next attempt resumes rather than re-downloading the whole model.
         */
        data class Failure(val message: String, val fatal: Boolean = false) : Result()
    }

    /**
     * Diagnostics for a long, failure-prone transfer over a phone network. Debug-only so
     * release builds stay silent, but kept in the shipping code path because resuming an
     * interrupted 800 MB download is exactly the sort of thing that needs explaining later.
     */
    fun log(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(LOG_TAG, message)
    }

    /** Bytes already on disk from a previous interrupted attempt. */
    fun partialBytes(partFile: File): Long = if (partFile.exists()) partFile.length() else 0L

    /**
     * Downloads the model to [partFile], resuming from whatever is already there, then verifies
     * it and moves it into place at [destFile].
     *
     * [onProgress] receives (bytesOnDisk, totalBytes) so callers can show progress that stays
     * correct across resumes.
     */
    fun download(
        partFile: File,
        destFile: File,
        onProgress: (bytesOnDisk: Long, totalBytes: Long) -> Unit
    ): Result {
        partFile.parentFile?.mkdirs()
        val alreadyHave = partialBytes(partFile)
        log("=== download requested, resuming from $alreadyHave bytes ===")
        log("part=${partFile.absolutePath} exists=${partFile.exists()}")

        var lastError = "No mirrors configured."

        for ((index, mirror) in ModelSources.mirrors.withIndex()) {
            if (index > 0 && partFile.exists()) {
                // A different mirror may serve different bytes; don't splice across hosts.
                log("mirror switch -> discarding ${partFile.length()} bytes from previous mirror")
                partFile.delete()
            }
            log("mirror[$index] '${mirror.label}' url=${mirror.url}")

            when (val result = downloadResumable(mirror.url, partFile, alreadyHave, onProgress)) {
                is Result.Failure -> {
                    lastError = result.message
                    val partialNow = partialBytes(partFile)
                    log("ERROR fatal=${result.fatal} msg='${result.message}' partialNow=$partialNow")
                    if (result.fatal) {
                        log("fatal -> DELETING .part (was $partialNow bytes)")
                        partFile.delete()
                    } else {
                        log("recoverable -> KEEPING .part ($partialNow bytes) for next attempt")
                    }
                }

                Result.Success -> {
                    log("verifying sha256 against '${mirror.sha256}'")
                    val mismatch = verifySha256(partFile, mirror.sha256)
                    if (mismatch != null) {
                        log("SHA MISMATCH -> $mismatch; discarding partial and trying next mirror")
                        // Corrupt bytes — a resume would only preserve the bad prefix.
                        partFile.delete()
                        lastError = mismatch
                        continue
                    }
                    log("sha256 OK")

                    // Atomic swap of the completed file into place.
                    if (destFile.exists() && !destFile.delete()) {
                        lastError = "Couldn't replace the existing model file."
                        log("FAILED to delete old dest: $lastError")
                        continue
                    }
                    if (!partFile.renameTo(destFile)) {
                        lastError = "Failed to commit downloaded model file."
                        log("FAILED to rename .part -> dest: $lastError")
                        continue
                    }
                    log("committed model to ${destFile.absolutePath} (${destFile.length()} bytes)")
                    return Result.Success
                }
            }
        }

        log("=== download failed: $lastError ===")
        // Note: a recoverable failure deliberately leaves the .part file in place so the next
        // attempt continues from where it stopped.
        return Result.Failure(lastError, fatal = false)
    }

    /**
     * One HTTP transfer. Resumes via `Range: bytes=N-` when [existingBytes] > 0; a `200` reply to
     * a range request means the server resent the whole file, so we truncate rather than splice.
     */
    private fun downloadResumable(
        urlString: String,
        targetFile: File,
        existingBytes: Long,
        onProgress: (bytesOnDisk: Long, totalBytes: Long) -> Unit
    ): Result {
        var currentUrl = urlString
        var redirects = 0
        // Tracks whether the bytes already on disk can be trusted for a future resume. A
        // connection that dies mid-transfer is safe to resume: everything written so far came
        // straight off the wire in order. What is NOT safe is a partial prefix that might have
        // come from a stale or mismatched source.
        var prefixIsTrustworthy = false
        val maxRedirects = Constants.MAX_DOWNLOAD_REDIRECTS

        while (redirects < maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                val onDiskNow = if (targetFile.exists()) targetFile.length() else 0L
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Android/FinanceApp")
                    setRequestProperty("Accept", "application/octet-stream")
                    if (onDiskNow > 0) {
                        setRequestProperty("Range", "bytes=$onDiskNow-")
                    }
                    instanceFollowRedirects = false // Manual handling prevents protocol-drop issues
                    connectTimeout = Constants.DOWNLOAD_CONNECT_TIMEOUT_MS
                    readTimeout = Constants.DOWNLOAD_READ_TIMEOUT_MS
                }

                log("GET url=$currentUrl")
                log("GET sent Range=${connection.getRequestProperty("Range")} onDisk=$onDiskNow")

                val responseCode = connection.responseCode
                log("HTTP $responseCode contentLength=${connection.contentLengthLong} " +
                        "contentRange=${connection.getHeaderField("Content-Range")} " +
                        "acceptRanges=${connection.getHeaderField("Accept-Ranges")}")

                // Handle Redirects manually (HTTP 301, 302, 303, 307, 308)
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location == null) {
                        log("redirect with no Location header -> giving up")
                        return Result.Failure("Redirected with no Location header.")
                    }
                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirects++
                    log("redirect $redirects -> $currentUrl")
                    continue
                }

                if (responseCode !in 200..299) {
                    // 416 means our partial file is longer than the current asset — the
                    // release was replaced, so the old bytes are worthless.
                    val fatal = responseCode == 416
                    val message = if (fatal) {
                        "Downloaded model is out of date with the published file. Starting over."
                    } else {
                        "Server returned HTTP response code: $responseCode"
                    }
                    log("non-2xx $responseCode -> fatal=$fatal")
                    return Result.Failure(message, fatal = fatal)
                }

                val resuming = responseCode == 206 && onDiskNow > 0

                // Once bytes start landing on disk in order, the prefix is good even if the
                // connection then dies. This is the common case: a 200 here is a normal fresh
                // download, not a rejected resume.
                prefixIsTrustworthy = true

                // Total size of the *whole* asset, not just this response body.
                val responseLength = connection.contentLengthLong
                val contentRange = connection.getHeaderField("Content-Range")
                val totalBytes = when {
                    resuming && contentRange != null && '/' in contentRange ->
                        contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                    responseLength > 0 && resuming -> onDiskNow + responseLength
                    responseLength > 0 -> responseLength
                    else -> -1L
                }
                log("resuming=$resuming append=${resuming && onDiskNow > 0} " +
                        "responseLength=$responseLength totalBytes=$totalBytes")

                // We asked to resume and got a 200, so the server is re-sending the whole
                // file. Appending would splice two copies together, so truncate and restart.
                if (onDiskNow > 0 && !resuming) {
                    prefixIsTrustworthy = false
                    log("WARN asked to resume but got $responseCode -> restarting from 0")
                }

                var bytesOnDisk = if (resuming) onDiskNow else 0L
                val append = resuming && onDiskNow > 0
                var lastReported = -1L

                onProgress(bytesOnDisk, totalBytes)

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile, append).use { output ->
                        val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_BYTES)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesOnDisk += bytesRead
                            if (totalBytes > 0) {
                                // Report at most ~1% steps to avoid flooding the caller.
                                val pct = bytesOnDisk * 100 / totalBytes
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(bytesOnDisk, totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                log("stream done: bytesOnDisk=$bytesOnDisk totalBytes=$totalBytes " +
                        "fileOnDisk=${targetFile.length()}")
                if (totalBytes > 0 && bytesOnDisk != totalBytes) {
                    val fatal = !prefixIsTrustworthy
                    log("size mismatch -> fatal=$fatal")
                    return Result.Failure(
                        "Download incomplete: expected $totalBytes bytes, got $bytesOnDisk.",
                        fatal = fatal
                    )
                }

                log("transfer complete, $bytesOnDisk bytes")
                onProgress(bytesOnDisk, if (totalBytes > 0) totalBytes else bytesOnDisk)
                return Result.Success

            } catch (e: Exception) {
                // UnknownHostException in particular means DNS failed — common on emulators
                // and on captive portals, and worth calling out separately from a timeout.
                val message = if (e is java.net.UnknownHostException) {
                    "Couldn't reach the download host. Check your internet connection " +
                            "(DNS may be blocked on this network) and try again."
                } else {
                    "Network error: ${e.localizedMessage ?: e::class.java.simpleName}"
                }
                // Recoverable as long as we were mid-transfer: whatever reached the disk is a
                // correct in-order prefix, and Range will pick up from there next attempt.
                val fatal = !prefixIsTrustworthy
                val onDisk = if (targetFile.exists()) targetFile.length() else 0L
                log("EXCEPTION ${e::class.java.name}: ${e.message}")
                log("  prefixIsTrustworthy=$prefixIsTrustworthy fatal=$fatal onDisk=$onDisk")
                return Result.Failure(message, fatal = fatal)
            } finally {
                connection?.disconnect()
            }
        }

        return Result.Failure("Too many redirects.")
    }

    /**
     * Returns null when the file matches (or no hash was configured), otherwise a message
     * describing the mismatch. A wrong hash means the bytes on disk aren't the ones we expect,
     * and handing those to the inference runtime would fail opaquely at load time.
     */
    private fun verifySha256(file: File, expected: String): String? {
        if (expected.isBlank()) return null
        val actual = run {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(Constants.HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        return if (actual.equals(expected, ignoreCase = true)) null
        else "Model file failed integrity check (sha256 mismatch)."
    }
}
