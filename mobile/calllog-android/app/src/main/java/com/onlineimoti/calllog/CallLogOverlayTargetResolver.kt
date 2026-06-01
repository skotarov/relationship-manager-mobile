package com.onlineimoti.calllog

import android.content.Context

internal object CallLogOverlayTargetResolver {
    fun detect(context: Context, titleTexts: List<String>, screenTexts: List<String>): CallLogOverlayTarget {
        val screenOnlyKind = CallLogOverlayTextRules.classifyScreen(emptyList(), screenTexts)
        if (screenOnlyKind == CallLogOverlayScreenKind.GENERAL_LOG) return CallLogOverlayTarget()

        val titlePhoneTarget = targetFromTexts(context, titleTexts, allowNameLookup = false)
        if (titlePhoneTarget.phone.isNotBlank()) return titlePhoneTarget

        val screenKind = CallLogOverlayTextRules.classifyScreen(titleTexts, screenTexts)
        if (screenKind == CallLogOverlayScreenKind.GENERAL_LOG) return CallLogOverlayTarget()

        val allowContactNameLookup = screenKind == CallLogOverlayScreenKind.CONTACT_DETAIL ||
            screenKind == CallLogOverlayScreenKind.CONTACT_HISTORY

        val titleTarget = targetFromTexts(context, titleTexts, allowNameLookup = allowContactNameLookup)
        if (titleTarget.phone.isNotBlank()) return titleTarget

        val headerTarget = targetFromTexts(
            context = context,
            texts = titleTexts + screenTexts.take(16),
            allowNameLookup = allowContactNameLookup,
        )
        if (headerTarget.phone.isNotBlank()) return headerTarget

        val allTexts = cleanCandidateTexts(titleTexts + screenTexts)
        if (allowContactNameLookup) {
            val wholeTarget = targetFromTexts(context, allTexts, allowNameLookup = true)
            if (wholeTarget.phone.isNotBlank()) return wholeTarget
        }

        val phones = allTexts.mapNotNull(CallLogOverlayTextRules::extractPhoneCandidate).distinct()
        return if (phones.size == 1 && screenKind != CallLogOverlayScreenKind.UNKNOWN) {
            CallLogOverlayTarget(
                phone = phones.first(),
                title = CallLogOverlayTextRules.firstLikelyTitle(allTexts),
            )
        } else {
            CallLogOverlayTarget()
        }
    }

    fun keepText(text: String): Boolean = CallLogOverlayTextRules.keepText(text)

    private fun targetFromTexts(
        context: Context,
        texts: List<String>,
        allowNameLookup: Boolean,
    ): CallLogOverlayTarget {
        val cleanedTexts = cleanCandidateTexts(texts)
        val phones = cleanedTexts.mapNotNull(CallLogOverlayTextRules::extractPhoneCandidate).distinct()
        val title = CallLogOverlayTextRules.firstLikelyTitle(cleanedTexts)
        if (phones.size == 1) return CallLogOverlayTarget(phone = phones.first(), title = title)

        if (!allowNameLookup) return CallLogOverlayTarget()
        val resolved = CallLogOverlayContactMatcher.resolvePhoneAndTitleFromVisibleName(context, cleanedTexts)
        return if (resolved.phone.isNotBlank()) {
            CallLogOverlayTarget(phone = resolved.phone, title = title.ifBlank { resolved.title })
        } else {
            CallLogOverlayTarget()
        }
    }

    private fun cleanCandidateTexts(texts: List<String>): List<String> {
        return texts
            .map { it.trim() }
            .filter { CallLogOverlayTextRules.isValidPhoneText(it) || CallLogOverlayTextRules.isValidContactNameCandidateShape(it) }
            .distinct()
    }
}
