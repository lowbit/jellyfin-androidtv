package org.jellyfin.androidtv.ui.settings.screen.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import java.util.UUID

private fun config(key: String, vararg itemIds: UUID, maxItems: Int? = null, active: Boolean = true) =
	HomeSectionConfigDto(key = key, itemIds = itemIds.toList(), maxItems = maxItems, active = active)

class SettingsHomeLayoutEditsTests : FunSpec({
	val collection = UUID.randomUUID()
	val layout = listOf(config("resume"), config("pinnedcollection", collection), config("nextup"))

	test("withSection appends, once") {
		val genre = UUID.randomUUID()

		layout.withSection("genre", genre) shouldContainExactly layout + config("genre", genre)
		layout.withSection("resume") shouldBe layout
		layout.withSection("pinnedcollection", collection) shouldBe layout
	}

	test("withoutSection removes by key and item, by key alone, or by index") {
		layout.withoutSection("pinnedcollection", collection) shouldContainExactly listOf(config("resume"), config("nextup"))
		layout.withoutSection("pinnedcollection") shouldContainExactly listOf(config("resume"), config("nextup"))
		layout.withoutSection("pinnedcollection", UUID.randomUUID()) shouldBe layout
		layout.withoutSection(0) shouldContainExactly listOf(config("pinnedcollection", collection), config("nextup"))
	}

	test("withItemToggled narrows a section, or adds one bound to that item") {
		val action = UUID.randomUUID()
		val comedy = UUID.randomUUID()
		val genres = layout + config("genre")

		val narrowed = genres.withItemToggled("genre", action)
		narrowed shouldContainExactly layout + config("genre", action)

		narrowed.withItemToggled("genre", comedy) shouldContainExactly layout + config("genre", action, comedy)

		// Unticking the last one leaves the section with nothing, which draws nothing.
		narrowed.withItemToggled("genre", action) shouldContainExactly layout + config("genre")

		// Nothing to narrow yet: the row is added, bound to what was ticked.
		layout.withItemToggled("genre", action) shouldContainExactly layout + config("genre", action)
	}

	test("withItems replaces one section's list") {
		val action = UUID.randomUUID()
		val comedy = UUID.randomUUID()
		val genres = layout + config("genre", action)

		genres.withItems("genre", listOf(action, comedy)) shouldContainExactly layout + config("genre", action, comedy)
		genres.withItems("genre", emptyList()) shouldContainExactly layout + config("genre")
		genres.withItems("missing", listOf(action)) shouldBe genres
	}

	test("withSection can start a section off with several items") {
		val action = UUID.randomUUID()
		val comedy = UUID.randomUUID()

		layout.withSection("genre", listOf(action, comedy)) shouldContainExactly layout + config("genre", action, comedy)
		layout.withSection("genre", listOf(action, comedy)).withSection("genre", listOf(action, comedy)) shouldContainExactly layout + config("genre", action, comedy)
	}

	test("moved swaps with its neighbour and stays put at the edges") {
		layout.moved(2, -1) shouldContainExactly listOf(config("resume"), config("nextup"), config("pinnedcollection", collection))
		layout.moved(0, 1) shouldContainExactly listOf(config("pinnedcollection", collection), config("resume"), config("nextup"))
		layout.moved(0, -1) shouldBe layout
		layout.moved(2, 1) shouldBe layout
	}

	test("withActive hides one section") {
		layout.withActive(1, false) shouldContainExactly listOf(
			config("resume"),
			config("pinnedcollection", collection, active = false),
			config("nextup"),
		)
	}

	test("withMaxItems caps one section and clears it again") {
		layout.withMaxItems(2, 12) shouldContainExactly listOf(config("resume"), config("pinnedcollection", collection), config("nextup", maxItems = 12))
		layout.withMaxItems(2, 12).withMaxItems(2, null) shouldBe layout
	}
})
