package com.aei.innovision

import kotlin.math.min

object FuzzyMatcher {

    /**
     * Calculates the Levenshtein distance between two strings.
     * The distance is the number of insertions, deletions, or substitutions 
     * required to change one string into another.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val str1 = s1.lowercase().trim()
        val str2 = s2.lowercase().trim()
        
        val dp = Array(str1.length + 1) { IntArray(str2.length + 1) }

        for (i in 0..str1.length) {
            for (j in 0..str2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        dp[i][j] = min(
                            min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + if (str1[i - 1] == str2[j - 1]) 0 else 1
                        )
                    }
                }
            }
        }
        return dp[str1.length][str2.length]
    }

    /**
     * Returns a similarity score between 0.0 and 1.0.
     * 1.0 means an exact match, 0.0 means completely different.
     */
    fun getSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        
        val maxLength = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return (maxLength - distance).toFloat() / maxLength.toFloat()
    }

    /**
     * Finds the best match from a list of suggestions.
     * Returns a Pair of (Suggested String, Similarity Score) or null if no good match.
     */
    fun findBestMatch(input: String, suggestions: List<String>, threshold: Float = 0.6f): Pair<String, Float>? {
        if (input.isBlank()) return null
        
        return suggestions.map { it to getSimilarity(input, it) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
    }
}
