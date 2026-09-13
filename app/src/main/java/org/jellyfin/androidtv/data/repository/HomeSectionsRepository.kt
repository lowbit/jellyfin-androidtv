package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.homeSectionsApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.jellyfin.sdk.model.api.HomeSectionProviderDto
import org.jellyfin.sdk.model.api.HomeSectionsChangedInfo
import org.jellyfin.sdk.model.api.HomeSectionsChangedMessage

interface HomeSectionsRepository {
	/**
	 * The home sections with their items. Concurrent callers share one request and the result is
	 * kept until [invalidate] is called.
	 */
	suspend fun getSections(): List<HomeSectionDto>

	/**
	 * Drop the cached sections so the next [getSections] call fetches again.
	 */
	fun invalidate()

	/**
	 * Sections the server reports as stale. Collecting subscribes on the WebSocket, the last
	 * collector leaving unsubscribes.
	 */
	val changes: Flow<HomeSectionsChangedInfo>

	suspend fun getProviders(): List<HomeSectionProviderDto>
	suspend fun getConfig(): List<HomeSectionConfigDto>
	suspend fun updateConfig(sections: List<HomeSectionConfigDto>)
	suspend fun resetConfig()
}

class HomeSectionsRepositoryImpl(
	private val api: ApiClient,
	private val itemLimit: Int = ITEM_LIMIT,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : HomeSectionsRepository {
	private val mutex = Mutex()
	@Volatile
	private var sections: Deferred<List<HomeSectionDto>>? = null

	override suspend fun getSections(): List<HomeSectionDto> {
		val deferred = mutex.withLock {
			sections ?: scope.async {
				api.homeSectionsApi.getHomeSections(itemLimit = itemLimit).content
			}.also { sections = it }
		}

		try {
			return deferred.await()
		} catch (err: Throwable) {
			// A failed request is not kept, the next call retries
			if (deferred.isCancelled) mutex.withLock { if (sections === deferred) sections = null }
			throw err
		}
	}

	override fun invalidate() {
		sections = null
	}

	override val changes: Flow<HomeSectionsChangedInfo>
		get() = api.webSocket.subscribe<HomeSectionsChangedMessage>().mapNotNull { it.data }

	override suspend fun getProviders(): List<HomeSectionProviderDto> =
		api.homeSectionsApi.getHomeSectionProviders().content

	override suspend fun getConfig(): List<HomeSectionConfigDto> =
		api.homeSectionsApi.getHomeSectionConfig().content

	override suspend fun updateConfig(sections: List<HomeSectionConfigDto>) {
		api.homeSectionsApi.updateHomeSectionConfig(data = sections)
	}

	override suspend fun resetConfig() {
		api.homeSectionsApi.resetHomeSectionConfig()
	}

	companion object {
		// Same amount the rows asked for before sections came from the server
		private const val ITEM_LIMIT = 50
	}
}
