package com.hawwwran.photosonthisday

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import androidx.room.Room
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.currentMonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.RoomDayIndexStore
import com.hawwwran.photosonthisday.data.ThumbnailCacheWiper
import com.hawwwran.photosonthisday.data.db.AppDatabase
import com.hawwwran.photosonthisday.session.AccountDataWiper
import com.hawwwran.photosonthisday.session.SessionManager
import com.hawwwran.photosonthisday.session.SessionStore
import com.hawwwran.photosonthisday.session.sessionDataStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point and the single place that owns long-lived objects. Manual
 * construction rather than a DI framework (decision 007): the graph is small enough to read
 * in one screen.
 */
class OnThisDayApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /**
     * One image loader for the app, fetching over the same OkHttp client as the API so the TLS
     * and no-cleartext rules hold for images too. Thumbnails are cached by a session-independent
     * key (see [com.hawwwran.photosonthisday.api.ThumbnailRef]), so its default disk cache
     * survives a sign-out.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .crossfade(true)
            .build()
}

/**
 * The object graph, built once. Plan 004 adds the image loader; each cache registers an
 * [AccountDataWiper] so decision 006's wipe reaches it.
 */
class AppGraph(context: Context) {

    /**
     * No custom trust manager, no cleartext, no redirect following: a redirect from the API is
     * never expected, and refusing them all is the simplest way to rule out a downgrade.
     * Connection failures are not retried either, so a login attempt is exactly one attempt.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    val client = SynologyClient(http)
    val authApi = AuthApi(client)
    val timelineApi = TimelineApi(client)
    val itemApi = ItemApi(client)
    val sessionStore = SessionStore(context.sessionDataStore)

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        AppDatabase.NAME,
    ).build()

    /** Filled as caches appear; read at wipe time, so registration order is free. */
    val accountDataWipers = mutableListOf<AccountDataWiper>()

    /** Cleared only when the account changes, so a same-account sign-out keeps cached images. */
    private val thumbnailWiper = ThumbnailCacheWiper(context.applicationContext)

    val sessions = SessionManager(authApi, sessionStore, accountDataWipers, listOf(thumbnailWiper))

    val dayIndex = DayIndexRepository(
        store = RoomDayIndexStore(database.dayIndexDao()),
        timelineApi = timelineApi,
        itemApi = itemApi,
        today = { currentMonthDay() },
        onSessionExpired = { sessions.onSessionExpired() },
    ).also { accountDataWipers += it }
}
