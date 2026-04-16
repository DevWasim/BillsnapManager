package com.billsnap.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BillImageOptimizationUseCase(private val context: Context) {

    /**
     * Optimizes the captured bill image.
     * @param originalFile The raw captured image file.
     * @param enableAdvanced If true, applies WebP compression, contrast, and brightness adjustments.
     * @return The optimized File if advanced was enabled, otherwise returns the original file (potentially with EXIF rotation fixed).
     */
    suspend fun optimizeImage(originalFile: File, enableAdvanced: Boolean): File = withContext(Dispatchers.IO) {
        if (!originalFile.exists()) return@withContext originalFile

        var bitmap = decodeAndCorrectExif(originalFile)
            ?: return@withContext originalFile // Return original if decoding fails

        var optimizedFile = originalFile

        if (enableAdvanced) {
            // Apply lightweight contrast enhancement
            val contrastedBitmap = enhanceContrast(bitmap)
            if (contrastedBitmap != bitmap) {
                bitmap.recycle() // Release intermediate bitmap
                bitmap = contrastedBitmap
            }

            // Save as WebP
            val outputDir = File(context.cacheDir, "optimized_bills").apply { mkdirs() }
            optimizedFile = File(outputDir, "opt_${UUID.randomUUID()}.webp")
            FileOutputStream(optimizedFile).use { out ->
                // WebP Lossy, quality 90 for excellent size/quality ratio
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
            }
        } else {
             // Even if advanced is disabled, we still want to save the EXIF-corrected version
             // so the ML pipeline doesn't have to deal with rotated bitmaps.
             val outputDir = File(context.cacheDir, "optimized_bills").apply { mkdirs() }
             optimizedFile = File(outputDir, "opt_${UUID.randomUUID()}.jpg")
             FileOutputStream(optimizedFile).use { out ->
                 bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
             }
        }

        if (!bitmap.isRecycled) {
            bitmap.recycle() // Prevent Memory Leaks
        }

        return@withContext optimizedFile
    }

    /**
     * Decodes the image using inSampleSize to prevent OOM, and fixes EXIF rotation.
     */
    private fun decodeAndCorrectExif(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        // Calculate inSampleSize (Max 1920x1080 for OCR)
        val maxDimension = 1920
        var inSampleSize = 1
        var outWidth = options.outWidth
        var outHeight = options.outHeight

        while (outWidth / 2 >= maxDimension || outHeight / 2 >= maxDimension) {
            outWidth /= 2
            outHeight /= 2
            inSampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        // Recommend ARGB_8888 for better contrast operations later
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

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

    /**
     * Applies a ColorMatrix to enhance contrast by 20% and brightness slightly.
     * This is an efficient, non-blocking hardware-accelerated equivalent to basic histogram equalization.
     */
    private fun enhanceContrast(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        
        val paint = Paint()
        val contrast = 1.2f // 20% increase
        val brightness = 10f // Slight bump
        
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        
        return dest
    }
}
