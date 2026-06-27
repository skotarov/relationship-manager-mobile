package com.onlineimoti.calllog

import android.content.Context

/**
 * Canonical caller-popup content. Both the custom overlay and the Android
 * system notification render this exact model, so their note order and firm
 * labels cannot drift apart.
 */
internal data class PostCallLookupDisplayContent(
    val header: String,
    val rows: List<PostCallLookupDisplayRow>,
)

internal data class PostCallLookupDisplayRow(
    val kind: Kind,
    val text: String,
) {
    enum class Kind { IDENTITY, GENERAL_NOTE, CALL_NOTE, SERVER_INFO }

    fun plainText(): String = when (kind) {
        Kind.IDENTITY -> text
        Kind.GENERAL_NOTE -> "☰ $text"
        Kind.CALL_NOTE -> "💬 $text"
        Kind.SERVER_INFO -> "☁ $text"
    }
}

internal object PostCallLookupDisplayRows {
    /** Identity plus local and server notes: 1 + 2 local + 3 firm main + 1 last call note. */
    private const val MAX_ROWS = 7
    private const val SERVER_LABEL = "Сървър"

    fun build(
        context: Context,
        phone: String,
        identity: String,
        remoteRows: List<PostCallLookupRemoteRow>,
        lookupServerLines: List<String> = emptyList(),
    ): PostCallLookupDisplayContent {
        val localRows = LocalCallStatsProvider.buildPopupInfoRows(context.applicationContext, phone)
        val header = localRows.firstOrNull { !isLocalNoteRow(it) }
            .orEmpty()
            .ifBlank { context.getString(R.string.overlay_no_previous_call) }

        val localNoteRows = localRows
            .filter(::isLocalNoteRow)
            .mapNotNull(::localNoteRow)
        val visibleLocalRows = localNoteRows.filterNot { local ->
            remoteRows.any { remote ->
                normalize(local.text).equals(normalize(remote.note), ignoreCase = true)
            }
        }
        val remoteDisplayRows = remoteRows.map { remote ->
            PostCallLookupDisplayRow(
                kind = when (remote.kind) {
                    PostCallLookupRemoteRow.Kind.GENERAL_NOTE -> PostCallLookupDisplayRow.Kind.GENERAL_NOTE
                    PostCallLookupRemoteRow.Kind.CALL_NOTE -> PostCallLookupDisplayRow.Kind.CALL_NOTE
                },
                text = listOf(remote.companyName.ifBlank { SERVER_LABEL }, remote.note)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            )
        }
        // lookup.php is already queried by the incoming-call flow. It is a
        // deliberate fallback for legacy records that history_lookup cannot
        // represent with a structured company event yet.
        val fallbackServerRows = if (remoteDisplayRows.isEmpty()) {
            lookupServerLines
                .asSequence()
                .map(::compact)
                .filter { it.isNotBlank() }
                .filterNot { candidate ->
                    localNoteRows.any { normalize(it.text).equals(normalize(candidate), ignoreCase = true) }
                }
                .distinctBy(::normalize)
                .take(MAX_ROWS)
                .map { line ->
                    PostCallLookupDisplayRow(
                        kind = PostCallLookupDisplayRow.Kind.SERVER_INFO,
                        text = "$SERVER_LABEL · $line",
                    )
                }
                .toList()
        } else {
            emptyList()
        }
        val allRows = buildList {
            identity.trim().takeIf { it.isNotBlank() }?.let {
                add(PostCallLookupDisplayRow(PostCallLookupDisplayRow.Kind.IDENTITY, it))
            }
            addAll(visibleLocalRows)
            addAll(remoteDisplayRows)
            addAll(fallbackServerRows)
        }.take(MAX_ROWS)
        return PostCallLookupDisplayContent(header = header, rows = allRows)
    }

    private fun isLocalNoteRow(value: String): Boolean =
        value.startsWith(ICON_GENERAL_NOTE) || value.startsWith(ICON_CALL_NOTE)

    private fun localNoteRow(value: String): PostCallLookupDisplayRow? = when {
        value.startsWith(ICON_GENERAL_NOTE) -> PostCallLookupDisplayRow(
            PostCallLookupDisplayRow.Kind.GENERAL_NOTE,
            value.removePrefix(ICON_GENERAL_NOTE).trim(),
        )
        value.startsWith(ICON_CALL_NOTE) -> PostCallLookupDisplayRow(
            PostCallLookupDisplayRow.Kind.CALL_NOTE,
            value.removePrefix(ICON_CALL_NOTE).trim(),
        )
        else -> null
    }

    private fun compact(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    private fun normalize(value: String): String = compact(value)

    private const val ICON_GENERAL_NOTE = "☰"
    private const val ICON_CALL_NOTE = "💬"
}
