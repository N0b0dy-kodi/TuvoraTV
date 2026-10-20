package com.nuvio.tv.reliability

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/** Opt-in, disposable-emulator integration with real HTTP/serialization/repositories.
 * Run only with -e isolatedReleaseGate true on the dedicated release-check AVD.
 */
@RunWith(AndroidJUnit4::class)
class SourceJourneyHttpDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun guard() {
        org.junit.Assume.assumeTrue("Requires disposable release-check emulator and local HTTP fixture",
            InstrumentationRegistry.getArguments().getString("isolatedReleaseGate") == "true")
        org.junit.Assume.assumeTrue("Emulator-only fixture", Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
    }
    @Suppress("UNCHECKED_CAST") private fun <T> binding(name: String): T {
        val component = (context.applicationContext as GeneratedComponentManager<*>).generatedComponent()!!
        val field = component.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return (field.get(component) as Provider<*>).get() as T
    }
    private fun control(json: String) {
        OkHttpClient().newCall(Request.Builder().url("http://10.0.2.2:9030/control")
            .post(json.toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful) }
    }
    @Test fun fastRowsArriveWhileManifestPendingAndFailedManifestCanRetry(): Unit = runBlocking {
        guard()
        val preferences = binding<AddonPreferences>("addonPreferencesProvider")
        val addons = binding<AddonRepositoryImpl>("addonRepositoryImplProvider")
        val streams = binding<StreamRepositoryImpl>("streamRepositoryImplProvider")
        val original = preferences.installedAddonUrls.first()
        check(original.all { it.contains("10.0.2.2") || it.contains("cinemeta") || it == "https://opensubtitles-v3.strem.io" }) { "Refusing non-fixture profile" }
        try {
            control("{\"slow\":5,\"fail\":false}")
            preferences.setAddonOrder(emptyList())
            withTimeout(10_000) { addons.getAddonDiscoveryState().first { it.initialized && it.addons.isEmpty() && it.pendingUrls.isEmpty() } }
            val ready = "http://10.0.2.2:9030/ready"; val slow = "http://10.0.2.2:9030/slow"
            preferences.setAddonOrder(listOf(ready, slow))
            withTimeout(10_000) { addons.getAddonDiscoveryState().first { slow in it.pendingUrls && it.addons.any { a -> a.name == "Gate ready source" } } }
            var firstUsablePending = false
            val result = withTimeout(20_000) {
                streams.getStreamsFromAllAddons("movie", "gate:movie", null, null, true).first { result ->
                    if (result is NetworkResult.Success && result.data.any { it.streams.isNotEmpty() }) {
                        firstUsablePending = slow in addons.getAddonDiscoveryState().first().pendingUrls
                        true
                    } else false
                }
            }
            assertTrue("Fast rows must precede slow manifest completion: $result", firstUsablePending)
            withTimeout(15_000) { addons.getAddonDiscoveryState().first { it.pendingUrls.isEmpty() && it.addons.size == 2 } }
            control("{\"fail\":true}")
            val failed = "http://10.0.2.2:9030/failed"
            preferences.setAddonOrder(listOf(failed))
            withTimeout(15_000) { addons.getAddonDiscoveryState().first { failed in it.failedUrls } }
            val failure = withTimeout(15_000) { streams.getStreamsFromAllAddons("movie", "gate:failed", null, null, true).first { it !is NetworkResult.Loading } }
            assertTrue("failed configuration must not become successful empty: $failure", failure is NetworkResult.Error)
            control("{\"fail\":false}")
            val retry = withTimeout(15_000) { streams.getStreamsFromAllAddons("movie", "gate:failed", null, null, true).first { it !is NetworkResult.Loading } }
            assertTrue("Explicit retry should recover: $retry", retry is NetworkResult.Success && retry.data.any { it.streams.isNotEmpty() })
            Log.i("HttpSourceGate", "fast-before-pending=true failure-distinct=true retry-recovered=true")
        } finally { control("{\"fail\":false,\"slow\":5}"); preferences.setAddonOrder(original) }
    }
    @Test fun realEmbyConnectionDiscoversMatchingMovieCandidates(): Unit = runBlocking {
        guard()
        val repository = binding<ServerRepository>("repositoryProvider")
        val streams = binding<ServerStreams>("serverStreamsProvider")
        check(repository.enabledConnections().all { it.address.contains("10.0.2.2") })
        val provider = repository.providers.first { it.id == "emby" }
        val connection = withTimeout(15_000) { repository.connect(provider, "http://10.0.2.2:9030", "fixture", "fixture") }
        try {
            assertTrue("real library must be discovered", connection.libraries.isNotEmpty())
            assertTrue(streams.canServe("movie", "tt0111161"))
            val sources = streams.sources("movie", "tt0111161", null, null, true)
            assertTrue(sources.isNotEmpty())
            val candidates = withTimeout(15_000) { sources.flatMap { it.load() } }
            assertTrue("real provider response must produce a deferred server candidate", candidates.any { it.serverTarget != null })
            control("{\"library_delay\":3}")
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            var stored = json.encodeToString(com.nuvio.tv.data.mediaserver.StoredServers.serializer(),
                com.nuvio.tv.data.mediaserver.StoredServers(listOf(connection.copy(libraries = emptyList())),
                    mapOf(connection.credentialRef to "fixture-token"), libraryRefreshPending = true))
            val persistence = object : com.nuvio.tv.data.mediaserver.ServerPersistence {
                override fun read(profileId: Int): String? = stored
                override fun write(profileId: Int, value: String?) { stored = value.orEmpty() }
                override fun clear() { stored = "" }
            }
            val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val cold = ServerRepository(persistence, repository.providers, coldScope)
                val coldStreams = ServerStreams(cold, com.nuvio.tv.data.mediaserver.ServerMatcher(cold,
                    binding("tmdbServiceProvider"), binding("metaRepositoryImplProvider")))
                cold.ensureLoaded()
                assertTrue("Cold libraries must still be pending", connection.id in cold.uiState.value.loadingLibraries)
                assertTrue("Pending cold server must remain eligible", coldStreams.canServe("movie", "tt0111161"))
                val coldCandidates = withTimeout(15_000) { coldStreams.sources("movie", "tt0111161", null, null).flatMap { it.load() } }
                assertTrue("Cold query must await real HTTP libraries and return candidates", coldCandidates.any { it.serverTarget != null })
            } finally { coldScope.cancel() }
        } finally { control("{\"library_delay\":0}"); repository.remove(connection.id) }
    }
    @Test fun prepareGuideUxFixture(): Unit = runBlocking {
        guard()
        val accounts = binding<com.nuvio.tv.data.local.XtreamAccountStore>("xtreamAccountStoreProvider")
        check(accounts.accounts.first().all { it.id in setOf("release-gate-large", "release-gate-ux") })
        val account = com.nuvio.tv.core.iptv.XtreamAccount("release-gate-ux", "Guide UX fixture",
            "http://10.0.2.2:9022/playlist-v2.m3u", "", "",
            sourceType = com.nuvio.tv.core.iptv.XtreamAccount.SOURCE_URL,
            epgUrl = "http://10.0.2.2:9022/guide.xml")
        binding<com.nuvio.tv.data.local.AppOnboardingDataStore>("appOnboardingDataStoreProvider").setHasSeenAuthQrOnFirstLaunch(true)
        binding<com.nuvio.tv.data.local.ExperienceModeDataStore>("experienceModeDataStoreProvider").setMode(com.nuvio.tv.domain.model.ExperienceMode.ADVANCED)
        binding<com.nuvio.tv.data.local.LayoutPreferenceDataStore>("layoutPreferenceDataStoreProvider").setLayout(com.nuvio.tv.domain.model.HomeLayout.MODERN)
        accounts.upsert(account)
        binding<com.nuvio.tv.data.local.XtreamHubSelectionStore>("xtreamHubSelectionStoreProvider").save(account.id, "LIVE")
        val factory = binding<com.nuvio.tv.core.iptv.IptvClientFactory>("iptvClientFactoryProvider")
        withTimeout(30_000) { factory.m3u().ensureIngested(account, force = true) }
        assertEquals(5, factory.clientFor(account).liveChannels(account).getOrThrow().size)
    }

    @Test fun sixThousandChannelM3uIngestRetainsEveryUniqueRow(): Unit = runBlocking {
        guard()
        val factory = binding<com.nuvio.tv.core.iptv.IptvClientFactory>("iptvClientFactoryProvider")
        val account = com.nuvio.tv.core.iptv.XtreamAccount("release-gate-large", "Gate large lineup",
            "http://10.0.2.2:9030/large.m3u", "", "", sourceType = com.nuvio.tv.core.iptv.XtreamAccount.SOURCE_URL)
        val start = android.os.SystemClock.elapsedRealtime()
        withTimeout(30_000) { factory.m3u().ensureIngested(account, force = true) }
        val channels = withTimeout(10_000) { factory.clientFor(account).liveChannels(account).getOrThrow() }
        assertEquals(6000, channels.size)
        assertEquals(6000, channels.map { it.streamId }.toSet().size)
        assertTrue(channels.any { it.name == "Gate Channel 0001" })
        assertTrue(channels.any { it.name == "Gate Channel 6000" })
        Log.i("HttpSourceGate", "large-lineup rows=${channels.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
    }

    @Test fun largeGuideRemoteNavigationAndBackKeepContext(): Unit = runBlocking {
        guard()
        val accounts = binding<com.nuvio.tv.data.local.XtreamAccountStore>("xtreamAccountStoreProvider")
        check(accounts.accounts.first().all { it.id == "release-gate-large" })
        val account = com.nuvio.tv.core.iptv.XtreamAccount("release-gate-large", "Gate large lineup",
            "http://10.0.2.2:9030/large.m3u", "", "", sourceType = com.nuvio.tv.core.iptv.XtreamAccount.SOURCE_URL)
        binding<com.nuvio.tv.data.local.AppOnboardingDataStore>("appOnboardingDataStoreProvider").setHasSeenAuthQrOnFirstLaunch(true)
        binding<com.nuvio.tv.data.local.ExperienceModeDataStore>("experienceModeDataStoreProvider").setMode(com.nuvio.tv.domain.model.ExperienceMode.ADVANCED)
        binding<com.nuvio.tv.data.local.LayoutPreferenceDataStore>("layoutPreferenceDataStoreProvider").setLayout(com.nuvio.tv.domain.model.HomeLayout.MODERN)
        if (InstrumentationRegistry.getArguments().getString("verifyExistingFixture") == "true") {
            assertTrue("Playlist must survive APK replacement", accounts.accounts.first().any { it.id == account.id })
            val remembered = binding<com.nuvio.tv.data.local.XtreamHubSelectionStore>("xtreamHubSelectionStoreProvider").read()
            assertEquals("Provider selection must survive APK replacement", account.id, remembered.accountId)
            assertEquals(6000, binding<com.nuvio.tv.core.iptv.IptvClientFactory>("iptvClientFactoryProvider")
                .clientFor(account).liveChannels(account).getOrThrow().size)
        }
        accounts.upsert(account)
        binding<com.nuvio.tv.data.local.XtreamHubSelectionStore>("xtreamHubSelectionStoreProvider").save(account.id, "LIVE")
        binding<com.nuvio.tv.core.iptv.IptvClientFactory>("iptvClientFactoryProvider").m3u().ensureIngested(account)
        // Connect accessibility before launch; its first root query can otherwise be null
        // until Compose has observed the service and published semantics.
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val intent = android.content.Intent(context, com.nuvio.tv.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        withTimeout(30_000) {
            while (automation.rootInActiveWindow?.packageName?.toString() != context.packageName ||
                (!screenText().contains("Home") && !screenText().contains("IPTV"))) delay(200)
        }
        delay(3500) // Let the startup overlay finish before dispatching remote focus input.
        screenshot("large-home-before-sidebar")
        // Production sidebar journey with the same remote input a viewer uses.
        repeat(3) { key(android.view.KeyEvent.KEYCODE_DPAD_LEFT); delay(200) }
        val sidebar = nodes().firstOrNull { it.text?.toString() == "IPTV" || it.contentDescription?.toString() == "IPTV" }
        check(sidebar != null) { "Precondition: IPTV sidebar entry not found. ${screenText()}" }
        var clickable: android.view.accessibility.AccessibilityNodeInfo? = sidebar
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        check(clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
        delay(1000)
        if (!screenText().contains("Gate Channel")) key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        withTimeout(20_000) { while (!screenText().contains("Gate Channel")) delay(200) }
        screenshot("large-guide-loaded")
        // From Live TV: Search, All favorites, Favorites, Recent, All channels, then rows.
        // Use the remote path: the metadata pane also contains the channel title and is not
        // a focusable row, so selecting the first matching accessibility text is ambiguous.
        repeat(5) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN); delay(100) }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); delay(300)
        val initial = focusedText()
        check(initial.contains("Gate Channel")) { "Channel-row remote precondition failed: $initial" }
        val start = android.os.SystemClock.elapsedRealtime()
        repeat(100) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN); delay(35) }
        delay(400)
        val after = awaitChangedFocus(initial)
        screenshot("large-guide-after-100-down")
        assertTrue("Focus must move through channel rows: $after", after.contains("Gate Channel") && initial != after)
        Log.i("HttpSourceGate", "large-guide keys=100 elapsedMs=${android.os.SystemClock.elapsedRealtime()-start} initial=$initial after=$after")
        val downTime = android.os.SystemClock.uptimeMillis()
        repeat(60) { count ->
            instrumentation.sendKeySync(android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN, count))
            delay(20)
        }
        instrumentation.sendKeySync(android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
            android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0))
        delay(500)
        val held = awaitChangedFocus(after)
        screenshot("large-guide-after-repeat")
        assertTrue("Injected held-key repeats must advance channel focus: $held", held.contains("Gate Channel") && held != after)
        Log.i("HttpSourceGate", "large-guide injectedRepeatEvents=60 after=$held")
        // Capture a selection and leave fullscreen using Back; establish the guide returns.
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER); delay(500)
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER); delay(1200)
        screenshot("large-guide-selected")
        key(android.view.KeyEvent.KEYCODE_BACK); delay(700)
        screenshot("large-guide-back")
        withTimeout(5_000) { while (!screenText().contains("Gate Channel")) delay(100) }
        assertTrue("Back must return to the same lineup", screenText().contains("Gate Channel"))
    }
    private suspend fun awaitChangedFocus(previous: String): String = withTimeout(5_000) {
        var current = focusedText()
        while (current == previous || !current.contains("Gate Channel")) { delay(100); current = focusedText() }
        current
    }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun screenText() = nodes().flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }.joinToString(" | ")
    private fun focusedText(): String {
        val n = nodes().firstOrNull { it.isFocused } ?: return "NO_FOCUS"
        fun descendants(node: android.view.accessibility.AccessibilityNodeInfo): String =
            (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).mapNotNull { node.getChild(it) }.map { descendants(it) }).joinToString(" ")
        return descendants(n)
    }
    private fun screenshot(name: String) {
        val directory = java.io.File(context.getExternalFilesDir(null), "release-gates").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        java.io.File(directory, "$name.txt").writeText(screenText() + "\nFOCUS: " + focusedText())
    }

}
