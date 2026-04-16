package com.billsnap.manager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OcrCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: OcrCorrectionEntity): Long

    @Query("SELECT * FROM ocr_corrections")
    suspend fun getAllCorrectionsSync(): List<OcrCorrectionEntity>

    @Query("SELECT corrected_text FROM ocr_corrections WHERE original_text = :originalText LIMIT 1")
    suspend fun getCorrectionForText(originalText: String): String?
    
    @Query("SELECT * FROM ocr_corrections WHERE sync_id = :syncId LIMIT 1")
    suspend fun getCorrectionBySyncId(syncId: String): OcrCorrectionEntity?

    @Query("UPDATE ocr_corrections SET sync_id = :syncId WHERE id = :id AND sync_id IS NULL")
    suspend fun updateSyncId(id: Long, syncId: String)
}
