package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.HomeSectionsRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import org.jellyfin.sdk.model.api.HomeSectionProviderDto
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.UUID

/**
 * The user's home screen layout as the settings screens see it. Every change posts the whole
 * layout, like the other settings screens write a preference on each click.
 */
class SettingsHomeViewModel(
	private val repository: HomeSectionsRepository,
	private val api: ApiClient,
) : ViewModel() {
	data class State(
		val loading: Boolean = true,
		/** The last load failed. */
		val error: Throwable? = null,
		/** The last save or reset failed; the layout shown is the server's. Cleared by the next save. */
		val saveError: Throwable? = null,
		val sections: List<HomeSectionConfigDto> = emptyList(),
		val providers: List<HomeSectionProviderDto> = emptyList(),
		/** What a section can be bound to, by the kind its provider asks for. */
		val items: Map<BaseItemKind, List<BaseItemDto>> = emptyMap(),
	) {
		val failed get() = error != null || saveError != null
		fun provider(key: String) = providers.firstOrNull { it.key == key }
		fun providerName(key: String) = provider(key)?.name ?: key
		fun itemName(itemId: UUID?) = itemId?.let { id -> items.values.asSequence().flatten().firstOrNull { it.id == id }?.name }
		fun contains(key: String, itemId: UUID?) = sections.any { it.key == key && it.itemId == itemId }
	}

	private val _state = MutableStateFlow(State())
	val state = _state.asStateFlow()
	private var saveJob: Job? = null

	/**
	 * Loads the layout, the providers and the items they can bind to. Called when the home
	 * settings open, so edits made elsewhere show up; a save still in flight is awaited first.
	 */
	fun load() {
		viewModelScope.launch {
			saveJob?.join()
			_state.update { it.copy(loading = it.providers.isEmpty(), error = null) }

			runCatching {
				withContext(Dispatchers.IO) {
					coroutineScope {
						val providers = async { repository.getProviders() }
						val sections = async { repository.getConfig() }
						val kinds = providers.await().mapNotNull { it.itemKind }.distinct()
						val items = kinds.map { kind -> async { kind to loadItems(kind) } }.awaitAll().toMap()

						Triple(sections.await(), providers.await(), items)
					}
				}
			}.fold(
				onSuccess = { (sections, providers, items) ->
					_state.update { it.copy(loading = false, sections = sections, providers = providers, items = items) }
				},
				onFailure = { error ->
					Timber.e(error, "Unable to load home section settings")
					_state.update { it.copy(loading = false, error = error) }
				},
			)
		}
	}

	private suspend fun loadItems(kind: BaseItemKind): List<BaseItemDto> = when (kind) {
		// Only the genres the server builds a section from
		BaseItemKind.GENRE -> api.genreApi.getGenres(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			sortBy = setOf(ItemSortBy.SORT_NAME),
			enableTotalRecordCount = false,
		).content.items

		else -> api.libraryApi.getItems(
			includeItemTypes = setOf(kind),
			recursive = true,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			enableTotalRecordCount = false,
		).content.items
	}

	fun add(key: String, itemId: UUID? = null) = save(state.value.sections.withSection(key, itemId))
	fun remove(key: String, itemId: UUID?) = save(state.value.sections.withoutSection(key, itemId))
	fun remove(index: Int) = save(state.value.sections.withoutSection(index))
	fun move(index: Int, offset: Int) = save(state.value.sections.moved(index, offset))
	fun setActive(index: Int, active: Boolean) = save(state.value.sections.withActive(index, active))
	fun setMaxItems(index: Int, maxItems: Int?) = save(state.value.sections.withMaxItems(index, maxItems))

	// The home settings screen reloads when it comes back, which picks the defaults up
	fun reset() {
		_state.update { it.copy(saveError = null) }
		saveJob = viewModelScope.launch {
			runCatching { withContext(Dispatchers.IO) { repository.resetConfig() } }
				.onFailure { error ->
					Timber.e(error, "Unable to reset home sections")
					_state.update { it.copy(saveError = error) }
				}
		}
	}

	private fun save(sections: List<HomeSectionConfigDto>) {
		val previous = state.value.sections
		if (sections == previous) return
		_state.update { it.copy(sections = sections, saveError = null) }

		saveJob = viewModelScope.launch {
			runCatching { withContext(Dispatchers.IO) { repository.updateConfig(sections) } }
				.onFailure { error ->
					Timber.e(error, "Unable to save home sections")
					_state.update { it.copy(sections = previous, saveError = error) }
				}
		}
	}
}

// The layout edits, as functions of the list so they can be tested on their own

fun List<HomeSectionConfigDto>.withSection(key: String, itemId: UUID? = null): List<HomeSectionConfigDto> =
	if (any { it.key == key && it.itemId == itemId }) this
	else this + HomeSectionConfigDto(key = key, itemId = itemId, maxItems = null, active = true)

fun List<HomeSectionConfigDto>.withoutSection(key: String, itemId: UUID?): List<HomeSectionConfigDto> =
	filterNot { it.key == key && it.itemId == itemId }

fun List<HomeSectionConfigDto>.withoutSection(index: Int): List<HomeSectionConfigDto> =
	filterIndexed { i, _ -> i != index }

fun List<HomeSectionConfigDto>.moved(index: Int, offset: Int): List<HomeSectionConfigDto> {
	val target = index + offset
	if (index !in indices || target !in indices) return this
	return toMutableList().apply { add(target, removeAt(index)) }
}

fun List<HomeSectionConfigDto>.withActive(index: Int, active: Boolean): List<HomeSectionConfigDto> =
	mapIndexed { i, section -> if (i == index) section.copy(active = active) else section }

fun List<HomeSectionConfigDto>.withMaxItems(index: Int, maxItems: Int?): List<HomeSectionConfigDto> =
	mapIndexed { i, section -> if (i == index) section.copy(maxItems = maxItems) else section }
