package com.remotecontroller.client.domain.repository

import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipSnapshotTest {
    private val first = ClipNote(id = 1, from = ClipNote.WEB, text = "one", sentAt = 0)
    private val second = ClipNote(id = 2, from = ClipNote.WEB, text = "two", sentAt = 0)

    @Test
    fun historyReplacesTheConversationAndClearsTheBanner() {
        val snapshot = ClipSnapshot(notes = listOf(first), banner = "old").after(ClipEvent.History(listOf(second)))

        assertEquals(listOf(second), snapshot.notes)
        assertNull(snapshot.banner)
    }

    @Test
    fun aNoteIsAdded() {
        assertEquals(listOf(first, second), ClipSnapshot(notes = listOf(first)).after(ClipEvent.Note(second)).notes)
    }

    @Test
    fun clearingKeepsTheConnection() {
        val snapshot = ClipSnapshot(notes = listOf(first), connected = true).after(ClipEvent.Cleared)

        assertTrue(snapshot.notes.isEmpty())
        assertTrue(snapshot.connected)
    }

    @Test
    fun aRefusalOnlySetsTheBanner() {
        val snapshot = ClipSnapshot(notes = listOf(first)).after(ClipEvent.Rejected("too large"))

        assertEquals("too large", snapshot.banner)
        assertEquals(listOf(first), snapshot.notes)
    }
}
