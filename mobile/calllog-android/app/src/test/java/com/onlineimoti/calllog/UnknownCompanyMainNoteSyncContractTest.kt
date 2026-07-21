package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownCompanyMainNoteSyncContractTest {
    @Test
    fun unknownCompanyScopeMustBeServerEligible() {
        val eligible = ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = false,
            unknownNumber = true,
        )
        assertTrue(eligible)
    }
}
