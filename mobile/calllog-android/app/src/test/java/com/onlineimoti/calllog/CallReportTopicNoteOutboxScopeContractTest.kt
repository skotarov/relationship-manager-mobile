package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test

class CallReportTopicNoteOutboxScopeContractTest {
    @Test
    fun unknownNumberQualifiesForServerCompanyOutbox() {
        assertTrue(ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = false,
            unknownNumber = true,
        ))
    }
}
