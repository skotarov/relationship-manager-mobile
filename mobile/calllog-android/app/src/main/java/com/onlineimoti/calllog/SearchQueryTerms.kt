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
        return terms.all { term ->
            when (term) {
                is Term.Digits -> digitsHaystack.contains(term.value)
                is Term.Text -> textHaystack.contains(term.value)
            }
        }
    }

    fun textTerms(): List<String> = terms.filterIsInstance<Term.Text>().map { it.value }

    fun digitTerms(): List<String> = terms.filterIsInstance<Term.Digits>().map { it.value }

    private sealed interface Term {
        val value: String

        data class Text(override val value: String) : Term
        data class Digits(override val value: String) : Term
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
                        raw.all(Char::isDigit) && digits.isNotBlank() -> Term.Digits(digits)
                        else -> raw.lowercase(Locale.getDefault()).takeIf { it.isNotBlank() }?.let(Term::Text)
                    }
                }
                .distinct()
                .toList()
            return SearchQueryTerms(terms)
        }
    }
}
