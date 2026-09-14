package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import org.jellyfin.sdk.model.api.HomeSectionProviderDto
import org.jellyfin.sdk.model.api.MediaType
import java.util.UUID

private fun config(key: String, vararg itemIds: UUID, active: Boolean = true) =
	HomeSectionConfigDto(key = key, itemIds = itemIds.toList(), maxItems = null, active = active)

private fun provider(key: String, itemKind: BaseItemKind? = null, allowsMultipleItems: Boolean = false) =
	HomeSectionProviderDto(key = key, name = key, itemKind = itemKind, allowsMultipleItems = allowsMultipleItems)

private fun item(type: BaseItemKind) = BaseItemDto(id = UUID.randomUUID(), type = type, mediaType = MediaType.UNKNOWN)

class HomeSectionPinningTests : FunSpec({
	val providers = listOf(
		provider("resume"),
		provider("pinnedcollection", BaseItemKind.BOX_SET, allowsMultipleItems = true),
		provider("plugin.person", BaseItemKind.PERSON),
	)
	val marvel = UUID.randomUUID()
	val potter = UUID.randomUUID()

	test("pinProviderFor finds the list section for the kind of item, and nothing for the rest") {
		providers.pinProviderFor(item(BaseItemKind.BOX_SET))?.key shouldBe "pinnedcollection"
		// A section that takes exactly one item is not a list to pin to.
		providers.pinProviderFor(item(BaseItemKind.PERSON)) shouldBe null
		providers.pinProviderFor(item(BaseItemKind.MOVIE)) shouldBe null
	}

	test("isPinned counts an item only in an active section") {
		listOf(config("pinnedcollection", marvel)).isPinned("pinnedcollection", marvel) shouldBe true
		listOf(config("pinnedcollection", marvel, active = false)).isPinned("pinnedcollection", marvel) shouldBe false
		listOf(config("pinnedcollection", potter)).isPinned("pinnedcollection", marvel) shouldBe false
	}

	test("withItemPinned appends as the last row and unhides the section") {
		val layout = listOf(config("resume"), config("pinnedcollection", marvel, active = false), config("nextup"))

		layout.withItemPinned("pinnedcollection", potter) shouldContainExactly listOf(
			config("resume"),
			config("pinnedcollection", marvel, potter),
			config("nextup"),
		)
	}

	test("withItemPinned adds the section at the end when the layout has none") {
		listOf(config("resume")).withItemPinned("pinnedcollection", potter) shouldContainExactly listOf(
			config("resume"),
			config("pinnedcollection", potter),
		)
	}

	test("withItemUnpinned removes the row, and the section once it is empty") {
		val layout = listOf(config("resume"), config("pinnedcollection", marvel, potter))

		layout.withItemUnpinned("pinnedcollection", marvel) shouldContainExactly listOf(config("resume"), config("pinnedcollection", potter))
		layout.withItemUnpinned("pinnedcollection", marvel)
			.withItemUnpinned("pinnedcollection", potter) shouldContainExactly listOf(config("resume"))
	}
})
