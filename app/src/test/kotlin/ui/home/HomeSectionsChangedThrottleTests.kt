package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.HomeSectionsChangedInfo
import kotlin.time.Duration.Companion.milliseconds

private val allStale = HomeSectionsChangedInfo(allStale = true, staleSectionKeys = emptyList())
private fun stale(vararg keys: String) = HomeSectionsChangedInfo(allStale = false, staleSectionKeys = keys.toList())

class HomeSectionsChangedThrottleTests : FunSpec({
	test("targeted changes pass through at once") {
		val changes = flow {
			emit(stale("resume"))
			emit(stale("nextup"))
		}

		runBlocking { changes.throttleAllStale(200.milliseconds).toList() } shouldContainExactly listOf(stale("resume"), stale("nextup"))
	}

	test("all stale changes within the window collapse into one trailing emission") {
		val changes = flow {
			repeat(5) {
				emit(allStale)
				delay(20)
			}
		}

		runBlocking { changes.throttleAllStale(200.milliseconds).toList() } shouldContainExactly listOf(allStale, allStale)
	}

	test("all stale changes outside the window go out on their own") {
		val changes = flow {
			emit(allStale)
			delay(300)
			emit(allStale)
		}

		runBlocking { changes.throttleAllStale(200.milliseconds).toList() } shouldContainExactly listOf(allStale, allStale)
	}

	test("targeted changes are not held back by the window") {
		val changes = flow {
			emit(allStale)
			delay(20)
			emit(stale("resume"))
		}

		runBlocking { changes.throttleAllStale(200.milliseconds).toList() } shouldContainExactly listOf(allStale, stale("resume"))
	}
})
