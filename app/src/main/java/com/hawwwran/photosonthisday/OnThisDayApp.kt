package com.hawwwran.photosonthisday

import android.app.Application
import android.content.Context
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.SynologyClient
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
class OnThisDayApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/**
 * The object graph, built once. Plan 003 adds the database and plan 004 the image loader;
 * each registers an [AccountDataWiper] so decision 006's wipe reaches it.
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
    val sessionStore = SessionStore(context.sessionDataStore)

    /** Filled by later plans as their caches appear. Read at wipe time, so order of registration is free. */
    val accountDataWipers = mutableListOf<AccountDataWiper>()

    val sessions = SessionManager(authApi, sessionStore, accountDataWipers)
}
