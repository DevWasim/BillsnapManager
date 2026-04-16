package com.billsnap.manager.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.billsnap.manager.ui.camera.VisualScannerViewModel
import com.billsnap.manager.ui.camera.VisualScannerState
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File

/**
 * Camera screen with CameraX integration and optional OCR mode.
 * When OCR is ON, the captured image path and isOcrMode flag are passed to the Preview screen
 * where PaddleOCR will handle extraction.
 */
class CameraFragment : Fragment() {

    companion object {
        private const val TAG = "CameraFragment"
    }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    
    private var isBackCamera = true
    private var isOcrMode = false
    private var advancedOptimizationEnabled = false // TODO: Read from settings

    private val viewModel: VisualScannerViewModel by viewModels()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnCapture.setOnClickListener { capturePhoto() }
        binding.btnFlipCamera.setOnClickListener {
            isBackCamera = !isBackCamera
            startCamera()
        }

        // OCR toggle
        binding.btnOcrToggle.setOnClickListener {
            isOcrMode = !isOcrMode
            updateOcrUI()
        }
        updateOcrUI()

        val prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        advancedOptimizationEnabled = prefs.getBoolean("advanced_optimization", true)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        observeState()
    }
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is VisualScannerState.Idle -> {
                            binding.overlayView.clear()
                        }
                        is VisualScannerState.Loading -> {}
                        is VisualScannerState.Scanning -> {
                            if (binding.previewView.display != null) {
                                binding.overlayView.updateResults(
                                    boxes = state.boxes,
                                    sourceWidth = 480,
                                    sourceHeight = 640
                                )
                            }
                        }
                        is VisualScannerState.ProcessingHighRes -> {
                            // Can show an overlay spinner if desired
                        }
                        is VisualScannerState.Success -> {
                            // Old behavior: Navigate directly instead of showing bottom sheet
                            navigateToPreview(state.imagePath, isOcrMode)
                            viewModel.resetState()
                        }
                        is VisualScannerState.Error -> {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }

    private fun updateOcrUI() {
        if (isOcrMode) {
            binding.btnOcrToggle.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.camera_button_bg)
            )
            binding.btnOcrToggle.alpha = 1.0f
        } else {
            binding.btnOcrToggle.setBackgroundColor(0)
            binding.btnOcrToggle.alpha = 0.6f
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                imageAnalysis = ImageAnalysis.Builder()
                    // Optimize for speed and memory (e.g. 480x640)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (isOcrMode) {
                                viewModel.analyzeFrame(imageProxy)
                            } else {
                                imageProxy.close()
                                binding.overlayView.clear()
                            }
                        }
                    }

                val cameraSelector = if (isBackCamera)
                    CameraSelector.DEFAULT_BACK_CAMERA
                else
                    CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera startup failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        binding.btnCapture.isEnabled = false

        val photoFile = File(requireContext().cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.btnCapture.isEnabled = true
                    
                    if (isOcrMode) {
                        // Pass to the deep pipeline which will now navigate on Success
                        viewModel.processHighResCapture(photoFile, advancedOptimizationEnabled)
                    } else {
                        navigateToPreview(photoFile.absolutePath, isOcrMode)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    binding.btnCapture.isEnabled = true
                    Log.e(TAG, "Capture failed", e)
                    Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun navigateToPreview(imagePath: String, ocrModeEnabled: Boolean) {
        val bundle = bundleOf(
            "imagePath" to imagePath,
            "isOcrMode" to ocrModeEnabled
        )
        val customerId = arguments?.getLong("customerId", 0L) ?: 0L
        if (customerId > 0) bundle.putLong("customerId", customerId)

        findNavController().navigate(R.id.action_camera_to_preview, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }
}
