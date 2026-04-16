package com.billsnap.manager.ocr

import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.OcrCorrectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrCorrectionLearningUseCase(private val db: AppDatabase) {

    /**
     * Applies stored user corrections to the raw OCR text string.
     * Performs a token-level replacement based on the longest matching strings first.
     */
    suspend fun applyCorrections(rawText: String): String = withContext(Dispatchers.Default) {
        var processedText = rawText
        try {
            val corrections = db.ocrCorrectionDao().getAllCorrectionsSync()
            // Sort by length descending so "Total Amount" replaces before "Amount"
            corrections.sortedByDescending { it.originalText.length }.forEach { correction ->
                // Apply case-insensitive deterministic string replacement (Word boundaries)
                // Use a regex to ensure we only replace whole words, not partial substrings if possible.
                val regex = Regex("(?i)\\b${Regex.escape(correction.originalText)}\\b")
                processedText = processedText.replace(regex, correction.correctedText)
                
                // Fallback for symbols (e.g., "$100" -> "€100") if word boundaries fail
                if (!processedText.contains(correction.correctedText) && processedText.contains(correction.originalText, ignoreCase = true)) {
                    processedText = processedText.replace(correction.originalText, correction.correctedText, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            // Log silently and return raw if db fails
        }
        return@withContext processedText
    }

    /**
     * Persists a newly learned correction from the user editing a chip in the UI.
     */
    suspend fun learnCorrection(original: String, corrected: String, shopId: String?, createdBy: String?) = withContext(Dispatchers.IO) {
        if (original == corrected || original.isBlank() || corrected.isBlank()) return@withContext
        
        try {
            val entity = OcrCorrectionEntity(
                originalText = original.trim(),
                correctedText = corrected.trim(),
                shopId = shopId,
                createdBy = createdBy,
                timestamp = System.currentTimeMillis()
            )
            db.ocrCorrectionDao().insertCorrection(entity)
        } catch (e: Exception) {
            // Unique constraint violation (already exists) will be ignored here safely.
        }
    }
}
