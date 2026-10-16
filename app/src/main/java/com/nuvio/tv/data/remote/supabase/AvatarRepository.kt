package com.nuvio.tv.data.remote.supabase

import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.MemberCatalogStorage
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

/**
 * Fork: resolves both the Postgrest client and the avatar base URL live through
 * [SyncBackendSupabaseProvider] rather than holding @Singleton snapshots or reading BuildConfig.
 *
 * The base URL has to come from the selected backend because it differs per backend, and
 * [com.nuvio.tv.core.network.SyncBackendDefaults] already derives it from that backend's Supabase
 * URL when AVATAR_PUBLIC_BASE_URL is unset. Reading BuildConfig.AVATAR_PUBLIC_BASE_URL directly
 * bypassed that derivation, and since the property is unset it yielded an empty base — so
 * avatarImageUrl() returned a bare storage_path, which Coil cannot load. Result: no preset avatar
 * ever rendered on TV, even with a populated catalog.
 *
 * Resolving live also makes SyncBackendSwitchService's invalidateCache() actually sufficient: the
 * refetch after a switch now hits the new backend and builds URLs against it.
 */
@Singleton
class AvatarRepository @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        // Read once so every URL in this batch is built against the backend we actually queried,
        // even if a switch lands mid-fetch.
        val backend = supabaseProvider.selectedBackend

        val response = supabaseProvider.postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        val catalog = remote.map { item ->
            AvatarCatalogItem(
                id = item.id,
                displayName = item.displayName,
                imageUrl = backend.avatarStorageUrl(item.storagePath),
                category = item.category,
                sortOrder = item.sortOrder,
                bgColor = item.bgColor
            )
        }
        cachedCatalog = catalog
        return catalog
    }

    suspend fun shareableImageUrl(profile: UserProfile): String? {
        val storageUrl = "${supabaseProvider.selectedBackend.normalizedSupabaseUrl}/storage/v1/object/"
        profile.avatarUrl?.trim()?.takeIf { it.startsWith(storageUrl) }?.let { return it }
        return getAvatarCatalog().firstOrNull { it.id == profile.avatarId }?.imageUrl
            ?.takeIf { it.startsWith(storageUrl) }
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }
}
