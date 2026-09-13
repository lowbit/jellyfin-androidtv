package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jellyfin.androidtv.constant.HomeSectionKey
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.jellyfin.sdk.model.api.HomeSectionViewType

private fun section(id: String, key: String = id) = HomeSectionDto(
	id = id,
	key = key,
	displayText = id,
	viewType = HomeSectionViewType.PORTRAIT,
	parentId = null,
	items = emptyList(),
)

class HomeSectionRowsTests : FunSpec({
	test("homeSectionRowIds adds a library row first when the layout has none") {
		homeSectionRowIds(listOf(section("resume"), section("nextup"))) shouldContainExactly
			listOf(LIBRARY_FALLBACK_ROW_ID, "resume", "nextup")
	}

	test("homeSectionRowIds keeps the layout when it has a library row") {
		val sections = listOf(section("resume"), section("smalllibrarytiles", HomeSectionKey.SMALL_LIBRARY_TILES))
		homeSectionRowIds(sections) shouldContainExactly listOf("resume", "smalllibrarytiles")

		val buttons = listOf(section("librarybuttons", HomeSectionKey.LIBRARY_BUTTONS))
		homeSectionRowIds(buttons) shouldContainExactly listOf("librarybuttons")
	}

	test("same ids keep every row") {
		val rows = HomeSectionRows<Any>()
		rows.update(listOf("a", "b"), onScreen = { true }, create = { listOf(Any()) })
		val a = rows["a"]
		val b = rows["b"]

		val kept = rows.update(listOf("a", "b"), onScreen = { true }, create = { error("nothing should be created") })

		kept shouldBe setOf("a", "b")
		rows["a"] shouldBeSameInstanceAs a
		rows["b"] shouldBeSameInstanceAs b
	}

	test("a new id creates only that row, in layout order") {
		val rows = HomeSectionRows<Any>()
		rows.update(listOf("a", "b"), onScreen = { true }, create = { listOf(Any()) })
		val a = rows["a"]
		val b = rows["b"]

		val created = mutableListOf<String>()
		val kept = rows.update(listOf("a", "new", "b"), onScreen = { true }, create = { id -> created += id; listOf(Any()) })

		kept shouldBe setOf("a", "b")
		created shouldContainExactly listOf("new")
		rows.all shouldContainExactly a + rows["new"] + b
	}

	test("a removed id drops its rows") {
		val rows = HomeSectionRows<Any>()
		rows.update(listOf("a", "b"), onScreen = { true }, create = { listOf(Any()) })
		val a = rows["a"]

		rows.update(listOf("a"), onScreen = { true }, create = { error("nothing should be created") })

		rows.all shouldContainExactly a
		rows["b"] shouldBe emptyList()
	}

	test("a row that left the screen is created again") {
		val rows = HomeSectionRows<Any>()
		rows.update(listOf("a", "b"), onScreen = { true }, create = { listOf(Any()) })
		val a = rows["a"]
		val b = rows["b"]

		val kept = rows.update(listOf("a", "b"), onScreen = { it !in b }, create = { listOf(Any()) })

		kept shouldBe setOf("a")
		rows["a"] shouldBeSameInstanceAs a
		rows["b"] shouldNotBeSameInstanceAs b
	}
})
