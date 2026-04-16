package com.billsnap.manager.ocr

data class SmartOcrResult(
    val amount: Double? = null,
    val date: String? = null,
    val phone: String? = null,
    val matchedCustomerId: Long? = null,
    val lateRiskScore: Float? = null,
    val processedText: String
) {
    fun toJsonString(): String {
        val json = org.json.JSONObject()
        amount?.let { json.put("amount", it) }
        date?.let { json.put("date", it) }
        phone?.let { json.put("phone", it) }
        matchedCustomerId?.let { json.put("matchedCustomerId", it) }
        lateRiskScore?.let { json.put("lateRiskScore", it) }
        json.put("processedText", processedText)
        return json.toString()
    }
}
