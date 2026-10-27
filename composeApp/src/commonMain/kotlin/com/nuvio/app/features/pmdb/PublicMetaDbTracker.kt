package com.nuvio.app.features.pmdb

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PublicMetaDbAccountStatus(
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * PublicMetaDB tracking: an API key (created at publicmetadb.com) authenticates every request.
 * Connecting registers watch-history writes, playback scrobbling, resume sync and watched sync with
 * the shared tracking registry, the same way the other tracking providers plug in.
 */
object PublicMetaDbTracker : TrackingAuthProvider {
    private const val API_KEY_STORAGE_KEY = "pmdb_api_key"

    internal val api = PublicMetaDbApi()
    internal val ids = PublicMetaDbIds(api)

    private var loadedProfileId: Int? = null
    private var generation = 0L
    private val apiKeyState = MutableStateFlow<String?>(null)
    private val authenticated = MutableStateFlow(false)
    private val accountStatus = MutableStateFlow(PublicMetaDbAccountStatus())

    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long get() = generation
    val status: StateFlow<PublicMetaDbAccountStatus> = accountStatus.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.PMDB,
        "PublicMetaDB",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    private fun apiKeyForProfile(profileId: Int): String? =
        apiKeyState.value.takeIf { loadedProfileId == profileId && ProfileRepository.activeProfileId == profileId }

    internal val progressProvider = PublicMetaDbProgressProvider(
        api = api,
        ids = ids,
        apiKey = { apiKeyForProfile(ProfileRepository.activeProfileId) },
        ensureAccountLoaded = ::ensureLoaded,
    )
    internal val writes = PublicMetaDbTrackingWrites(api, ids, progressProvider, ::apiKeyForProfile)
    internal val watchedProvider = PublicMetaDbWatchedProvider(api, ids, ::apiKeyForProfile)

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerHistoryWriter(writes)
        TrackingProviderRegistry.registerScrobbler(writes)
        TrackingProviderRegistry.registerProgressProvider(progressProvider)
        TrackingProviderRegistry.registerWatchedProvider(watchedProvider)
        ensureLoaded()
    }

    override fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        val stored = PublicMetaDbPlatform.persistence.read(ProfileScopedKey.of(API_KEY_STORAGE_KEY, profileId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        generation++
        apiKeyState.value = stored
        authenticated.value = stored != null
        accountStatus.value = PublicMetaDbAccountStatus()
    }

    /** Validates [rawKey] against PublicMetaDB and stores it for the active profile. */
    suspend fun connect(rawKey: String): Boolean {
        ensureLoaded()
        val key = rawKey.trim()
        if (key.isEmpty()) {
            accountStatus.value = PublicMetaDbAccountStatus(errorMessage = "Enter your PublicMetaDB API key")
            return false
        }
        accountStatus.value = PublicMetaDbAccountStatus(isBusy = true)
        return try {
            if (!api.validateKey(key)) {
                accountStatus.value = PublicMetaDbAccountStatus(errorMessage = "PublicMetaDB rejected that API key")
                false
            } else {
                val profileId = ProfileRepository.activeProfileId
                PublicMetaDbPlatform.persistence.write(ProfileScopedKey.of(API_KEY_STORAGE_KEY, profileId), key)
                generation++
                apiKeyState.value = key
                authenticated.value = true
                accountStatus.value = PublicMetaDbAccountStatus()
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PublicMetaDbApiException) {
            accountStatus.value = PublicMetaDbAccountStatus(
                errorMessage = if (error.status == 429) "PublicMetaDB rate limit reached; try again later" else "Could not reach PublicMetaDB (HTTP ${error.status})",
            )
            false
        } catch (error: Exception) {
            accountStatus.value = PublicMetaDbAccountStatus(errorMessage = "Could not reach PublicMetaDB")
            false
        }
    }

    fun disconnect() {
        val profileId = ProfileRepository.activeProfileId
        PublicMetaDbPlatform.persistence.write(ProfileScopedKey.of(API_KEY_STORAGE_KEY, profileId), null)
        generation++
        apiKeyState.value = null
        authenticated.value = false
        accountStatus.value = PublicMetaDbAccountStatus()
        progressProvider.clearLocalState()
    }

    /** Pulls resume points now; returns true when the sync finished without an error. */
    suspend fun syncNow(): Boolean {
        ensureLoaded()
        accountStatus.update { it.copy(isBusy = true, errorMessage = null) }
        progressProvider.refresh(force = true, sourceChanged = false)
        val error = progressProvider.lastError
        accountStatus.value = PublicMetaDbAccountStatus(errorMessage = error)
        return error == null
    }

    val lastSyncedAtEpochMs: Long get() = progressProvider.lastRefreshedAtEpochMs
    val resumePointCount: Int get() = progressProvider.resumePointCount
    val progressChanges: kotlinx.coroutines.flow.Flow<Unit> get() = progressProvider.changes

    override fun onProfileChanged() {
        loadedProfileId = null
        progressProvider.clearLocalState()
        ensureLoaded()
    }

    override fun clearLocalState() {
        (1..MAX_PROFILES).forEach { profileId ->
            PublicMetaDbPlatform.persistence.write(ProfileScopedKey.of(API_KEY_STORAGE_KEY, profileId), null)
        }
        generation++
        apiKeyState.value = null
        authenticated.value = false
        progressProvider.clearLocalState()
    }

    override fun removeStoredProfile(profileId: Int) {
        PublicMetaDbPlatform.persistence.write(ProfileScopedKey.of(API_KEY_STORAGE_KEY, profileId), null)
        if (profileId == loadedProfileId) {
            apiKeyState.value = null
            authenticated.value = false
        }
    }
}
