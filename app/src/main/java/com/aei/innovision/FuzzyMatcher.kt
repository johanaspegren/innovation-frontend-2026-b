package com.aei.innovision

import kotlin.math.min

object FuzzyMatcher {

    // ---------- helpers ----------

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> =
        normalize(s).split(" ").filter { it.length > 1 }.toSet()

    private fun ngrams(s: String, n: Int = 3): Set<String> {
        val clean = normalize(s)
        if (clean.length < n) return emptySet()
        return (0..clean.length - n).map { clean.substring(it, it + n) }.toSet()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        return (a intersect b).size.toFloat() / (a union b).size.toFloat()
    }

    // ---------- existing API (unchanged) ----------

    fun levenshteinDistance(s1: String, s2: String): Int {
        val str1 = normalize(s1)
        val str2 = normalize(s2)
        val dp = Array(str1.length + 1) { IntArray(str2.length + 1) }

        for (i in 0..str1.length) {
            for (j in 0..str2.length) {
                dp[i][j] = when {
                    i == 0 -> j
                    j == 0 -> i
                    else -> minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + if (str1[i - 1] == str2[j - 1]) 0 else 1
                    )
                }
            }
        }
        return dp[str1.length][str2.length]
    }

    fun getSimilarity(s1: String, s2: String): Float {
        if (s1.isBlank() && s2.isBlank()) return 1f
        if (s1.isBlank() || s2.isBlank()) return 0f
        val maxLength = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return (maxLength - distance).toFloat() / maxLength
    }

    private fun combinedScore(a: String, b: String): Float {
        val tokenScore = jaccard(tokens(a), tokens(b))
        val ngramScore = jaccard(ngrams(a), ngrams(b))
        val levenshteinScore = getSimilarity(a, b)

        return (
                0.4f * tokenScore +
                        0.4f * ngramScore +
                        0.2f * levenshteinScore
                )
    }

    fun findBestMatch(
        input: String,
        suggestions: List<String>,
        threshold: Float = 0.6f
    ): Pair<String, Float>? {
        if (input.isBlank()) return null

        return suggestions
            .map { it to combinedScore(input, it) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
    }
}
