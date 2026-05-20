package com.onlineimoti.calllog

data class LookupResult(
    val phone: String,
    val direction: String,
    val title: String,
    val subtitle: String,
    val lines: List<String>,
    val openFormUrl: String,
    val openHistoryUrl: String,
)
