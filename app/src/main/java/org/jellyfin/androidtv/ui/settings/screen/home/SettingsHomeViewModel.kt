package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import org.jellyfin.androidtv.R
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
		fun itemName(itemId: UUID) = items.values.asSequence().flatten().firstOrNull { it.id == itemId }?.name

		/** Whether a section takes any number of items, each of them getting a row. */
		fun takesSeveralItems(key: String) = provider(key)?.let { it.itemKind != null && it.allowsMultipleItems } == true

		/** The ids of everything a section of this kind can be bound to. */
		fun offeredItemIds(key: String) = provider(key)?.itemKind?.let { kind -> items[kind] }.orEmpty().map { it.id }

		/** Whether a section that takes several items has every one of them. */
		fun hasEveryItem(section: HomeSectionConfigDto): Boolean {
			val offered = offeredItemIds(section.key)
			return offered.isNotEmpty() && section.itemIds.containsAll(offered)
		}

		/**
		 * What a section is bound to, for the caption under its name: all, none, or the names,
		 * cut to the first few and a count since a caption is one line. Null when it says nothing.
		 */
		@Composable
		fun itemNames(section: HomeSectionConfigDto): String? {
			if (takesSeveralItems(section.key)) {
				if (section.itemIds.isEmpty()) return stringResource(R.string.lbl_none)
				if (hasEveryItem(section)) return stringResource(R.string.home_section_all_items)
			}

			val names = section.itemIds.mapNotNull(::itemName)
			if (names.isEmpty()) return null

			val shown = names.take(MAX_CAPTION_NAMES).joinToString(", ")
			val rest = names.size - MAX_CAPTION_NAMES
			return if (rest > 0) "$shown, +$rest" else shown
		}

		fun section(key: String) = sections.firstOrNull { it.key == key }
		fun containsSection(key: String) = section(key) != null
		fun containsItem(key: String, itemId: UUID) = sections.any {
			it.key == key && (if (takesSeveralItems(key)) itemId in it.itemIds else it.itemIds == listOf(itemId))
		}
	}

	private companion object {
		const val MAX_CAPTION_NAMES = 3
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

	/**
	 * Adds or removes a section that takes no item, or one that takes several, which starts
	 * with all of them so that adding it needs no more.
	 */
	fun toggleSection(key: String) = save(
		if (state.value.containsSection(key)) state.value.sections.withoutSection(key)
		else state.value.sections.withSection(key, state.value.offeredItemIds(key))
	)

	/**
	 * Adds or removes one item. For a section that takes several the item joins or leaves that
	 * section's list; otherwise the section bound to that item is added or removed.
	 */
	fun toggleItem(key: String, itemId: UUID) = save(
		if (state.value.takesSeveralItems(key)) state.value.sections.withItemToggled(key, itemId)
		else if (state.value.containsItem(key, itemId)) state.value.sections.withoutSection(key, itemId)
		else state.value.sections.withSection(key, itemId)
	)

	/** Every item on offer, or none, for a section that takes several. */
	fun toggleAllItems(key: String) {
		val section = state.value.section(key) ?: return
		val itemIds = if (state.value.hasEveryItem(section)) emptyList() else state.value.offeredItemIds(key)
		save(state.value.sections.withItems(key, itemIds))
	}

	fun remove(index: Int) = save(state.value.sections.withoutSection(index))
	fun move(index: Int, offset: Int) = save(state.value.sections.moved(index, offset))
	fun moveItem(index: Int, itemId: UUID, offset: Int) = save(state.value.sections.withItemMoved(index, itemId, offset))
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
	withSection(key, listOfNotNull(itemId))

fun List<HomeSectionConfigDto>.withSection(key: String, itemIds: List<UUID>): List<HomeSectionConfigDto> {
	if (any { it.key == key && it.itemIds == itemIds }) return this

	return this + HomeSectionConfigDto(key = key, itemIds = itemIds, maxItems = null, active = true)
}

fun List<HomeSectionConfigDto>.withoutSection(key: String, itemId: UUID? = null): List<HomeSectionConfigDto> =
	filterNot { it.key == key && (itemId == null || it.itemIds == listOf(itemId)) }

/**
 * Adds or removes one item of a section that takes several, adding the section itself when the
 * layout has none.
 */
fun List<HomeSectionConfigDto>.withItemToggled(key: String, itemId: UUID): List<HomeSectionConfigDto> {
	if (none { it.key == key }) return this + HomeSectionConfigDto(key = key, itemIds = listOf(itemId), maxItems = null, active = true)

	return map { section ->
		if (section.key != key) section
		else section.copy(itemIds = if (itemId in section.itemIds) section.itemIds - itemId else section.itemIds + itemId)
	}
}

fun List<HomeSectionConfigDto>.withItems(key: String, itemIds: List<UUID>): List<HomeSectionConfigDto> =
	map { section -> if (section.key == key) section.copy(itemIds = itemIds) else section }

/**
 * Moves one item of a section that takes several up or down its rows, clamped to the ends, so
 * a large offset means top or bottom.
 */
fun List<HomeSectionConfigDto>.withItemMoved(index: Int, itemId: UUID, offset: Int): List<HomeSectionConfigDto> {
	val section = getOrNull(index) ?: return this
	val from = section.itemIds.indexOf(itemId)
	if (from < 0) return this
	val to = (from + offset).coerceIn(section.itemIds.indices)
	if (to == from) return this

	val itemIds = section.itemIds.toMutableList().apply { add(to, removeAt(from)) }
	return mapIndexed { i, s -> if (i == index) s.copy(itemIds = itemIds) else s }
}

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
