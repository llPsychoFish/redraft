package com.redraft.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val RECENTS_JSON_KEY = stringPreferencesKey("recents_json")

/**
 * The one DataStore instance for the recents file, per process.
 *
 * DataStore allows exactly one active instance per file: a second one throws
 * `IllegalStateException: There are multiple DataStores active for the same
 * file` the first time it reads. Creating the store with
 * `PreferenceDataStoreFactory.create { ... }` inside a constructor produced a
 * NEW instance for every `RecentFilesRepository`, so a second ViewModel built
 * while the first was still alive crashed the app. The `preferencesDataStore`
 * delegate builds the store once, against the application context, and hands
 * the same instance to every caller.
 */
private val Context.recentsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recents",
)

interface RecentsStore {
    val recents: Flow<List<RecentFile>>
    suspend fun addOrUpdate(file: RecentFile)
    suspend fun remove(uri: String)
    suspend fun clear()
}

class RecentFilesRepository(
    private val dataStore: DataStore<Preferences>,
) : RecentsStore {

    constructor(context: Context) : this(context.applicationContext.recentsDataStore)

    override val recents: Flow<List<RecentFile>> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> decode(preferences[RECENTS_JSON_KEY]) }

    override suspend fun addOrUpdate(file: RecentFile) {
        dataStore.edit { preferences ->
            preferences[RECENTS_JSON_KEY] = encode(decode(preferences[RECENTS_JSON_KEY]).upsert(file))
        }
    }

    override suspend fun remove(uri: String) {
        dataStore.edit { preferences ->
            val remaining = decode(preferences[RECENTS_JSON_KEY]).filterNot { it.uri == uri }
            if (remaining.isEmpty()) {
                preferences.remove(RECENTS_JSON_KEY)
            } else {
                preferences[RECENTS_JSON_KEY] = encode(remaining)
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(RECENTS_JSON_KEY) }
    }

    private fun encode(files: List<RecentFile>): String {
        val array = JSONArray()
        files.forEach { file ->
            array.put(
                JSONObject().apply {
                    put(FIELD_URI, file.uri)
                    put(FIELD_NAME, file.displayName)
                    put(FIELD_TIMESTAMP, file.lastOpenedEpochMs)
                },
            )
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<RecentFile> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = entry.optString(FIELD_URI).takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                RecentFile(
                    uri = uri,
                    displayName = entry.optString(FIELD_NAME).takeIf { it.isNotEmpty() } ?: uri,
                    lastOpenedEpochMs = entry.optLong(FIELD_TIMESTAMP, 0L),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private companion object {
        const val FIELD_URI = "uri"
        const val FIELD_NAME = "name"
        const val FIELD_TIMESTAMP = "ts"
    }
}
