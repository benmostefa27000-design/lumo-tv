package com.lumo.tv.data

/**
 * Filtre permanent, sans réglage : ces catégories et contenus ne sont jamais chargés.
 * Pour le retirer, il faut modifier ce fichier et recompiler l'appli.
 */
object ContentFilter {
    private val pattern = Regex(
        "(adult|xxx|18\\s*\\+|\\+\\s*18|porn|\\bsex|erotic|érot|erot|playboy|hustler|brazzers|redlight|red light|nsfw|x-?rated|for adults|hot girls|onlyfans)",
        RegexOption.IGNORE_CASE
    )

    fun blocked(name: String?): Boolean = name != null && pattern.containsMatchIn(name)
}
