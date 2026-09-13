package org.jellyfin.androidtv.ui.settings.screen.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import java.util.UUID

private fun config(key: String, itemId: UUID? = null, maxItems: Int? = null, active: Boolean = true) =
	HomeSectionConfigDto(key = key, itemId = itemId, maxItems = maxItems, active = active)

class SettingsHomeLayoutEditsTests : FunSpec({
	val collection = UUID.randomUUID()
	val layout = listOf(config("resume"), config("pinnedcollection", collection), config("nextup"))

	test("withSection appends, once") {
		val genre = UUID.randomUUID()

		layout.withSection("genre", genre) shouldContainExactly layout + config("genre", genre)
		layout.withSection("resume") shouldBe layout
		layout.withSection("pinnedcollection", collection) shouldBe layout
	}

	test("withoutSection removes by key and item, or by index") {
		layout.withoutSection("pinnedcollection", collection) shouldContainExactly listOf(config("resume"), config("nextup"))
		layout.withoutSection("pinnedcollection", null) shouldBe layout
		layout.withoutSection(0) shouldContainExactly listOf(config("pinnedcollection", collection), config("nextup"))
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
