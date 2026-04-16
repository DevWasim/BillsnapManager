package com.billsnap.manager.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a bill record.
 * custom_name has a unique index to prevent duplicates.
 * Optional foreign key to customers table.
 */
@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["custom_name"], unique = true),
        Index(value = ["customer_id"]),
        Index(value = ["remaining_amount"]),
        Index(value = ["timestamp"]),
        Index(value = ["payment_status"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["customer_id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "custom_name")
    val customName: String,

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "image_path")
    val imagePath: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "payment_status")
    val paymentStatus: String = "Unpaid",

    @ColumnInfo(name = "customer_id")
    val customerId: Long? = null,

    @ColumnInfo(name = "reminder_datetime")
    val reminderDatetime: Long? = null,

    @ColumnInfo(name = "paid_timestamp")
    val paidTimestamp: Long? = null,

    @ColumnInfo(name = "sync_id")
    val syncId: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "shop_id")
    val shopId: String? = null,

    @ColumnInfo(name = "created_by")
    val createdBy: String? = null,

    @ColumnInfo(name = "vendor_name")
    val vendorName: String? = null,

    @ColumnInfo(name = "total_amount")
    val totalAmount: Double? = null,

    @ColumnInfo(name = "tax_amount")
    val taxAmount: Double? = null,

    @ColumnInfo(name = "invoice_date")
    val invoiceDate: String? = null,

    @ColumnInfo(name = "invoice_number")
    val invoiceNumber: String? = null,

    @ColumnInfo(name = "raw_ocr_text")
    val rawOcrText: String? = null,

    @ColumnInfo(name = "ocr_confidence")
    val ocrConfidence: Float? = null,

    @ColumnInfo(name = "ocr_image_path")
    val ocrImagePath: String? = null,

    @ColumnInfo(name = "ocr_text_file_path")
    val ocrTextFilePath: String? = null,

    @ColumnInfo(name = "optimized_image_path")
    val optimizedImagePath: String? = null,

    @ColumnInfo(name = "is_smart_processed")
    val isSmartProcessed: Boolean = false,

    @ColumnInfo(name = "smart_ocr_json")
    val smartOcrJson: String? = null,

    @ColumnInfo(name = "late_risk_score")
    val lateRiskScore: Float? = null,

    // --- Payment tracking fields ---
    // remainingAmount is ALWAYS derived: totalAmount - paidAmount
    // It must never be manually edited outside controlled ViewModel logic.
    @ColumnInfo(name = "paid_amount")
    val paidAmount: Double = 0.0,

    @ColumnInfo(name = "remaining_amount")
    val remainingAmount: Double = 0.0,

    @ColumnInfo(name = "last_payment_date")
    val lastPaymentDate: Long? = null,

    @ColumnInfo(name = "payment_history_json")
    val paymentHistoryJson: String? = null
)

/**
 * Data class representing an isolated fraction of a payment transaction.
 * Serialized dynamically into the payment_history_json string array per bill.
 */
data class PaymentRecord(
    val amount: Double,
    val timestamp: Long
)
