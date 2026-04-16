package com.billsnap.manager.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an OCR user correction.
 * Used to map misread phrases to corrected phrases to apply automatically in the future.
 */
@Entity(
    tableName = "ocr_corrections",
    indices = [
        Index(value = ["original_text"], unique = true)
    ]
)
data class OcrCorrectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "original_text")
    val originalText: String,

    @ColumnInfo(name = "corrected_text")
    val correctedText: String,

    @ColumnInfo(name = "sync_id")
    val syncId: String? = null,

    @ColumnInfo(name = "shop_id")
    val shopId: String? = null,

    @ColumnInfo(name = "created_by")
    val createdBy: String? = null,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
