package org.jellyfin.androidtv.data.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.operations.HomeSectionsApi
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.jellyfin.sdk.model.api.HomeSectionViewType
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HomeSectionsRepositoryTests : FunSpec({
	val sections = listOf(
		HomeSectionDto(id = "resume", key = "resume", displayText = "Continue Watching", viewType = HomeSectionViewType.LANDSCAPE, parentId = null, items = emptyList()),
	)
	val body = ApiSerializer.json.encodeToString(ListSerializer(HomeSectionDto.serializer()), sections).encodeToByteArray()

	// An ApiClient whose /HomeSections answers are controlled by the test
	fun apiClient(respond: suspend () -> RawResponse): Pair<ApiClient, AtomicInteger> {
		val requests = AtomicInteger()
		val api = mockk<ApiClient> {
			every { getOrCreateApi(HomeSectionsApi::class, any()) } answers { HomeSectionsApi(this@mockk) }
			coEvery { request(any(), "/HomeSections", any(), any(), any()) } coAnswers {
				requests.incrementAndGet()
				respond()
			}
		}
		return api to requests
	}

	test("concurrent calls share one request") {
		val gate = CompletableDeferred<Unit>()
		val (api, requests) = apiClient { gate.await(); RawResponse(body, 200, emptyMap()) }
		val repository = HomeSectionsRepositoryImpl(api, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

		runBlocking {
			val first = async(Dispatchers.Default) { repository.getSections() }
			val second = async(Dispatchers.Default) { repository.getSections() }
			gate.complete(Unit)

			first.await() shouldHaveSize 1
			second.await() shouldHaveSize 1
		}

		requests.get() shouldBe 1
	}

	test("the result is kept until invalidated") {
		val (api, requests) = apiClient { RawResponse(body, 200, emptyMap()) }
		val repository = HomeSectionsRepositoryImpl(api, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

		runBlocking {
			repository.getSections()
			repository.getSections()
			requests.get() shouldBe 1

			repository.invalidate()
			repository.getSections()
			requests.get() shouldBe 2
		}
	}

	test("a failed request is not kept") {
		var fail = true
		val (api, requests) = apiClient {
			if (fail) throw IOException("offline")
			RawResponse(body, 200, emptyMap())
		}
		val repository = HomeSectionsRepositoryImpl(api, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

		runBlocking {
			shouldThrow<IOException> { repository.getSections() }

			fail = false
			repository.getSections() shouldHaveSize 1
		}

		requests.get() shouldBe 2
	}

	test("fetching some keys sends them and leaves the kept result alone") {
		val queries = mutableListOf<Map<String, Any?>>()
		val api = mockk<ApiClient> {
			every { getOrCreateApi(HomeSectionsApi::class, any()) } answers { HomeSectionsApi(this@mockk) }
			coEvery { request(any(), "/HomeSections", any(), any(), any()) } coAnswers {
				queries += arg<Map<String, Any?>>(3)
				RawResponse(body, 200, emptyMap())
			}
		}
		val repository = HomeSectionsRepositoryImpl(api, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

		runBlocking {
			repository.getSections()
			repository.getSections(listOf("resume", "nextup"))
			repository.getSections()
		}

		queries shouldHaveSize 2
		(queries[1]["keys"] as Collection<*>).toList() shouldBe listOf("resume", "nextup")
	}
})
