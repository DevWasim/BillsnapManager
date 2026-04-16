package com.billsnap.manager.ui.save

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.BillRepository
import com.billsnap.manager.ocr.SmartOcrProcessor
import com.billsnap.manager.ocr.SmartOcrResult
import com.billsnap.manager.worker.ReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ViewModel for saving a new bill record.
 * Handles validation, duplicate checking, image compression & storage,
 * DB insertion, gallery copy, and WorkManager reminder scheduling.
 */
class SaveFormViewModel(
    private val repository: BillRepository,
    private val db: AppDatabase
) : ViewModel() {

    sealed class SaveResult {
        object Success : SaveResult()
        object DuplicateName : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    private val _isSaving = MutableLiveData(false)
    val isSaving: LiveData<Boolean> = _isSaving
    
    private val _smartOcrResult = MutableLiveData<SmartOcrResult?>()
    val smartOcrResult: LiveData<SmartOcrResult?> = _smartOcrResult

    fun processOcrText(rawText: String?) {
        if (rawText.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                _smartOcrResult.value = SmartOcrProcessor.process(rawText, db)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun saveBill(
        context: Context,
        name: String,
        notes: String,
        tempImagePath: String,
        paymentStatus: String,
        customerId: Long? = null,
        reminderDatetime: Long? = null,
        vendorName: String? = null,
        rawOcrText: String? = null,
        ocrConfidence: Float? = null,
        ocrImagePath: String? = null,
        isSmartProcessed: Boolean = false,
        smartOcrJson: String? = null,
        lateRiskScore: Float? = null,
        totalAmount: Double? = null,
        paidAmount: Double = 0.0,
        remainingAmount: Double = 0.0
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("createBills")) {
                    _saveResult.value = SaveResult.Error("You do not have permission to create bills.")
                    return@launch
                }

                if (repository.isNameExists(name)) {
                    _saveResult.value = SaveResult.DuplicateName
                    return@launch
                }

                // Copy & compress image to internal storage
                val destFile = withContext(Dispatchers.IO) {
                    compressAndSave(context, tempImagePath)
                }

                // Save to public gallery
                withContext(Dispatchers.IO) {
                    saveToPublicGallery(context, destFile, name)
                }

                // Clean up temp
                File(tempImagePath).delete()

                // Persist OCR Image Overlay if present
                var finalOcrImagePath: String? = null
                if (ocrImagePath != null) {
                    val ocrDestFile = withContext(Dispatchers.IO) {
                        compressAndSave(context, ocrImagePath)
                    }
                    finalOcrImagePath = ocrDestFile.absolutePath
                    File(ocrImagePath).delete() // Clean up temp file
                }

                // Insert DB record
                val paidTimestamp = if (paymentStatus == "Paid") System.currentTimeMillis() else null
                val bill = BillEntity(
                    customName = name,
                    notes = notes,
                    imagePath = destFile.absolutePath,
                    paymentStatus = paymentStatus,
                    customerId = customerId,
                    reminderDatetime = reminderDatetime,
                    paidTimestamp = paidTimestamp,
                    vendorName = vendorName,
                    rawOcrText = rawOcrText,
                    ocrConfidence = ocrConfidence,
                    ocrImagePath = finalOcrImagePath,
                    isSmartProcessed = isSmartProcessed,
                    smartOcrJson = smartOcrJson,
                    lateRiskScore = lateRiskScore,
                    totalAmount = totalAmount,
                    paidAmount = paidAmount,
                    remainingAmount = remainingAmount,
                    lastPaymentDate = if (paidAmount > 0) System.currentTimeMillis() else null
                )
                val billId = repository.insert(bill)

                // Write OCR text to a file (one file per bill) if OCR was performed
                if (!rawOcrText.isNullOrBlank()) {
                    val ocrTextFilePath = withContext(Dispatchers.IO) {
                        saveOcrTextFile(context, billId, rawOcrText)
                    }
                    if (ocrTextFilePath != null) {
                        repository.updateOcrTextFilePath(billId, ocrTextFilePath)
                    }
                }

                // Schedule reminder if set and not already paid
                if (reminderDatetime != null && paymentStatus != "Paid") {
                    scheduleReminder(context, billId, name, reminderDatetime)
                }

                com.billsnap.manager.security.ActivityLogger.logAction("Bill Created", "Created bill: $name")
                _saveResult.value = SaveResult.Success
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Unknown error")
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Compress image to 80% JPEG quality. Resizes if width > 1920.
     */
    private fun compressAndSave(context: Context, sourcePath: String): File {
        val imageDir = File(context.filesDir, "bill_images")
        if (!imageDir.exists()) imageDir.mkdirs()

        val uniqueFileName = "${UUID.randomUUID()}.jpg"
        val destFile = File(imageDir, uniqueFileName)

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, options)

        // Calculate sample size for large images
        val maxWidth = 1920
        var sampleSize = 1
        if (options.outWidth > maxWidth) {
            sampleSize = options.outWidth / maxWidth
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(sourcePath, decodeOptions)

        FileOutputStream(destFile).use { out ->
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap?.recycle()

        return destFile
    }

    /**
     * Saves the raw OCR text to a plain-text file inside internal storage.
     * Location: <filesDir>/ocr_texts/<billId>.txt
     * Returns the absolute path on success, or null on failure.
     */
    private fun saveOcrTextFile(context: Context, billId: Long, text: String): String? {
        return try {
            val dir = File(context.filesDir, "ocr_texts")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$billId.txt")
            FileWriter(file).use { it.write(text) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Schedule a WorkManager one-time reminder.
     */
    private fun scheduleReminder(context: Context, billId: Long, billName: String, triggerAt: Long) {
        val delay = triggerAt - System.currentTimeMillis()
        if (delay <= 0) return

        val data = Data.Builder()
            .putString(ReminderWorker.KEY_BILL_NAME, billName)
            .putLong(ReminderWorker.KEY_BILL_ID, billId)
            .build()

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("reminder_$billId")
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    /**
     * Saves the image to the public gallery under Pictures/BillSnap.
     */
    private fun saveToPublicGallery(context: Context, sourceFile: File, displayName: String) {
        val fileName = "${displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}_${System.currentTimeMillis()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BillSnap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val billSnapDir = File(picturesDir, "BillSnap")
            if (!billSnapDir.exists()) billSnapDir.mkdirs()

            val destFile = File(billSnapDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        }
    }
}
