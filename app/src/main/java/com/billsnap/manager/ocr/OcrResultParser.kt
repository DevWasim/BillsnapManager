package com.billsnap.manager.ocr

import com.equationl.paddleocr4android.bean.OcrResult

data class ParsedBillData(
    val vendorName: String = "",
    val totalAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val invoiceDate: String = "",
    val invoiceNumber: String = "",
    val rawText: String = "",
    val confidence: Float = 0f
)

object OcrResultParser {

    // Regex to match dates like DD/MM/YYYY, YYYY-MM-DD, MM.DD.YY
    private val DATE_REGEX = Regex("""\b(\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4})\b""")
    
    // Regex for amounts allowing international separators e.g., $1,000.50 or €1.000,50
    // Will extract just the numerical part to be parsed.
    private val AMOUNT_REGEX = Regex("""(?:(?i)Total|Amount|Due|Sum|Bal)[^\d]*(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)""")
    private val TAX_REGEX = Regex("""(?:(?i)Tax|VAT|GST)[^\d]*(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?)""")
    
    // Invoice/Receipt Number detection
    private val INVOICE_NUM_REGEX = Regex("""(?i)(?:Inv|Invoice|Receipt|Bill|Ticket)\s*(?:No|#|Number)?[:\s]*([A-Z0-9-]{3,15})""")

    fun parse(ocrResult: OcrResult): ParsedBillData {
        val lines = ocrResult.simpleText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedBillData()

        val rawText = ocrResult.simpleText
        
        // Calculate average confidence from the raw models
        var confSum = 0f
        var count = 0
        ocrResult.outputRawResult.forEach { model ->
            confSum += model.confidence
            count++
        }
        val avgConfidence = if (count > 0) confSum / count else 0f

        var totalAmt = 0.0
        var taxAmt = 0.0
        var dateStr = ""
        var invoiceNum = ""
        
        // Assume vendor is usually at the very top (first 1-3 lines) with largest text / center alignment.
        // For simplicity, we just use the first line, filtering out extremely short strings just in case.
        val vendorName = lines.firstOrNull { it.length > 2 } ?: "Unknown Vendor"

        for (line in lines) {
            // Find Date
            if (dateStr.isEmpty()) {
                val dateMatch = DATE_REGEX.find(line)
                if (dateMatch != null) dateStr = dateMatch.groupValues[1]
            }

            // Find Invoice Number
            if (invoiceNum.isEmpty()) {
                val invMatch = INVOICE_NUM_REGEX.find(line)
                if (invMatch != null) invoiceNum = invMatch.groupValues[1]
            }

            // Find Tax
            val taxMatch = TAX_REGEX.find(line)
            if (taxMatch != null) {
                val parsedTax = parseAmount(taxMatch.groupValues[1])
                if (parsedTax > taxAmt) taxAmt = parsedTax
            }

            // Find Total
            val totalMatch = AMOUNT_REGEX.find(line)
            if (totalMatch != null) {
                val parsedTotal = parseAmount(totalMatch.groupValues[1])
                if (parsedTotal > totalAmt) totalAmt = parsedTotal
            }
        }
        
        // Fallback: If no implicit 'Total' label is found, just look for the largest currency-like value at the bottom
        if (totalAmt == 0.0) {
            val looseAmountRegex = Regex("""[$€£¥]?\s?(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{2}))""")
            val looseMatches = looseAmountRegex.findAll(rawText)
            looseMatches.forEach { match ->
                val amt = parseAmount(match.groupValues[1])
                if (amt > totalAmt) totalAmt = amt
            }
        }

        return ParsedBillData(
            vendorName = vendorName,
            totalAmount = totalAmt,
            taxAmount = taxAmt,
            invoiceDate = dateStr,
            invoiceNumber = invoiceNum,
            rawText = rawText,
            confidence = avgConfidence
        )
    }

    /**
     * Handles comma/period normalization for amount parsing.
     * Extracts double value from string like 1,000.50 or 1.000,50
     */
    private fun parseAmount(rawAmount: String): Double {
        var cleanStr = rawAmount.replace(" ", "")
        
        // Check if last non-digit separator is comma (European style: 1.000,50)
        val lastComma = cleanStr.lastIndexOf(',')
        val lastPoint = cleanStr.lastIndexOf('.')

        if (lastComma > lastPoint && lastComma >= cleanStr.length - 3) {
            // European format -> Convert to US for Double.parseDouble
            cleanStr = cleanStr.replace(".", "")
            cleanStr = cleanStr.replace(",", ".")
        } else {
            // US format -> remove commas
            cleanStr = cleanStr.replace(",", "")
        }

        return try {
            cleanStr.toDouble()
        } catch (e: Exception) {
            0.0
        }
    }
}
