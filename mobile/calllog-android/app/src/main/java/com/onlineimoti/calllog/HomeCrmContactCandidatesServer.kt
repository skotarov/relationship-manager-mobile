package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> =
        ServerCrmContactsClient.lookup(
            config = ConfigStore.load(context.applicationContext),
            filterState = filterState,
            searchQuery = searchQuery,
            context = context.applicationContext,
        )
}