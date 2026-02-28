package com.openclaw.android.gateway

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalApiTest {

    private lateinit var gateway: GatewayClient
    private lateinit var approvalApi: ApprovalApi
    private lateinit var approvalFlow: MutableSharedFlow<ApprovalRequestPayload>

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        approvalFlow = MutableSharedFlow()
        every { gateway.approvalRequests } returns approvalFlow
        approvalApi = ApprovalApi(gateway)
    }

    // --- observeApprovalRequests ---

    @Test
    fun `observeApprovalRequests maps payload correctly`() = runTest {
        val results = mutableListOf<ApprovalApi.ApprovalUiRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            approvalApi.observeApprovalRequests().collect { results.add(it) }
        }

        approvalFlow.emit(ApprovalRequestPayload(
            requestId = "r1", tool = "bash", description = "Run ls",
        ))

        assertEquals(1, results.size)
        assertEquals("r1", results[0].requestId)
        assertEquals("bash", results[0].tool)
        assertEquals("Run ls", results[0].description)

        job.cancel()
    }

    @Test
    fun `observeApprovalRequests defaults null tool to unknown`() = runTest {
        val results = mutableListOf<ApprovalApi.ApprovalUiRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            approvalApi.observeApprovalRequests().collect { results.add(it) }
        }

        approvalFlow.emit(ApprovalRequestPayload(requestId = "r1", tool = null))

        assertEquals("unknown", results[0].tool)
        job.cancel()
    }

    @Test
    fun `observeApprovalRequests defaults null description`() = runTest {
        val results = mutableListOf<ApprovalApi.ApprovalUiRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            approvalApi.observeApprovalRequests().collect { results.add(it) }
        }

        approvalFlow.emit(ApprovalRequestPayload(requestId = "r1", description = null))

        assertEquals("The agent wants to execute a tool", results[0].description)
        job.cancel()
    }

    // --- resolve ---

    @Test
    fun `resolve sends correct params for approve`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = true)

        val params = paramsSlot.captured
        assertEquals("r1", params["requestId"]?.toString()?.trim('"'))
        assertEquals("true", params["approved"]?.toString())
    }

    @Test
    fun `resolve sends correct params for deny`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = false)
        assertEquals("false", paramsSlot.captured["approved"]?.toString())
    }

    @Test
    fun `resolve includes reason when provided`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = true, reason = "looks safe")
        assertTrue(paramsSlot.captured.containsKey("reason"))
    }

    @Test
    fun `resolve omits reason when null`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = true, reason = null)
        assertTrue(!paramsSlot.captured.containsKey("reason"))
    }

    @Test(expected = ApprovalApi.ApprovalException::class)
    fun `resolve throws ApprovalException on failure`() = runTest {
        coEvery { gateway.request("exec.approval.resolve", any()) } returns GatewayResponse(
            id = "t", ok = false, error = JsonError(message = "not found"),
        )

        approvalApi.resolve("r1", approved = true)
    }

    @Test
    fun `resolve throws ApprovalException with fallback message`() = runTest {
        coEvery { gateway.request("exec.approval.resolve", any()) } returns GatewayResponse(
            id = "t", ok = false, error = JsonError(message = null),
        )

        try {
            approvalApi.resolve("r1", approved = true)
        } catch (e: ApprovalApi.ApprovalException) {
            assertEquals("Failed to resolve approval", e.message)
        }
    }
}
