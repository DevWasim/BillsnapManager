package com.billsnap.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.equationl.paddleocr4android.CpuPowerMode
import com.equationl.paddleocr4android.OCR
import com.equationl.paddleocr4android.OcrConfig
import com.equationl.paddleocr4android.bean.OcrResult
import com.equationl.paddleocr4android.callback.OcrInitCallback
import com.equationl.paddleocr4android.callback.OcrRunCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PaddleOcrManager {
    private const val TAG = "PaddleOcrManager"
    var isInitialized = false
        private set

    private var ocr: OCR? = null

    /**
     * Initializes the PaddleOCR predictor.
     */
    suspend fun initialize(context: Context, useHighAccuracy: Boolean = false): Boolean = suspendCancellableCoroutine { continuation ->
        if (isInitialized) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        if (ocr == null) {
            ocr = OCR(context)
        }

        val config = OcrConfig().apply {
            modelPath = "models/ch_PP-OCRv4"
            clsModelFilename = "cls.nb"
            detModelFilename = "det.nb"
            recModelFilename = "rec.nb"
            labelPath = "models/ch_PP-OCRv4/ppocr_keys_v1.txt"

            isRunDet = true
            isRunCls = true
            isRunRec = true
            isDrwwTextPositionBox = true

            cpuPowerMode = if (useHighAccuracy) {
                CpuPowerMode.LITE_POWER_FULL
            } else {
                CpuPowerMode.LITE_POWER_HIGH
            }
        }

        ocr?.initModel(config, object : OcrInitCallback {
            override fun onSuccess() {
                isInitialized = true
                Log.i(TAG, "PaddleOCR Initialized Successfully")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onFail(e: Throwable) {
                Log.e(TAG, "Failed to initialize PaddleOCR", e)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    /**
     * Runs OCR on the given bitmap.
     * Returns an OcrResult which contains simpleText and structured bounding boxes.
     */
    suspend fun runOcr(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { continuation ->
        if (!isInitialized || ocr == null) {
            continuation.resumeWithException(IllegalStateException("PaddleOCR is not initialized"))
            return@suspendCancellableCoroutine
        }

        ocr?.run(bitmap, object : OcrRunCallback {
            override fun onSuccess(result: OcrResult) {
                if (continuation.isActive) continuation.resume(result)
            }

            override fun onFail(e: Throwable) {
                Log.e(TAG, "OCR Run Failed", e)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    fun release() {
        ocr?.releaseModel()
        isInitialized = false
    }
}
