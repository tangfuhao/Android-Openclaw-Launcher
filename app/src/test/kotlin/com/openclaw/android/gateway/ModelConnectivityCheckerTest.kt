package com.openclaw.android.gateway

import com.openclaw.android.data.ModelApiType
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelConnectivityCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: ModelConnectivityChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = ModelConnectivityChecker()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `openai-completions probe succeeds on 200`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"OK"}}]}"""),
        )

        val config = ModelConfig(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "openai",
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "sk-test",
                    apiType = ModelApiType.OPENAI_COMPLETIONS,
                    modelId = "gpt-4",
                ),
            ),
        )

        val result = kotlinx.coroutines.runBlocking { checker.test(config) }
        assertTrue(result is ConnectivityResult.Success)
    }

    @Test
    fun `openai-completions probe fails on 401`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"invalid key"}"""),
        )

        val config = ModelConfig(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "openai",
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "bad",
                    apiType = ModelApiType.OPENAI_COMPLETIONS,
                    modelId = "gpt-4",
                ),
            ),
        )

        val result = kotlinx.coroutines.runBlocking { checker.test(config) }
        assertTrue(result is ConnectivityResult.Failure)
        assertTrue((result as ConnectivityResult.Failure).message.contains("401"))
    }

    @Test
    fun `probe fails when base URL missing for unknown provider`() {
        val config = ModelConfig(
            primaryModel = "unknown-vendor/model",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "unknown-vendor",
                    apiKey = "key",
                    modelId = "model",
                ),
            ),
        )

        val result = kotlinx.coroutines.runBlocking { checker.test(config) }
        assertTrue(result is ConnectivityResult.Failure)
        assertTrue((result as ConnectivityResult.Failure).message.contains("Base URL"))
    }
}
