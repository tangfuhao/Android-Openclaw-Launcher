package com.openclaw.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun `default status is SENT`() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hi")
        assertEquals(ChatMessage.Status.SENT, msg.status)
    }

    @Test
    fun `default isStreaming is false`() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hi")
        assertFalse(msg.isStreaming)
    }

    @Test
    fun `textContent returns concatenated text blocks`() {
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
    fun `textContent ignores non-text blocks`() {
        val msg = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            contentBlocks = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.ToolUse(toolId = "t1", name = "bash", input = kotlinx.serialization.json.JsonObject(emptyMap())),
                ContentBlock.Text("world"),
            ),
        )
        assertEquals("hello\nworld", msg.textContent)
    }

    @Test
    fun `copy updates contentBlocks while preserving other fields`() {
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
    fun `ofText creates message with single text block`() {
        val msg = ChatMessage.ofText(id = "1", role = ChatMessage.Role.USER, text = "hello")
        assertEquals(1, msg.contentBlocks.size)
        assertTrue(msg.contentBlocks[0] is ContentBlock.Text)
        assertEquals("hello", msg.textContent)
    }

    @Test
    fun `Role enum has all expected values`() {
        val roles = ChatMessage.Role.entries
        assertEquals(3, roles.size)
        assertTrue(roles.contains(ChatMessage.Role.USER))
        assertTrue(roles.contains(ChatMessage.Role.ASSISTANT))
        assertTrue(roles.contains(ChatMessage.Role.SYSTEM))
    }

    @Test
    fun `Status enum has all expected values`() {
        val statuses = ChatMessage.Status.entries
        assertEquals(3, statuses.size)
        assertTrue(statuses.contains(ChatMessage.Status.SENDING))
        assertTrue(statuses.contains(ChatMessage.Status.SENT))
        assertTrue(statuses.contains(ChatMessage.Status.ERROR))
    }
}
