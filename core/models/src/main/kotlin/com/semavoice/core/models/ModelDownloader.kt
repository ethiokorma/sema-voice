package com.semavoice.core.models

import com.semavoice.core.common.AppError
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DownloadProgress(val fileName: String, val bytesDone: Long, val bytesTotal: Long) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Resumable, checksum-verified HTTP downloader. Writes to "<name>.part" with HTTP Range
 * resume, then verifies SHA-256 before renaming into place. A file that fails
 * verification is deleted, never loaded.
 */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    suspend fun download(
        modelId: String,
        file: ModelFile,
        destination: File,
        onProgress: (DownloadProgress) -> Unit,
    ): Outcome<File> = withContext(Dispatchers.IO) {
        val part = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            var existing = if (part.exists()) part.length() else 0L
            if (existing >= file.sizeBytes && file.sizeBytes > 0) existing = 0L.also { part.delete() }
            val request = Request.Builder().url(file.url).apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416) { part.delete(); existing = 0 }
                if (!response.isSuccessful && response.code != 206) {
                    return@withContext Outcome.Failure(AppError.DownloadInterrupted(modelId, IOException("HTTP ${response.code}")))
                }
                val resumed = response.code == 206 && existing > 0
                if (!resumed && existing > 0) { part.delete(); existing = 0 }
                val body = response.body ?: return@withContext Outcome.Failure(AppError.DownloadInterrupted(modelId))
                val total = if (file.sizeBytes > 0) file.sizeBytes else existing + body.contentLength()
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(existing)
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024 || done == total) {
                                lastReport = done
                                onProgress(DownloadProgress(file.name, done, total))
                            }
                        }
                    }
                }
            }
            if (file.sizeBytes > 0 && part.length() != file.sizeBytes) {
                return@withContext Outcome.Failure(AppError.DownloadInterrupted(modelId, IOException("size mismatch ${part.length()} != ${file.sizeBytes}")))
            }
            onProgress(DownloadProgress(file.name, part.length(), part.length()))
            val sha = sha256(part)
            if (!sha.equals(file.sha256, ignoreCase = true)) {
                SemaLog.e(TAG, "checksum mismatch for ${file.name}")
                part.delete()
                return@withContext Outcome.Failure(AppError.ChecksumMismatch(modelId))
            }
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true); part.delete()
            }
            Outcome.Success(destination)
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            SemaLog.w(TAG, "download interrupted: ${file.name}", e)
            Outcome.Failure(AppError.DownloadInterrupted(modelId, e))
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
