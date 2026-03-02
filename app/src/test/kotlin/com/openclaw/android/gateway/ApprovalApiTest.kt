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

    @Test
    fun `observeApprovalRequests maps payload correctly`() = runTest {
        val results = mutableListOf<ApprovalApi.ApprovalUiRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            approvalApi.observeApprovalRequests().collect { results.add(it) }
        }

        approvalFlow.emit(ApprovalRequestPayload(
            id = "r1",
            command = "bash",
            commandArgv = listOf("-c", "ls -la"),
            cwd = "/root",
        ))

        assertEquals(1, results.size)
        assertEquals("r1", results[0].requestId)
        assertEquals("bash", results[0].command)
        assertEquals(listOf("-c", "ls -la"), results[0].commandArgv)
        assertEquals("/root", results[0].cwd)

        job.cancel()
    }

    @Test
    fun `observeApprovalRequests displayDescription formats correctly`() = runTest {
        val results = mutableListOf<ApprovalApi.ApprovalUiRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            approvalApi.observeApprovalRequests().collect { results.add(it) }
        }

        approvalFlow.emit(ApprovalRequestPayload(
            id = "r1",
            command = "bash",
            commandArgv = listOf("-c", "rm -rf /tmp"),
            cwd = "/home",
        ))

        assertTrue(results[0].displayDescription.contains("bash"))
        assertTrue(results[0].displayDescription.contains("-c rm -rf /tmp"))
        assertTrue(results[0].displayDescription.contains("(in /home)"))

        job.cancel()
    }

    @Test
    fun `resolve sends id and decision allow`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = true)

        val params = paramsSlot.captured
        assertEquals("r1", params["id"]?.toString()?.trim('"'))
        assertEquals("\"allow\"", params["decision"]?.toString())
    }

    @Test
    fun `resolve sends id and decision deny`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("exec.approval.resolve", capture(paramsSlot)) } returns GatewayResponse(
            id = "t", ok = true,
        )

        approvalApi.resolve("r1", approved = false)
        assertEquals("\"deny\"", paramsSlot.captured["decision"]?.toString())
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
