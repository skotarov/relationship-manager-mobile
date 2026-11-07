package com.onlineimoti.calllog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCallLogSearchClientTest {
    @Test
    fun `extracts a matching yellow company note from the authoritative server contact`() {
        val item = JSONObject(
            """{
                "notes": [{
                    "company_id": "maxim",
                    "text": "Светльо | автободжия Манастирски Изток"
                }]
            }""".trimIndent(),
        )

        assertEquals(
            "Светльо | автободжия Манастирски Изток",
            ServerCallLogSearchClient.matchingNoteSnippet(item, "Манастирски"),
        )
    }
}
