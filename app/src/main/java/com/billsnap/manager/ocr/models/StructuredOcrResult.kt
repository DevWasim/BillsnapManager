package com.billsnap.manager.ocr.models

data class StructuredOcrResult(
    val boundingBoxes: List<BoundingBoxResult>,
    val vendorName: String?,
    val totalAmount: Double?,
    val taxAmount: Double?,
    val invoiceDate: String?,
    val invoiceNumber: String?,
    val overallConfidence: Float,
    val matchedCustomerId: Long?
)

data class BoundingBoxResult(
    val text: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val blockType: BlockType
)

enum class BlockType {
    VENDOR, TOTAL, TAX, DATE, INVOICE_NUMBER, GENERAL_TEXT
}
