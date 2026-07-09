package com.onlineimoti.calllog

import java.util.Locale

/**
 * A Home search query is a set of independent terms, not one literal phrase.
 * Every entered term must be found, but terms may appear in any order and may
 * be satisfied by different contact fields (for example name + phone number).
 */
internal class SearchQueryTerms private constructor(
    private val terms: List<Term>,
) {
    val isEmpty: Boolean
        get() = terms.isEmpty()

    fun matches(vararg fields: String): Boolean {
        if (terms.isEmpty()) return false
        val textHaystack = fields
            .asSequence()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase(Locale.getDefault())
        val digitsHaystack = fields
            .asSequence()
            .flatMap { it.asSequence() }
            .filter(Char::isDigit)
            .joinToString("")
        val phoneKeyHaystack = fields
            .asSequence()
            .map(PhoneNormalizer::key)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return terms.all { term ->
            when (term) {
                is Term.Digits -> term.values.any { value ->
                    digitsHaystack.contains(value) || phoneKeyHaystack.contains(value)
                }
                is Term.Text -> textHaystack.contains(term.value)
            }
        }
    }

    fun textTerms(): List<String> = terms.filterIsInstance<Term.Text>().map { it.value }

    fun digitTerms(): List<String> = terms.filterIsInstance<Term.Digits>().map { it.value }

    private sealed interface Term {
        val value: String

        data class Text(override val value: String) : Term
        data class Digits(override val value: String, val values: Set<String>) : Term
    }

    companion object {
        fun from(query: String): SearchQueryTerms {
            val terms = query.trim()
                .split(Regex("\\s+"))
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .mapNotNull { raw ->
                    val digits = raw.filter(Char::isDigit)
                    when {
                        raw.all { it.isDigit() || it == '+' } && digits.isNotBlank() -> Term.Digits(
                            value = digits,
                            values = digitAlternates(digits),
                        )
                        else -> raw.lowercase(Locale.getDefault()).takeIf { it.isNotBlank() }?.let(Term::Text)
                    }
                }
                .distinct()
                .toList()
            return SearchQueryTerms(terms)
        }

        private fun digitAlternates(digits: String): Set<String> {
            val normalized = if (digits.startsWith("00") && digits.length > 4) digits.drop(2) else digits
            return linkedSetOf<String>().apply {
                add(digits)
                add(normalized)
                if (normalized.startsWith("359")) add(normalized.drop(3))
                if (normalized.startsWith("0")) add(normalized.drop(1))
                PhoneNormalizer.key(normalized).takeIf { it.isNotBlank() }?.let(::add)
            }.filter { it.isNotBlank() }.toSet()
        }
    }
}
