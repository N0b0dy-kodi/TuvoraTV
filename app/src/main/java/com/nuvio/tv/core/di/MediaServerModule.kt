package com.nuvio.tv.core.di

import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.mediaserver.AndroidServerDeviceIdStore
import com.nuvio.tv.data.mediaserver.AndroidServerPersistence
import com.nuvio.tv.data.mediaserver.AndroidServerTrustStore
import com.nuvio.tv.data.mediaserver.ServerDeviceId
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerTrust
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object MediaServerModule {
    @Provides
    @Singleton
    fun trust(store: AndroidServerTrustStore): ServerTrust = ServerTrust(store)

    @Provides
    @Singleton
    fun repository(
        persistence: AndroidServerPersistence,
        deviceIdStore: AndroidServerDeviceIdStore,
        identity: SyncClientIdentity,
        profileDataStore: ProfileDataStore,
        trust: ServerTrust
    ): ServerRepository {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        // Chosen once: a device that already signed in keeps the id its tokens are bound to; a new one gets the Tuvora prefix.
        val serverDeviceId = ServerDeviceId(deviceIdStore, identity::currentClientId, persistence::hasAny)
        val clientIdentity = ServerClientIdentity(
            device = Build.MODEL.orEmpty().ifBlank { "Android TV" },
            version = BuildConfig.VERSION_NAME,
            deviceId = serverDeviceId::current
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = ServerRepository(
            persistence = persistence,
            providers = listOf(JellyfinProvider(http, clientIdentity, trust), EmbyProvider(http, clientIdentity, trust)),
            scope = scope,
            trust = trust
        )
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        return repository
    }

    @Provides
    @IntoSet
    fun credentialStore(repository: ServerRepository): ProfileScopedCredentialStore = repository
}
