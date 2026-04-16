package com.billsnap.manager.ui.save

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.data.CustomerEntity
import com.billsnap.manager.databinding.FragmentSaveFormBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.text.TextWatcher
import android.text.Editable

/**
 * Form screen for saving a bill with name, notes, customer, payment status, and optional reminder.
 */
class SaveFormFragment : Fragment() {

    private var _binding: FragmentSaveFormBinding? = null
    private val binding get() = _binding!!
    private var imagePath: String? = null
    private var selectedCustomerId: Long? = null
    private var reminderDatetime: Long? = null
    private val calendar = Calendar.getInstance()

    // Internal OCR metadata
    private var rawOcrText: String? = null
    private var ocrConfidence: Float? = null
    private var ocrImagePath: String? = null

    private var customerList: List<CustomerEntity> = emptyList()

    private val viewModel: SaveFormViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return SaveFormViewModel(app.billRepository, app.database) as T
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSaveFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imagePath = arguments?.getString("imagePath")

        // Check if customerId was passed (from profile detail camera)
        val passedCustomerId = arguments?.getLong("customerId", 0L) ?: 0L

        // Show image preview
        imagePath?.let { path ->
            Glide.with(this).load(File(path)).centerCrop().into(binding.ivPreview)
        }

        // Retrieve background OCR metadata
        rawOcrText = arguments?.getString("ocrRawText")
        ocrConfidence = arguments?.getFloat("ocrConfidence", 0f)
        ocrImagePath = arguments?.getString("ocrImagePath")

        // Legacy pre-fill fallback
        val legacyOcrText = arguments?.getString("ocrText")
        if (!legacyOcrText.isNullOrEmpty() && binding.etName.text.isNullOrEmpty()) {
            binding.etName.setText(legacyOcrText)
        }

        // Default status: Unpaid
        binding.toggleStatus.check(R.id.btnUnpaid)

        // Setup customer dropdown
        setupCustomerDropdown(passedCustomerId)

        // Reminder toggle
        binding.switchReminder.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutReminder.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) reminderDatetime = null
        }

        // Date picker
        binding.btnPickDate.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, day)
                    updateReminderDatetime()
                    binding.btnPickDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        .format(calendar.time)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Time picker
        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    updateReminderDatetime()
                    binding.btnPickTime.text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(calendar.time)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                false
            ).show()
        }

        binding.btnSave.setOnClickListener { validateAndSave() }

        // --- Real-time amount calculation ---
        val amountWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val total = binding.etTotalAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
                val paid = binding.etPaidAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
                binding.tilTotalAmount.error = null
                binding.tilPaidAmount.error = null
                if (total < 0) {
                    binding.tilTotalAmount.error = "Cannot be negative"
                } else if (paid < 0) {
                    binding.tilPaidAmount.error = "Cannot be negative"
                } else if (paid > total && total > 0) {
                    binding.tilPaidAmount.error = "Cannot exceed total amount"
                }
                val remaining = if (total > 0) maxOf(total - paid, 0.0) else 0.0
                binding.tilPaidAmount.helperText = "Remaining: %.2f".format(remaining)
                // Color the helper text based on remaining
                if (remaining > 0 && total > 0) {
                    binding.tilPaidAmount.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFFF44336.toInt()))
                } else {
                    binding.tilPaidAmount.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFF69F0AE.toInt()))
                }
                // Auto-derive payment status toggle
                if (total > 0) {
                    if (paid >= total) {
                        binding.toggleStatus.check(R.id.btnPaid)
                    } else {
                        binding.toggleStatus.check(R.id.btnUnpaid)
                    }
                }
            }
        }
        binding.etTotalAmount.addTextChangedListener(amountWatcher)
        binding.etPaidAmount.addTextChangedListener(amountWatcher)

        // Listen for new customer created via AddCustomerFragment
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<Long>("newCustomerId")?.observe(viewLifecycleOwner) { newId ->
                if (newId > 0) {
                    selectedCustomerId = newId
                    // Refresh dropdown
                    setupCustomerDropdown(newId)
                }
            }

        // Observe save result
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveFormViewModel.SaveResult.Success -> {
                    Toast.makeText(requireContext(), getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                    val originalCustomerId = arguments?.getLong("customerId", 0L) ?: 0L
                    if (originalCustomerId > 0) {
                        val bundle = androidx.core.os.bundleOf("customerId" to originalCustomerId)
                        findNavController().navigate(R.id.action_save_to_profileDetail, bundle)
                    } else {
                        findNavController().navigate(R.id.action_save_to_home)
                    }
                }
                is SaveFormViewModel.SaveResult.DuplicateName -> {
                    binding.tilName.error = "This name already exists. Please use a different name."
                }
                is SaveFormViewModel.SaveResult.Error -> {
                    Toast.makeText(requireContext(), getString(R.string.error_format, result.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Show/hide progress indicator
        viewModel.isSaving.observe(viewLifecycleOwner) { saving ->
            binding.btnSave.isEnabled = !saving
            binding.progressBar.visibility = if (saving) View.VISIBLE else View.GONE
        }

        // Process OCR Data using Smart Processing Layer
        viewModel.processOcrText(rawOcrText)
        
        viewModel.smartOcrResult.observe(viewLifecycleOwner) { smartResult ->
            if (smartResult != null) {
                // Auto-fill extracted data
                if (binding.etName.text.isNullOrEmpty() && !smartResult.date.isNullOrEmpty()) {
                    binding.etName.setText("Bill ${smartResult.date}")
                }
                
                if (binding.etNotes.text.isNullOrEmpty()) {
                    val notesBuilder = StringBuilder()
                    if (smartResult.amount != null) notesBuilder.append("Amount: ${smartResult.amount}\n")
                    if (smartResult.phone != null) notesBuilder.append("Phone: ${smartResult.phone}\n")
                    binding.etNotes.setText(notesBuilder.toString().trim())
                }

                // Auto-link customer if possible
                if (smartResult.matchedCustomerId != null && selectedCustomerId == null) {
                    setupCustomerDropdown(smartResult.matchedCustomerId)
                }
                
                // Show risk warning if applicable
                if ((smartResult.lateRiskScore ?: 0f) > 0.5f) {
                    Toast.makeText(requireContext(), getString(R.string.high_risk_warning), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupCustomerDropdown(preselectedId: Long = 0) {
        val app = requireActivity().application as BillSnapApp
        lifecycleScope.launch {
            customerList = app.customerRepository.getAllCustomersSync()

            val items = mutableListOf("None", "➕ Add New Profile")
            items.addAll(customerList.map { it.name })

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, items)
            binding.actvCustomer.setAdapter(adapter)

            // Pre-select if customerId was passed
            if (preselectedId > 0) {
                val customer = customerList.find { it.customerId == preselectedId }
                if (customer != null) {
                    selectedCustomerId = preselectedId
                    binding.actvCustomer.setText(customer.name, false)
                }
            } else {
                binding.actvCustomer.setText("None", false)
            }

            binding.actvCustomer.setOnItemClickListener { _, _, position, _ ->
                when (position) {
                    0 -> selectedCustomerId = null
                    1 -> {
                        // Navigate to add customer
                        findNavController().navigate(R.id.action_save_to_addCustomer)
                    }
                    else -> {
                        selectedCustomerId = customerList[position - 2].customerId
                    }
                }
            }
        }
    }

    private fun updateReminderDatetime() {
        reminderDatetime = calendar.timeInMillis
    }

    private fun validateAndSave() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val notes = binding.etNotes.text?.toString()?.trim() ?: ""
        
        binding.tilName.error = null

        if (name.isEmpty()) {
            binding.tilName.error = "Name is required."
            binding.etName.requestFocus()
            return
        }

        val totalAmount = binding.etTotalAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val paidAmount = binding.etPaidAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val remainingAmount = if (totalAmount > 0) maxOf(totalAmount - paidAmount, 0.0) else 0.0

        // Derive payment status from amounts
        val status = when {
            totalAmount > 0 && paidAmount >= totalAmount -> "Paid"
            totalAmount > 0 && paidAmount > 0 -> "Partial"
            binding.toggleStatus.checkedButtonId == R.id.btnPaid -> "Paid"
            else -> "Unpaid"
        }

        imagePath?.let { path ->
            viewModel.saveBill(
                context = requireContext(),
                name = name,
                notes = notes,
                tempImagePath = path,
                paymentStatus = status,
                customerId = selectedCustomerId,
                reminderDatetime = reminderDatetime,
                rawOcrText = rawOcrText,
                ocrConfidence = ocrConfidence,
                ocrImagePath = ocrImagePath,
                isSmartProcessed = viewModel.smartOcrResult.value != null,
                smartOcrJson = viewModel.smartOcrResult.value?.toJsonString(),
                lateRiskScore = viewModel.smartOcrResult.value?.lateRiskScore,
                totalAmount = if (totalAmount > 0) totalAmount else null,
                paidAmount = paidAmount,
                remainingAmount = remainingAmount
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
