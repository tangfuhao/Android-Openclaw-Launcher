package com.openclaw.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun defaultStatusIsSent() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hi")
        assertEquals(ChatMessage.Status.SENT, msg.status)
    }

    @Test
    fun defaultIsStreamingIsFalse() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hi")
        assertFalse(msg.isStreaming)
    }

    @Test
    fun textContentReturnsConcatenatedTextBlocks() {
        val msg = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            contentBlocks = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.Text("world"),
            ),
        )
        assertEquals("hello\nworld", msg.textContent)
    }

    @Test
    fun copyUpdatesContentBlocksWhilePreservingOtherFields() {
        val msg = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            contentBlocks = listOf(ContentBlock.Text("hello")),
            isStreaming = true,
        )
        val updated = msg.copy(
            contentBlocks = listOf(ContentBlock.Text("hello world")),
            isStreaming = false,
        )
        assertEquals("1", updated.id)
        assertEquals(ChatMessage.Role.ASSISTANT, updated.role)
        assertEquals("hello world", updated.textContent)
        assertFalse(updated.isStreaming)
    }

    @Test
    fun ofTextCreatesMessageWithSingleTextBlock() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hello")
        assertEquals(1, msg.contentBlocks.size)
        assertTrue(msg.contentBlocks[0] is ContentBlock.Text)
        assertEquals("hello", msg.textContent)
    }

    @Test
    fun ofTextAcceptsExplicitRunPhase() {
        val msg = ChatMessage.ofText(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            text = "thinking",
            runPhase = RunPhase.RESPONDING,
        )
        assertEquals(RunPhase.RESPONDING, msg.runPhase)
    }

    @Test
    fun runPhaseEnumHasExpectedValues() {
        val phases = RunPhase.entries
        assertEquals(4, phases.size)
        assertTrue(phases.contains(RunPhase.IDLE))
        assertTrue(phases.contains(RunPhase.THINKING))
        assertTrue(phases.contains(RunPhase.RESPONDING))
        assertTrue(phases.contains(RunPhase.DONE))
    }

    @Test
    fun roleEnumHasAllExpectedValues() {
        val roles = ChatMessage.Role.entries
        assertEquals(3, roles.size)
        assertTrue(roles.contains(ChatMessage.Role.USER))
        assertTrue(roles.contains(ChatMessage.Role.ASSISTANT))
        assertTrue(roles.contains(ChatMessage.Role.SYSTEM))
    }

    @Test
    fun statusEnumHasAllExpectedValues() {
        val statuses = ChatMessage.Status.entries
        assertEquals(3, statuses.size)
        assertTrue(statuses.contains(ChatMessage.Status.SENDING))
        assertTrue(statuses.contains(ChatMessage.Status.SENT))
        assertTrue(statuses.contains(ChatMessage.Status.ERROR))
    }
}
