package com.hawwwran.photosonthisday.update

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The APK download, as the view model sees it; [UpdateDownloader] is the real one. */
fun interface UpdateDownloading {
    fun download(url: String, version: String, expectedSize: Long): Flow<UpdateDownloader.DownloadProgress>
}

/**
 * Streams the release APK to the app cache, emitting progress as a cold Flow. Cancel the
 * collecting coroutine to abort: the partial file is deleted and the wake lock released. A
 * `PARTIAL_WAKE_LOCK` is held for the download so a screen-off pause does not stall it.
 *
 * A target that already exists with the expected size is reused: the user who came back from the
 * install-permission page must not download the whole APK a second time.
 */
class UpdateDownloader(
    private val cacheDir: File,
    private val wakeLockFactory: () -> WakeLockHolder,
    /**
     * Derived from the app's client for its TLS and timeout rules, with redirects switched back on:
     * GitHub answers `browser_download_url` with a 302 to its asset host, which the API client's
     * no-redirect policy would refuse.
     */
    appClient: OkHttpClient,
) : UpdateDownloading {
    fun interface WakeLockHolder {
        fun release()
    }

    sealed class DownloadProgress {
        data object Started : DownloadProgress()
        data class Progress(val bytesRead: Long, val total: Long) : DownloadProgress()
        data class Done(val file: File) : DownloadProgress()
        data class Failed(val reason: UpdateFailure) : DownloadProgress()
    }

    private val client: OkHttpClient = appClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun download(url: String, version: String, expectedSize: Long): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Started)
        val targetDir = File(cacheDir, "updates").apply { mkdirs() }
        val target = File(targetDir, "OnThisDay-$version.apk")
        if (expectedSize > 0 && target.isFile && target.length() == expectedSize) {
            emit(DownloadProgress.Progress(expectedSize, expectedSize))
            emit(DownloadProgress.Done(target))
            return@flow
        }
        val partial = File(targetDir, target.name + ".partial")
        partial.delete()

        val wakeLock = wakeLockFactory()
        var success = false
        try {
            awaitResponse(Request.Builder().url(url).build()).use { resp ->
                if (!resp.isSuccessful) {
                    UpdateLog.downloadFailed(UpdateFailure.DOWNLOAD_HTTP)
                    emit(DownloadProgress.Failed(UpdateFailure.DOWNLOAD_HTTP))
                    return@flow
                }
                val body = resp.body
                val total = body.contentLength()
                var bytesRead = 0L
                var lastEmitAt = 0L
                val temp = okio.Buffer()
                partial.sink().buffer().use { sink ->
                    val source = body.source()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(temp, BUFFER_SIZE)
                        if (read == -1L) break
                        sink.write(temp, read)
                        bytesRead += read
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastEmitAt >= PROGRESS_EMIT_INTERVAL_MS) {
                            emit(DownloadProgress.Progress(bytesRead, total))
                            lastEmitAt = nowMs
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                // A dropped connection can look like a clean EOF; if Content-Length was
                // advertised and we got fewer bytes, the file is a truncated APK the installer
                // would reject as "package invalid". Fail loudly instead.
                if (total > 0 && bytesRead != total) {
                    UpdateLog.downloadFailed(UpdateFailure.DOWNLOAD_INCOMPLETE)
                    emit(DownloadProgress.Failed(UpdateFailure.DOWNLOAD_INCOMPLETE))
                    return@flow
                }
                if (!partial.renameTo(target)) {
                    UpdateLog.downloadFailed(UpdateFailure.DOWNLOAD_IO)
                    emit(DownloadProgress.Failed(UpdateFailure.DOWNLOAD_IO))
                    return@flow
                }
                success = true
                emit(DownloadProgress.Progress(bytesRead, total))
                emit(DownloadProgress.Done(target))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UpdateLog.downloadFailed(UpdateFailure.DOWNLOAD_IO, e)
            emit(DownloadProgress.Failed(UpdateFailure.DOWNLOAD_IO))
        } finally {
            if (!success) partial.delete()
            wakeLock.release()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun awaitResponse(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    companion object {
        private const val BUFFER_SIZE = 64L * 1024L
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60 * 1000

        fun forContext(context: Context, appClient: OkHttpClient) = UpdateDownloader(
            cacheDir = context.cacheDir,
            wakeLockFactory = {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnThisDay::UpdateDownload").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                WakeLockHolder { if (wl.isHeld) wl.release() }
            },
            appClient = appClient,
        )
    }
}
