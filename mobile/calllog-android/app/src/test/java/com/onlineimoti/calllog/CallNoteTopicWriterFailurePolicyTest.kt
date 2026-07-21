package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNoteTopicWriterFailurePolicyTest {
    @Test
    fun serverScopedSaveSucceedsOnlyWhenCacheAndQueueSucceed() {
        assertTrue(CompanyMainNoteSavePolicy.isSaved(cacheSaved = true, queued = true))
        assertFalse(CompanyMainNoteSavePolicy.isSaved(cacheSaved = true, queued = false))
        assertFalse(CompanyMainNoteSavePolicy.isSaved(cacheSaved = false, queued = true))
    }
}
