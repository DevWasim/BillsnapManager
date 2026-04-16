package com.billsnap.manager.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.BillRepository
import com.billsnap.manager.data.OcrCorrectionEntity
import com.billsnap.manager.data.PaymentRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

/**
 * ViewModel for the Detail screen.
 * Loads a single bill by ID and handles payment status toggling with paid_timestamp tracking.
 * Supports incremental "Add Payment" functionality with automatic status derivation.
 *
 * Payment status is ALWAYS derived from amounts:
 *   - UNPAID: paidAmount == 0
 *   - PARTIAL: 0 < paidAmount < totalAmount
 *   - PAID: paidAmount >= totalAmount
 */
class DetailViewModel(
    private val repository: BillRepository,
    private val db: AppDatabase
) : ViewModel() {

    private val _bill = MutableLiveData<BillEntity?>()
    val bill: LiveData<BillEntity?> = _bill

    fun loadBill(id: Long) {
        viewModelScope.launch {
            _bill.value = repository.getBillById(id)
        }
    }

    /**
     * Derives payment status from amount fields.
     * This is the single source of truth for status — UI never sets status directly.
     */
    companion object {
        fun deriveStatus(totalAmount: Double?, paidAmount: Double): String {
            val total = totalAmount ?: 0.0
            return when {
                total <= 0 -> if (paidAmount > 0) "Paid" else "Unpaid"
                paidAmount >= total -> "Paid"
                paidAmount > 0 -> "Partial"
                else -> "Unpaid"
            }
        }
    }

    /**
     * Toggles payment status. If bill has amounts, syncs with amount logic.
     */
    fun togglePaymentStatus() {
        val current = _bill.value ?: return
        val total = current.totalAmount ?: 0.0
        val now = System.currentTimeMillis()

        viewModelScope.launch {
            if (total > 0) {
                // Amount-aware toggle: flip between fully paid and fully unpaid
                val newPaid = if (current.paymentStatus == "Paid") 0.0 else total
                val newRemaining = total - newPaid
                val newStatus = deriveStatus(total, newPaid)
                val paidTimestamp = if (newStatus == "Paid") now else null
                val lastPaymentDate = if (newPaid > 0) now else null

                repository.updatePaymentAmounts(current.id, newPaid, newRemaining, newStatus, lastPaymentDate, paidTimestamp, current.paymentHistoryJson)
                _bill.value = current.copy(
                    paidAmount = newPaid,
                    remainingAmount = newRemaining,
                    paymentStatus = newStatus,
                    paidTimestamp = paidTimestamp,
                    lastPaymentDate = lastPaymentDate
                )
            } else {
                // Legacy toggle for bills without amounts
                val newStatus = if (current.paymentStatus == "Paid") "Unpaid" else "Paid"
                val paidTimestamp = if (newStatus == "Paid") now else null
                repository.updatePaymentStatusWithTimestamp(current.id, newStatus, paidTimestamp)
                _bill.value = current.copy(paymentStatus = newStatus, paidTimestamp = paidTimestamp)
            }

            // Log the action for audit
            val billName = current.customName
            com.billsnap.manager.security.ActivityLogger.logAction(
                "Payment Status Changed",
                "Bill: $billName → ${_bill.value?.paymentStatus}"
            )
        }
    }

    /**
     * Adds an incremental payment to this bill.
     * Cumulates paidAmount, recalculates remainingAmount, and derives new status.
     */
    fun addPayment(amount: Double) {
        val current = _bill.value ?: return
        val total = current.totalAmount ?: 0.0
        if (total <= 0 || amount <= 0) return

        val newPaid = minOf(current.paidAmount + amount, total)
        val newRemaining = maxOf(total - newPaid, 0.0)
        val newStatus = deriveStatus(total, newPaid)
        val now = System.currentTimeMillis()
        val paidTimestamp = if (newStatus == "Paid") now else current.paidTimestamp

        // Process JSON History Tracker
        val gson = Gson()
        val listType = object : TypeToken<MutableList<PaymentRecord>>() {}.type
        val historyList: MutableList<PaymentRecord> = if (!current.paymentHistoryJson.isNullOrBlank()) {
            try {
                gson.fromJson(current.paymentHistoryJson, listType) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        
        historyList.add(PaymentRecord(amount, now))
        val newHistoryJson = gson.toJson(historyList)

        viewModelScope.launch {
            repository.updatePaymentAmounts(current.id, newPaid, newRemaining, newStatus, now, paidTimestamp, newHistoryJson)
            _bill.value = current.copy(
                paidAmount = newPaid,
                remainingAmount = newRemaining,
                paymentStatus = newStatus,
                paidTimestamp = paidTimestamp,
                lastPaymentDate = now,
                paymentHistoryJson = newHistoryJson
            )

            // Log for audit transparency
            com.billsnap.manager.security.ActivityLogger.logAction(
                "Payment Added",
                "Bill: ${current.customName}, Amount: ${"%.2f".format(amount)}, New Total Paid: ${"%.2f".format(newPaid)}"
            )
        }
    }

    fun updateCustomerId(billId: Long, customerId: Long) {
        viewModelScope.launch {
            repository.updateCustomerId(billId, customerId)
            _bill.value = repository.getBillById(billId)
        }
    }

    fun saveOcrCorrection(originalText: String, correctedText: String) {
        if (originalText.isBlank() || correctedText.isBlank()) return
        viewModelScope.launch {
            try {
                db.ocrCorrectionDao().insertCorrection(
                    OcrCorrectionEntity(
                        originalText = originalText,
                        correctedText = correctedText
                    )
                )
            } catch (e: Exception) {
                // Ignore unique constraint or other db errors
            }
        }
    }
}

