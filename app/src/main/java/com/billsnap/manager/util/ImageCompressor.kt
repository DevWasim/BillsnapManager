package com.billsnap.manager.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Compresses images to JPEG format under a target file size.
 * Uses downsampling for large images + binary-search quality reduction.
 */
object ImageCompressor {

    private const val MAX_FILE_SIZE_BYTES = 500 * 1024  // 500 KB
    private const val MAX_DIMENSION = 1920              // max px on longest edge
    private const val MIN_QUALITY = 10

    /**
     * Compress [sourceFile] to a JPEG under 500KB.
     * Returns a new compressed file in [cacheDir].
     * If the source is already under 500KB, it is still re-encoded to normalize format.
     */
    fun compress(sourceFile: File, cacheDir: File): File {
        // Step 1: Determine sample size for large images
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, options)

        val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
            ?: throw IllegalArgumentException("Cannot decode image: ${sourceFile.absolutePath}")

        // Step 2: Scale down if still larger than MAX_DIMENSION
        val scaledBitmap = scaleBitmap(bitmap, MAX_DIMENSION)

        // Step 3: Binary search for quality that fits under MAX_FILE_SIZE_BYTES
        var quality = 90
        var bytes: ByteArray

        do {
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > MAX_FILE_SIZE_BYTES && quality >= MIN_QUALITY)

        // Recycle bitmaps
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        bitmap.recycle()

        // Step 4: Write to output file
        val compressedDir = File(cacheDir, "compressed")
        if (!compressedDir.exists()) compressedDir.mkdirs()
        val outputFile = File(compressedDir, "comp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { it.write(bytes) }

        return outputFile
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val longestSide = maxOf(width, height)
        while (longestSide / inSampleSize > maxDim * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
