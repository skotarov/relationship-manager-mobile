package com.onlineimoti.calllog

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/** Keeps visual highlights aligned with the whitespace-separated Home search. */
internal object SearchTextHighlighter {
    fun highlightedText(value: String, query: String, textColor: Int): CharSequence {
        if (value.isBlank()) return value
        val terms = SearchQueryTerms.from(query)
        if (terms.isEmpty) return value
        val spannable = SpannableString(value)
        terms.textTerms().forEach { term -> applyTextHighlight(spannable, value, term, textColor) }
        terms.digitTerms().forEach { term -> applyDigitHighlight(spannable, value, term, textColor) }
        return spannable
    }

    private fun applyTextHighlight(spannable: SpannableString, value: String, term: String, textColor: Int) {
        val lowerValue = value.lowercase()
        val lowerTerm = term.lowercase()
        var start = lowerValue.indexOf(lowerTerm)
        while (start >= 0) {
            applyHighlightSpan(spannable, start, start + term.length, textColor)
            start = lowerValue.indexOf(lowerTerm, start + term.length)
        }
    }

    private fun applyDigitHighlight(spannable: SpannableString, value: String, term: String, textColor: Int) {
        if (term.length < 3) return
        val digitCharIndexes = value.mapIndexedNotNull { index, char -> index.takeIf { char.isDigit() } }
        val valueDigits = digitCharIndexes.map { value[it] }.joinToString("")
        var digitStart = valueDigits.indexOf(term)
        while (digitStart >= 0) {
            val charStart = digitCharIndexes.getOrNull(digitStart) ?: break
            val charEnd = (digitCharIndexes.getOrNull(digitStart + term.length - 1) ?: break) + 1
            applyHighlightSpan(spannable, charStart, charEnd, textColor)
            digitStart = valueDigits.indexOf(term, digitStart + term.length)
        }
    }

    private fun applyHighlightSpan(spannable: SpannableString, start: Int, end: Int, textColor: Int) {
        if (start < 0 || end <= start || end > spannable.length) return
        spannable.setSpan(BackgroundColorSpan(textColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
