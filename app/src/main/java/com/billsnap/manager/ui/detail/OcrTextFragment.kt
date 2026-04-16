package com.billsnap.manager.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.databinding.FragmentOcrTextBinding
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Full-screen OCR text viewer.
 * Renders the raw OCR plain-text captured from a bill image in a monospace
 * pre-formatted style, preserving the original spacing and line layout.
 *
 * The text is read from [rawOcrText] (passed as a nav argument by DetailFragment).
 * If the field is empty, the file at [ocrTextFilePath] is read instead.
 */
class OcrTextFragment : Fragment() {

    private var _binding: FragmentOcrTextBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return DetailViewModel(app.billRepository, app.database) as T
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrTextBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { findNavController().popBackStack() }

        val billId = arguments?.getLong("billId") ?: -1L

        if (billId > 0) {
            // Load from ViewModel (reads bill from DB, uses rawOcrText or falls back to file)
            viewModel.loadBill(billId)
            viewModel.bill.observe(viewLifecycleOwner) { bill ->
                bill ?: return@observe

                val displayText = when {
                    !bill.rawOcrText.isNullOrBlank() -> bill.rawOcrText
                    !bill.ocrTextFilePath.isNullOrBlank() -> readOcrFile(bill.ocrTextFilePath)
                    else -> "No OCR text available for this bill."
                }
                binding.tvOcrText.text = displayText
            }
        } else {
            // Fallback: text passed directly as argument (legacy)
            val directText = arguments?.getString("ocrText")
            binding.tvOcrText.text = directText ?: "No OCR text available."
        }

        binding.tvOcrText.setOnLongClickListener {
            showAddCorrectionDialog()
            true
        }
    }

    private fun showAddCorrectionDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val originalInput = EditText(requireContext()).apply {
            hint = "Original Text (e.g., Pa1d)"
        }
        val correctedInput = EditText(requireContext()).apply {
            hint = "Corrected Text (e.g., Paid)"
        }
        layout.addView(originalInput)
        layout.addView(correctedInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Smart Correction")
            .setMessage("Teach the processor to auto-correct a mistake in future scans.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val original = originalInput.text.toString().trim()
                val corrected = correctedInput.text.toString().trim()
                if (original.isNotEmpty() && corrected.isNotEmpty()) {
                    viewModel.saveOcrCorrection(original, corrected)
                    Toast.makeText(requireContext(), "Correction saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Reads OCR text from the stored file path. */
    private fun readOcrFile(path: String): String {
        return try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            "Could not read OCR file: ${e.message}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
