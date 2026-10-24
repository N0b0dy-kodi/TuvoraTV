package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.guideDisplayStore by preferencesDataStore(name = "tv_guide_display")

/** Device-local display preference: no account sync or network work. */
class GuideDisplayPreferences(context: Context) {
    private val store = context.applicationContext.guideDisplayStore
    private val layoutKey = stringPreferencesKey("layout")
    val layout = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }.map { prefs -> GuideLayoutPreset.fromStored(prefs[layoutKey]) }
    suspend fun setLayout(layout: GuideLayoutPreset) { store.edit { it[layoutKey] = layout.name } }
}
