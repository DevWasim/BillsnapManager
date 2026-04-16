package com.billsnap.manager.ocr

import android.util.Log
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.CustomerNameIdPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object SmartOcrProcessor {
    private const val TAG = "SmartOcrProcessor"
    
    // Fuzzy matching minimum similarity percentage (0.0 to 1.0)
    private const val SIMILARITY_THRESHOLD = 0.75

    // Regex Patterns
    private val amountRegex = Regex("""\$?\s?(\d+[.,]\d{2})(?!\d)""") // Matches $12.34 or 12,34
    private val dateRegex = Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\b""") // Matches 12/31/2023 or 12-31-23
    private val phoneRegex = Regex("""\b\+?(\d{10,14})\b""") // Matches basic phone sequences

    suspend fun process(
        rawText: String,
        db: AppDatabase
    ): SmartOcrResult = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Starting Smart Processing")

        // 1. Auto-Correction Memory
        var processedText = rawText
        try {
            val corrections = db.ocrCorrectionDao().getAllCorrectionsSync()
            // Iterate over corrections. Sort by length to avoid partial replacements of longer phrases? 
            corrections.sortedByDescending { it.originalText.length }.forEach { correction ->
                processedText = processedText.replace(correction.originalText, correction.correctedText, ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying auto-corrections", e)
        }

        // 2. Regex Extractions
        var extractedAmount: Double? = null
        var extractedDate: String? = null
        var extractedPhone: String? = null

        amountRegex.find(processedText)?.let { match ->
            try {
                // Ensure proper dot separator for double parsing
                val cleanAmount = match.groupValues[1].replace(",", ".")
                extractedAmount = cleanAmount.toDouble()
            } catch (e: Exception) {
                // parse error
            }
        }

        dateRegex.find(processedText)?.let { match ->
            extractedDate = match.groupValues[1]
        }

        phoneRegex.find(processedText)?.let { match ->
            extractedPhone = match.groupValues[1]
        }

        // 3. Fuzzy Customer Matching
        var matchedCustomerId: Long? = null
        try {
            val customers = db.customerDao().getCustomerNamesAndIds()
            // Optimization: Tokenize processed text into words
            val textTokens = processedText.split("\\s+".toRegex()).map { it.lowercase() }
            
            var bestMatchScore = 0.0
            
            for (customer in customers) {
                val customerNameLower = customer.name.lowercase()
                
                // Directly check substring first
                if (processedText.lowercase().contains(customerNameLower)) {
                    matchedCustomerId = customer.customerId
                    break // Exact substring match is perfect
                }
                
                // Fuzzy match against tokens
                for (token in textTokens) {
                    if (kotlin.math.abs(token.length - customerNameLower.length) > 3) continue // Length diff too high
                    
                    val similarity = calculateSimilarity(token, customerNameLower)
                    if (similarity >= SIMILARITY_THRESHOLD && similarity > bestMatchScore) {
                        bestMatchScore = similarity
                        matchedCustomerId = customer.customerId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during fuzzy matching", e)
        }

        // 4. Heuristics & Risk Scoring
        var lateRiskScore: Float? = null
        if (matchedCustomerId != null) {
            try {
                val pastBills = db.billDao().getRecentPaidBillsForCustomerSync(matchedCustomerId)
                if (pastBills.isNotEmpty()) {
                    var totalDelayDays = 0L
                    var delayedBillsCount = 0

                    for (bill in pastBills) {
                        if (bill.paidTimestamp != null) {
                            // Calculate delay relative to either reminderDate or bill date
                            val targetDate = bill.reminderDatetime ?: bill.timestamp
                            if (bill.paidTimestamp > targetDate) {
                                val delayMs = bill.paidTimestamp - targetDate
                                val delayDays = TimeUnit.MILLISECONDS.toDays(delayMs)
                                if (delayDays > 0) {
                                    totalDelayDays += delayDays
                                    delayedBillsCount++
                                }
                            }
                        }
                    }

                    if (delayedBillsCount > 0) {
                        val avgDelay = totalDelayDays.toFloat() / pastBills.size
                        // Normalize a risk score between 0.0 and 1.0 (assuming 30+ days average delay is worst)
                        lateRiskScore = min(1.0f, avgDelay / 30f)
                    } else {
                        lateRiskScore = 0.0f
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating late risk score", e)
            }
        }

        Log.d(TAG, "Smart Processing Finished. Matches: cust=$matchedCustomerId, amt=$extractedAmount, date=$extractedDate")

        SmartOcrResult(
            amount = extractedAmount,
            date = extractedDate,
            phone = extractedPhone,
            matchedCustomerId = matchedCustomerId,
            lateRiskScore = lateRiskScore,
            processedText = processedText
        )
    }

    /**
     * Calculates the similarity between two strings based on Levenshtein Distance.
     * Returns a score from 0.0 (completely different) to 1.0 (identical).
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val maxLength = max(s1.length, s2.length)
        if (maxLength == 0) return 1.0

        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
