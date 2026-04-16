package com.billsnap.manager.ui.camera

import com.billsnap.manager.ocr.models.StructuredOcrResult

/**
 * Represents the state of the visual scanner pipeline.
 */
sealed class VisualScannerState {
    data object Idle : VisualScannerState()
    data object Loading : VisualScannerState()
    data class Scanning(val boxes: List<com.billsnap.manager.ocr.models.BoundingBoxResult>) : VisualScannerState()
    data object ProcessingHighRes : VisualScannerState()
    data class Success(val result: StructuredOcrResult, val imagePath: String) : VisualScannerState()
    data class Error(val message: String) : VisualScannerState()
}
