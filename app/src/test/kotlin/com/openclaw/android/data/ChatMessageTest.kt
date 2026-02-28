package com.openclaw.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun `default status is SENT`() {
        val msg = ChatMessage(id = "1", role = ChatMessage.Role.USER, content = "hi")
        assertEquals(ChatMessage.Status.SENT, msg.status)
    }

    @Test
    fun `default isStreaming is false`() {
        val msg = ChatMessage(id = "1", role = ChatMessage.Role.USER, content = "hi")
        assertFalse(msg.isStreaming)
    }

    @Test
    fun `copy updates content while preserving other fields`() {
        val msg = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            content = "hello",
            isStreaming = true,
        )
        val updated = msg.copy(content = "hello world", isStreaming = false)
        assertEquals("1", updated.id)
        assertEquals(ChatMessage.Role.ASSISTANT, updated.role)
        assertEquals("hello world", updated.content)
        assertFalse(updated.isStreaming)
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
