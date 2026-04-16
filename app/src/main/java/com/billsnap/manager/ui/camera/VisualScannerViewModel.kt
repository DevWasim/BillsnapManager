package com.billsnap.manager.ui.camera

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.ocr.BillImageOptimizationUseCase
import com.billsnap.manager.ocr.OcrCorrectionLearningUseCase
import com.billsnap.manager.ocr.StructuredOcrPipelineUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VisualScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<VisualScannerState>(VisualScannerState.Idle)
    val uiState: StateFlow<VisualScannerState> = _uiState.asStateFlow()

    private val db = AppDatabase.getInstance(application)
    private val learningUseCase = OcrCorrectionLearningUseCase(db)
    private val pipelineUseCase = StructuredOcrPipelineUseCase(application, learningUseCase)
    private val optimizationUseCase = BillImageOptimizationUseCase(application)

    /**
     * Called continuously by CameraX ImageAnalysis.
     * Extracts bounding boxes and emits partial Scanning state.
     */
    fun analyzeFrame(imageProxy: ImageProxy) {
        // Prevent concurrent frame processing if we are busy handling a real capture
        if (_uiState.value is VisualScannerState.ProcessingHighRes || _uiState.value is VisualScannerState.Success) {
            imageProxy.close()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val boxes = pipelineUseCase.analyzeFrame(imageProxy)
            if (boxes.isNotEmpty()) {
                _uiState.value = VisualScannerState.Scanning(boxes)
            }
        }
    }

    /**
     * Called when the user hits 'Capture'.
     * Runs Image Optimization (WebP/Contrast) then Deep OCR merge.
     */
    fun processHighResCapture(imageFile: File, enableAdvancedOptimization: Boolean) {
        _uiState.value = VisualScannerState.ProcessingHighRes

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Optimize Image
                val optimizedFile = optimizationUseCase.optimizeImage(imageFile, enableAdvancedOptimization)
                
                // 2. Decode for Deep OCR (Guaranteed safe memory from optimization run)
                val bitmap = BitmapFactory.decodeFile(optimizedFile.absolutePath)
                if (bitmap == null) {
                    _uiState.value = VisualScannerState.Error("Failed to decode captured image.")
                    return@launch
                }

                // 3. Process Deep OCR
                val result = pipelineUseCase.processHighResCapture(bitmap)
                bitmap.recycle()

                // 4. Emit Success
                withContext(Dispatchers.Main) {
                    _uiState.value = VisualScannerState.Success(result, optimizedFile.absolutePath)
                }

            } catch (e: Exception) {
                Log.e("ScannerViewModel", "Capture processing failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = VisualScannerState.Error(e.message ?: "Unknown error occurred.")
                }
            }
        }
    }
    
    fun applyCorrection(original: String, corrected: String, shopId: String?, createdBy: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            learningUseCase.learnCorrection(original, corrected, shopId, createdBy)
        }
    }

    fun resetState() {
        _uiState.value = VisualScannerState.Idle
    }
}
