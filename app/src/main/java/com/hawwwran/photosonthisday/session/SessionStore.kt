package com.hawwwran.photosonthisday.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hawwwran.photosonthisday.api.SessionCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A signed-in account: where, who, and what proves it. */
data class Session(
    val baseUrl: HttpUrl,
    val account: String,
    val credentials: SessionCredentials,
)

sealed interface SessionState {
    data object Loading : SessionState

    data class SignedIn(val session: Session) : SessionState

    /**
     * No usable session. [expired] is true when DSM ended it, so the sign-in screen can say so.
     * The last address and account are kept to prefill the form; they are not credentials.
     */
    data class SignedOut(
        val expired: Boolean,
        val lastBaseUrl: String?,
        val lastAccount: String?,
    ) : SessionState
}

val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Decision 003 made concrete: the session id, the token and the trusted-device id are stored;
 * the password never reaches this class. DataStore in the app's private directory, which
 * file-based encryption already covers, and `allowBackup=false` keeps it off the cloud.
 */
class SessionStore(private val dataStore: DataStore<Preferences>) {

    val state: Flow<SessionState> = dataStore.data.map { prefs ->
        val sid = prefs[SID]
        val baseUrl = prefs[BASE_URL]
        val account = prefs[ACCOUNT]
        if (sid != null && baseUrl != null && account != null) {
            SessionState.SignedIn(
                Session(baseUrl.toHttpUrl(), account, SessionCredentials(sid, prefs[SYNOTOKEN])),
            )
        } else {
            SessionState.SignedOut(
                expired = prefs[EXPIRED] ?: false,
                lastBaseUrl = baseUrl,
                lastAccount = account,
            )
        }
    }

    suspend fun current(): Session? = (state.first() as? SessionState.SignedIn)?.session

    /** The account last signed in, kept after sign-out so a change of account can be detected. */
    suspend fun lastAccount(): String? = dataStore.data.first()[ACCOUNT]

    /** Survives sign-out and account changes: it belongs to the device, not the account. */
    suspend fun deviceId(): String? = dataStore.data.first()[DEVICE_ID]

    /** The NAS folder holding the likes file (decision 008). Default is the account's home. */
    fun likesFolder(): Flow<String> = dataStore.data.map { it[LIKES_FOLDER] ?: DEFAULT_LIKES_FOLDER }

    suspend fun setLikesFolder(path: String) {
        dataStore.edit { it[LIKES_FOLDER] = path.trim().trimEnd('/').ifEmpty { DEFAULT_LIKES_FOLDER } }
    }

    /**
     * How the always-on-top liked group is arranged. False (default): one blob of all liked.
     * True: the liked split into per-year groups. Either way the liked stay at the top and are
     * never mixed into the year sections below.
     */
    fun likedByYear(): Flow<Boolean> = dataStore.data.map { it[LIKED_BY_YEAR] ?: false }

    suspend fun setLikedByYear(value: Boolean) {
        dataStore.edit { it[LIKED_BY_YEAR] = value }
    }

    suspend fun save(session: Session, deviceId: String?) {
        dataStore.edit { prefs ->
            prefs[BASE_URL] = session.baseUrl.toString()
            prefs[ACCOUNT] = session.account
            prefs[SID] = session.credentials.sid
            session.credentials.synotoken?.let { prefs[SYNOTOKEN] = it } ?: prefs.remove(SYNOTOKEN)
            deviceId?.let { prefs[DEVICE_ID] = it }
            prefs.remove(EXPIRED)
        }
    }

    /** DSM ended the session: drop what proved it, remember that it happened. */
    suspend fun markExpired() {
        dataStore.edit { prefs ->
            prefs.remove(SID)
            prefs.remove(SYNOTOKEN)
            prefs[EXPIRED] = true
        }
    }

    /** Sign-out: the credentials go, the address and account stay to prefill the form. */
    suspend fun clearCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(SID)
            prefs.remove(SYNOTOKEN)
            prefs.remove(EXPIRED)
        }
    }

    companion object {
        const val DEFAULT_LIKES_FOLDER = "/home/OnThisDay"
        private val BASE_URL = stringPreferencesKey("base_url")
        private val ACCOUNT = stringPreferencesKey("account")
        private val SID = stringPreferencesKey("sid")
        private val SYNOTOKEN = stringPreferencesKey("synotoken")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val EXPIRED = booleanPreferencesKey("expired")
        private val LIKES_FOLDER = stringPreferencesKey("likes_folder")
        private val LIKED_BY_YEAR = booleanPreferencesKey("liked_by_year")
    }
}
