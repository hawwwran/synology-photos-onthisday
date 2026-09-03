package com.hawwwran.photosonthisday

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import androidx.room.Room
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.FolderApi
import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.currentMonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.ImageSaver
import com.hawwwran.photosonthisday.data.MediaSharer
import com.hawwwran.photosonthisday.data.RoomDayIndexStore
import com.hawwwran.photosonthisday.data.ThumbnailCacheWiper
import com.hawwwran.photosonthisday.data.buildImageLoader
import com.hawwwran.photosonthisday.likes.FileStationClient
import com.hawwwran.photosonthisday.likes.LikeRepository
import com.hawwwran.photosonthisday.likes.LikesNasStore
import kotlinx.coroutines.flow.first
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
     * One image loader for the app; see [buildImageLoader] for the guards around its disk cache.
     * Thumbnails are cached by a session-independent key
     * (see [com.hawwwran.photosonthisday.api.ThumbnailRef]), so the disk cache survives a sign-out.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context, graph.http)
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
    val folderApi = FolderApi(client)
    val sessionStore = SessionStore(context.sessionDataStore)

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        AppDatabase.NAME,
    ).fallbackToDestructiveMigration(dropAllTables = true).build()  // pre-release; the index and likes rebuild from the NAS

    /** Filled as caches appear; read at wipe time, so registration order is free. */
    val accountDataWipers = mutableListOf<AccountDataWiper>()

    /** Cleared when the account changes and from Settings; a same-account sign-out keeps cached images. */
    val thumbnailWiper = ThumbnailCacheWiper(context.applicationContext)

    val imageSaver = ImageSaver(context.applicationContext, http)
    val mediaSharer = MediaSharer(context.applicationContext, http)

    val sessions = SessionManager(authApi, sessionStore, accountDataWipers, listOf(thumbnailWiper))

    val dayIndex = DayIndexRepository(
        store = RoomDayIndexStore(database.dayIndexDao()),
        timelineApi = timelineApi,
        itemApi = itemApi,
        today = { currentMonthDay() },
        onSessionExpired = { sid -> sessions.onSessionExpired(sid) },
    ).also { accountDataWipers += it }

    private val fileStationClient = FileStationClient(http)
    private val likesNasStore = LikesNasStore(fileStationClient)

    val likes = LikeRepository(
        dao = database.likeDao(),
        nas = likesNasStore,
        folder = { sessionStore.likesFolder().first() },
        onSessionExpired = { sid -> sessions.onSessionExpired(sid) },
    ).also { accountDataWipers += it }
}
