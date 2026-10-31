package app.parabol.ui

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Image loading for posters and channel logos.
 *
 * Poster links from providers are a mix of hosts (their own server, TMDB copies, CDNs).
 * Some of those refuse requests without a normal user agent, or answer "too many requests"
 * when a grid asks for dozens of posters at once; a poster then stayed empty. Here every
 * image request carries a user agent and is retried a few times, and images are kept on
 * the device, so a poster that loaded once doesn't need the network again.
 */
object ParabolImages {

    /** Shown under Settings › About, to see which posters fail and why. */
    val loaded = AtomicInteger()
    val failed = AtomicInteger()
    @Volatile var lastFailure: String = ""

    fun create(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgent)
            .addInterceptor(Retry)
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            // Many image servers send "no-cache"; posters hardly change, so keep them anyway.
            .respectCacheHeaders(false)
            .eventListener(object : coil.EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    loaded.incrementAndGet()
                }
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    failed.incrementAndGet()
                    val url = request.data.toString()
                    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url.take(40)
                    lastFailure = "$host: ${result.throwable.javaClass.simpleName} ${result.throwable.message.orEmpty().take(60)}"
                }
            })
            .build()
    }

    private object UserAgent : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Parabol")
                .header("Accept", "image/webp,image/*,*/*;q=0.8")
                .build()
        )
    }

    /** Up to 3 attempts on a network error, "too many requests" (429) or a server error (5xx). */
    private object Retry : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val response = chain.proceed(chain.request())
                    val retry = response.code == 429 || response.code in 500..599
                    if (!retry || attempt >= 3) return response
                    response.close()
                } catch (e: IOException) {
                    if (attempt >= 3) throw e
                }
                Thread.sleep(500L * attempt)
            }
        }
    }
}
