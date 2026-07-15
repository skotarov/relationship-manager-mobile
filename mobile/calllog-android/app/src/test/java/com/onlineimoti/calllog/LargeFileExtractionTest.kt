package com.onlineimoti.calllog

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class LargeFileExtractionTest {
    @Test
    fun queuedTopicNoteKeepsSyncMapping() {
        val context = Mockito.mock(Context::class.java)
        val appContext = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(appContext)

        val note = CallReportQueuedTopicNote(
            clientEventId = "event-1",
            companyId = "company-1",
            phone = "+359888123456",
            direction = "in",
            occurredAtMs = 1234L,
            durationSeconds = 45L,
            note = "note",
            contactName = "Contact",
            updatedAtMs = 5678L,
            communicationType = "sms",
            clearCompanyAssignment = true,
        )

        val event = note.toSyncEvent(context)
        assertEquals(note.clientEventId, event.clientEventId)
        assertEquals(note.companyId, event.companyId)
        assertEquals(note.phone, event.phone)
        assertEquals(note.direction, event.direction)
        assertEquals(note.occurredAtMs, event.occurredAtMs)
        assertEquals(note.durationSeconds, event.durationSeconds)
        assertEquals(note.note, event.note)
        assertEquals(note.contactName, event.contactName)
        assertEquals(note.communicationType, event.communicationType)
        assertEquals(note.clearCompanyAssignment, event.clearCompanyAssignment)
    }
}
