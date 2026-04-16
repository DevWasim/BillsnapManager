package com.billsnap.manager.ui.preview

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentPreviewBinding
import com.billsnap.manager.ocr.OcrProcessor
import com.billsnap.manager.ocr.OcrResultParser
import com.billsnap.manager.ocr.ParsedBillData
import com.bumptech.glide.Glide
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Preview screen showing the captured image.
 * User can Retake (go back to camera) or Save (proceed to form).
 * Passes customerId and ocrText through to SaveForm if present.
 */
class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!
    private var imagePath: String? = null
    private var isOcrMode = false
    private var isBoxesVisible = false
    private var parsedData: ParsedBillData? = null
    private var tempOcrImagePath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imagePath = arguments?.getString("imagePath")
        isOcrMode = arguments?.getBoolean("isOcrMode", false) ?: false

        imagePath?.let { path ->
            Glide.with(this).load(File(path)).into(binding.ivPreview)
        }

        binding.btnRetake.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnToggleBoxes.setOnClickListener {
            isBoxesVisible = !isBoxesVisible
            binding.ivOcrBoxes.visibility = if (isBoxesVisible) View.VISIBLE else View.GONE
            binding.btnToggleBoxes.text = if (isBoxesVisible) "Hide Text Boxes" else "Show Text Boxes"
        }

        binding.btnSave.setOnClickListener {
            imagePath?.let { path ->
                val bundle = bundleOf("imagePath" to path)
                // Pass customerId through if present (from profile camera flow)
                val customerId = arguments?.getLong("customerId", 0L) ?: 0L
                if (customerId > 0) {
                    bundle.putLong("customerId", customerId)
                }
                
                // Pass Extracted OCR Data and Overlay Image Path
                parsedData?.let {
                    bundle.putString("ocrVendorName", it.vendorName)
                    bundle.putString("ocrRawText", it.rawText)
                    bundle.putFloat("ocrConfidence", it.confidence)
                }
                
                tempOcrImagePath?.let { ocrPath ->
                    bundle.putString("ocrImagePath", ocrPath)
                }
                
                findNavController().navigate(R.id.action_preview_to_save, bundle)
            }
        }

        if (isOcrMode && imagePath != null) {
            runOcrExtraction(imagePath!!)
        }
    }

    private fun runOcrExtraction(path: String) {
        binding.layoutOcrLoading.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false
        binding.btnRetake.isEnabled = false

        val prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val highAccuracy = prefs.getBoolean("ocr_high_accuracy", false)

        lifecycleScope.launch {
            try {
                val result = OcrProcessor.processImage(requireContext(), File(path), highAccuracy)
                if (result != null) {
                    parsedData = OcrResultParser.parse(result)
                    
                    // Save the image with bounding boxes to a temporary file
                    val imgWithBox = result.imgWithBox
                    if (imgWithBox != null) {
                        binding.ivOcrBoxes.setImageBitmap(imgWithBox)
                        
                        val tempFile = File(requireContext().cacheDir, "ocr_preview_${UUID.randomUUID()}.jpg")
                        withContext(Dispatchers.IO) {
                            FileOutputStream(tempFile).use { out ->
                                imgWithBox.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                            }
                        }
                        tempOcrImagePath = tempFile.absolutePath
                    }

                    binding.btnToggleBoxes.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), getString(R.string.ocr_extraction_complete), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.ocr_failed_manual), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PreviewFragment", "OCR process error", e)
                Toast.makeText(requireContext(), getString(R.string.ocr_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                binding.layoutOcrLoading.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnRetake.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
