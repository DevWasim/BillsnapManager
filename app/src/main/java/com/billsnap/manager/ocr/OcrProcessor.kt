package com.billsnap.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.equationl.paddleocr4android.bean.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OcrProcessor {
    
    // Max dimension for OCR processing to balance speed and accuracy
    private const val MAX_DIMENSION = 1600

    /**
     * Processes an image file by scaling it and running PaddleOCR.
     */
    suspend fun processImage(context: Context, imageFile: File, useHighAccuracy: Boolean = false): OcrResult? = withContext(Dispatchers.Default) {
        val bitmap = getOptimizedBitmap(imageFile) ?: return@withContext null
        
        try {
            if (!PaddleOcrManager.isInitialized) {
                PaddleOcrManager.initialize(context, useHighAccuracy)
            }
            return@withContext PaddleOcrManager.runOcr(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle() // Prevent Memory Leaks
            }
        }
    }

    /**
     * Decodes, rotates based on EXIF, and scales down the bitmap.
     */
    private fun getOptimizedBitmap(file: File): Bitmap? {
        if (!file.exists()) return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        // Calculate inSampleSize
        var inSampleSize = 1
        var outWidth = options.outWidth
        var outHeight = options.outHeight

        while (outWidth / 2 >= MAX_DIMENSION || outHeight / 2 >= MAX_DIMENSION) {
            outWidth /= 2
            outHeight /= 2
            inSampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize

        val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        // Fix Exif Rotation
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (degrees == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        val rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        
        if (rotatedBitmap != rawBitmap && !rawBitmap.isRecycled) {
            rawBitmap.recycle()
        }
        
        return rotatedBitmap
    }
}
