package com.billsnap.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.billsnap.manager.ocr.models.BlockType
import com.billsnap.manager.ocr.models.BoundingBoxResult
import com.billsnap.manager.ocr.models.StructuredOcrResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pipeline for processing images through ML Kit and extracting structured data.
 * Used for both real-time ImageAnalysis (ML Kit only for speed) and final High-Res capture.
 */
class StructuredOcrPipelineUseCase(private val context: Context, private val ocrCorrectionLearningUseCase: OcrCorrectionLearningUseCase) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Regex Patterns for Classification
    private val amountRegex = Regex("""\$?\s?(\d+[.,]\d{2})(?!\d)""")
    private val dateRegex = Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\b""")
    private val phoneRegex = Regex("""\b\+?(\d{10,14})\b""")

    /**
     * Real-time analysis of a CameraX ImageProxy frame.
     * Extracts bounding boxes mapped perfectly to the source image dimensions.
     */
    suspend fun analyzeFrame(imageProxy: ImageProxy): List<BoundingBoxResult> = withContext(Dispatchers.Default) {
        val mediaImage = imageProxy.image ?: return@withContext emptyList()
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        try {
            val result = recognizer.process(inputImage).await()
            val boundingBoxes = mutableListOf<BoundingBoxResult>()
            
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val text = line.text
                    
                    val type = classifyText(text)
                    // ML Kit doesn't expose confidence directly in the standard text API. We mock it or calculate heuristically.
                    val confidence = if (type != BlockType.GENERAL_TEXT) 0.95f else 0.8f 
                    
                    boundingBoxes.add(
                        BoundingBoxResult(
                            text = text,
                            confidence = confidence,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            blockType = type
                        )
                    )
                }
            }
            return@withContext boundingBoxes
        } catch (e: Exception) {
            Log.e("StructuredOcrPipeline", "Frame analysis failed", e)
            return@withContext emptyList()
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Deep analysis of a captured high-res bitmap.
     * Merges ML Kit block detection with token correction and extracts specific fields.
     */
    suspend fun processHighResCapture(bitmap: Bitmap): StructuredOcrResult = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        try {
            val result = recognizer.process(inputImage).await()
            
            val boxes = mutableListOf<BoundingBoxResult>()
            val rawTextBuilder = java.lang.StringBuilder()
            
            var extractedAmount: Double? = null
            var extractedTaxAmount: Double? = null
            var extractedDate: String? = null
            var vendorName: String? = null // Often the first large block
            
            for ((index, block) in result.textBlocks.withIndex()) {
                rawTextBuilder.append(block.text).append("\n")
                
                // Heuristic: First block is often the vendor name
                if (index == 0 && block.lines.isNotEmpty()) {
                    vendorName = block.lines[0].text
                }
                
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    
                    // Apply learning corrections
                    val correctedText = ocrCorrectionLearningUseCase.applyCorrections(line.text)
                    val type = classifyText(correctedText)
                    
                    if (type == BlockType.TOTAL && extractedAmount == null) {
                        amountRegex.find(correctedText)?.let { match ->
                            try { extractedAmount = match.groupValues[1].replace(",", ".").toDouble() } catch (_: Exception) {}
                        }
                    } else if (type == BlockType.TAX && extractedTaxAmount == null) {
                         amountRegex.find(correctedText)?.let { match ->
                            try { extractedTaxAmount = match.groupValues[1].replace(",", ".").toDouble() } catch (_: Exception) {}
                        }
                    } else if (type == BlockType.DATE && extractedDate == null) {
                        dateRegex.find(correctedText)?.let { match ->
                            extractedDate = match.groupValues[1]
                        }
                    }
                    
                    boxes.add(
                        BoundingBoxResult(
                            text = correctedText,
                            confidence = 0.9f, 
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            blockType = type
                        )
                    )
                }
            }
            
            return@withContext StructuredOcrResult(
                boundingBoxes = boxes,
                vendorName = vendorName,
                totalAmount = extractedAmount,
                taxAmount = extractedTaxAmount,
                invoiceDate = extractedDate,
                invoiceNumber = null,
                overallConfidence = 0.9f,
                matchedCustomerId = null // Handled later by SmartOcrProcessor if needed
            )
            
        } catch (e: Exception) {
            Log.e("StructuredOcrPipeline", "High-res capture analysis failed", e)
            throw e
        }
    }

    private fun classifyText(text: String): BlockType {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("total") && amountRegex.containsMatchIn(text) -> BlockType.TOTAL
            (lowerText.contains("tax") || lowerText.contains("vat")) && amountRegex.containsMatchIn(text) -> BlockType.TAX
            dateRegex.containsMatchIn(text) -> BlockType.DATE
            lowerText.contains("invoice") || lowerText.contains("receipt no") -> BlockType.INVOICE_NUMBER
            else -> BlockType.GENERAL_TEXT
        }
    }
}
