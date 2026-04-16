package com.billsnap.manager.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.os.bundleOf
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import android.util.TypedValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.billsnap.manager.data.PaymentRecord
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentDetailBinding
import com.billsnap.manager.ui.gallery.BillAdapter
import com.billsnap.manager.data.AdminProfileManager
import com.billsnap.manager.util.CurrencyManager
import com.billsnap.manager.util.PdfExporter
import com.billsnap.manager.util.ShareCardGenerator
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail screen showing full bill info with smart payment status toggle,
 * status history (created/paid/reminder timestamps), customer name, and export.
 */
class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private var currentImagePath: String? = null

    private val viewModel: DetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return DetailViewModel(app.billRepository, app.database) as T
            }
        }
    }

    private val dateTimeFormat = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val billId = arguments?.getLong("billId") ?: run {
            findNavController().popBackStack(); return
        }
        viewModel.loadBill(billId)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // Export single bill
        binding.btnExportBill.setOnClickListener {
            if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("exportBills")) {
                Toast.makeText(requireContext(), "No permission to export bills", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportCurrentBill()
        }

        // Share bill as image card
        binding.btnShareBill.setOnClickListener {
            shareBillAsCard()
        }

        // Tap image to open full-screen viewer
        binding.ivImage.setOnClickListener {
            currentImagePath?.let { path ->
                findNavController().navigate(
                    R.id.action_detail_to_fullImage,
                    bundleOf("imagePath" to path)
                )
            }
        }

        // Tap OCR button to open monospace text viewer
        binding.btnViewOcr.setOnClickListener {
            val bill = viewModel.bill.value ?: return@setOnClickListener
            findNavController().navigate(
                R.id.action_detail_to_ocrText,
                bundleOf("billId" to bill.id)
            )
        }

        // Listen for new customer profile created from AddCustomerFragment
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<Long>("newCustomerId")
            ?.observe(viewLifecycleOwner) { customerId ->
                val billId = arguments?.getLong("billId") ?: return@observe
                if (customerId > 0) {
                    viewModel.updateCustomerId(billId, customerId)
                }
            }

        viewLifecycleOwner.lifecycleScope.launch {
            com.billsnap.manager.security.PermissionManager.session.collect { session ->
                val canEditBills = session.hasPermission("editBills")
                val canCreateCustomers = session.hasPermission("createCustomers")
                val canCreateBills = session.hasPermission("createBills")
                val canExportBills = session.hasPermission("exportBills")
                
                binding.btnToggleStatus.isEnabled = canEditBills
                binding.btnExportBill.visibility = if (canExportBills) View.VISIBLE else View.GONE
                
                // If they can't create customers, they can't add a profile from here
                if (!canCreateCustomers) {
                    binding.btnAddProfile.visibility = View.GONE
                }
                
                // If they can't create bills, they shouldn't see "Add More Bills"
                if (!canCreateBills) {
                    binding.btnAddMoreBills.visibility = View.GONE
                }
            }
        }

        viewModel.bill.observe(viewLifecycleOwner) { bill ->
            bill ?: return@observe

            // Show share button for any bill with data
            binding.btnShareBill.visibility = View.VISIBLE

            currentImagePath = bill.imagePath

            // Load full image
            val file = File(bill.imagePath)
            Glide.with(this)
                .load(if (file.exists()) file else null)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(binding.ivImage)

            binding.tvName.text = bill.customName
            binding.tvNotes.text = bill.notes.ifEmpty { "No notes" }

            // OCR Results Button Visibility: show when rawOcrText is not empty
            if (!bill.rawOcrText.isNullOrBlank() || !bill.ocrTextFilePath.isNullOrBlank()) {
                binding.btnViewOcr.visibility = View.VISIBLE
            } else {
                binding.btnViewOcr.visibility = View.GONE
            }

            // --- Payment Summary Card ---
            val totalAmount = bill.totalAmount ?: 0.0
            if (totalAmount > 0) {
                binding.cardPayment.visibility = View.VISIBLE
                binding.tvPayTotal.text = CurrencyManager.formatCompact(totalAmount)
                binding.tvPayPaid.text = CurrencyManager.formatCompact(bill.paidAmount)
                binding.tvPayRemaining.text = CurrencyManager.formatCompact(bill.remainingAmount)

                // Progress bar (0-100)
                val progress = if (totalAmount > 0) ((bill.paidAmount / totalAmount) * 100).toInt() else 0
                binding.progressPayment.setProgressCompat(progress.coerceIn(0, 100), true)

                // Color remaining based on value
                if (bill.remainingAmount > 0) {
                    binding.tvPayRemaining.setTextColor(0xFFF44336.toInt())
                } else {
                    binding.tvPayRemaining.setTextColor(0xFF69F0AE.toInt())
                }

                // Hide Add Payment button if fully paid
                binding.btnAddPayment.visibility = if (bill.remainingAmount > 0) View.VISIBLE else View.GONE
            } else {
                binding.cardPayment.visibility = View.GONE
            }

            // Profile section
            if (bill.customerId != null) {
                // Has profile – show profile card, hide Add Profile button
                binding.cardProfile.visibility = View.VISIBLE
                binding.btnAddProfile.visibility = View.GONE
                
                val currentSession = com.billsnap.manager.security.PermissionManager.session.value
                binding.btnAddMoreBills.visibility = if (currentSession.hasPermission("createBills")) View.VISIBLE else View.GONE

                val app = requireActivity().application as BillSnapApp
                lifecycleScope.launch {
                    val customer = app.customerRepository.getCustomerById(bill.customerId)
                    if (_binding == null) return@launch
                    if (customer != null) {
                        binding.tvProfileName.text = customer.name
                        if (customer.phoneNumber.isNotEmpty()) {
                            binding.tvProfilePhone.text = customer.phoneNumber
                            binding.tvProfilePhone.visibility = View.VISIBLE
                        } else {
                            binding.tvProfilePhone.visibility = View.GONE
                        }
                        // Load profile image
                        if (customer.profileImagePath.isNotEmpty()) {
                            val profileFile = File(customer.profileImagePath)
                            if (profileFile.exists()) {
                                Glide.with(this@DetailFragment)
                                    .load(profileFile)
                                    .centerCrop()
                                    .placeholder(R.drawable.ic_person_add)
                                    .into(binding.ivProfileImage)
                            }
                        }
                        // Navigate to profile detail on click
                        binding.cardProfile.setOnClickListener {
                            findNavController().navigate(
                                R.id.action_detail_to_profileDetail,
                                bundleOf("customerId" to bill.customerId)
                            )
                        }
                    }
                }

                // Add more bills for this customer
                binding.btnAddMoreBills.setOnClickListener {
                    findNavController().navigate(
                        R.id.action_detail_to_camera,
                        bundleOf("customerId" to bill.customerId)
                    )
                }
            } else {
                // No profile – hide card + add more bills
                binding.cardProfile.visibility = View.GONE
                binding.btnAddMoreBills.visibility = View.GONE

                val currentSession = com.billsnap.manager.security.PermissionManager.session.value
                binding.btnAddProfile.visibility = if (currentSession.hasPermission("createCustomers")) View.VISIBLE else View.GONE

                binding.btnAddProfile.setOnClickListener {
                    // Navigate to AddCustomer, passing bill image as prefill
                    findNavController().navigate(
                        R.id.action_detail_to_addCustomer,
                        bundleOf("prefillImagePath" to bill.imagePath)
                    )
                }
            }

            // Status History
            binding.tvCreatedTimestamp.text = dateTimeFormat.format(Date(bill.timestamp))

            if (bill.paidTimestamp != null) {
                binding.layoutPaidTimestamp.visibility = View.VISIBLE
                binding.tvPaidTimestamp.text = dateTimeFormat.format(Date(bill.paidTimestamp))
            } else {
                binding.layoutPaidTimestamp.visibility = View.GONE
            }

            // Parse and inflate PaymentHistoryJson
            binding.layoutPaymentHistory.removeAllViews()
            if (!bill.paymentHistoryJson.isNullOrBlank()) {
                val gson = Gson()
                val listType = object : TypeToken<List<PaymentRecord>>() {}.type
                try {
                    val historyList: List<PaymentRecord> = gson.fromJson(bill.paymentHistoryJson, listType) ?: emptyList()
                    if (historyList.isNotEmpty()) {
                        binding.layoutPaymentHistory.visibility = View.VISIBLE
                        historyList.forEach { record ->
                            val row = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt())
                                gravity = Gravity.CENTER_VERTICAL
                            }

                            val label = TextView(requireContext()).apply {
                                text = "Partial Pay"
                                textSize = 13f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.status_paid))
                                layoutParams = LinearLayout.LayoutParams(
                                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100f, resources.displayMetrics).toInt(),
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            }

                            val value = TextView(requireContext()).apply {
                                val amountStr = CurrencyManager.formatCompact(record.amount)
                                val dateStr = dateTimeFormat.format(Date(record.timestamp))
                                text = "$amountStr on $dateStr"
                                textSize = 13f
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.status_paid))
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                )
                            }

                            row.addView(label)
                            row.addView(value)
                            binding.layoutPaymentHistory.addView(row)
                        }
                    } else {
                        binding.layoutPaymentHistory.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    binding.layoutPaymentHistory.visibility = View.GONE
                }
            } else {
                binding.layoutPaymentHistory.visibility = View.GONE
            }

            if (bill.reminderDatetime != null) {
                binding.layoutReminderTimestamp.visibility = View.VISIBLE
                binding.tvReminderTimestamp.text = dateTimeFormat.format(Date(bill.reminderDatetime))
            } else {
                binding.layoutReminderTimestamp.visibility = View.GONE
            }

            // Update toggle button appearance with smart status
            val effectiveStatus = BillAdapter.getEffectiveStatus(bill)
            updateStatusUI(effectiveStatus)
        }

        binding.btnToggleStatus.setOnClickListener {
            if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("editBills")) {
                Toast.makeText(requireContext(), "No permission to edit bills", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.togglePaymentStatus()
        }

        // --- Add Payment Dialog ---
        binding.btnAddPayment.setOnClickListener {
            if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("editBills")) {
                Toast.makeText(requireContext(), "No permission to edit bills", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bill = viewModel.bill.value ?: return@setOnClickListener
            val remaining = bill.remainingAmount
            if (remaining <= 0) return@setOnClickListener

            val input = com.google.android.material.textfield.TextInputEditText(requireContext())
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            input.hint = getString(R.string.amount_max_hint, "%,.2f".format(remaining))
            input.setPadding(48, 32, 48, 32)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_payment))
                .setMessage(getString(R.string.remaining_format, "%,.2f".format(remaining)))
                .setView(input)
                .setPositiveButton(getString(R.string.pay)) { _, _ ->
                    val amount = input.text?.toString()?.toDoubleOrNull() ?: 0.0
                    if (amount > 0 && amount <= remaining) {
                        viewModel.addPayment(amount)
                    } else if (amount > remaining) {
                        Toast.makeText(requireContext(), getString(R.string.amount_exceeds_remaining), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun exportCurrentBill() {
        val bill = viewModel.bill.value ?: return
        lifecycleScope.launch {
            try {
                val file = PdfExporter.generatePdf(requireContext(), listOf(bill))
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareBillAsCard() {
        val bill = viewModel.bill.value ?: return
        val ctx = requireContext()
        val adminProfile = AdminProfileManager.getInstance(ctx)
        requireNotNull(adminProfile) { "AdminProfile was null in DetailFragment" }
        android.util.Log.d("ShareCard", "AdminProfile received: StoreName=${adminProfile.storeName}")
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Determine overdue status
        val now = System.currentTimeMillis()
        val isOverdue = bill.reminderDatetime != null && bill.reminderDatetime <= now && bill.paymentStatus != "Paid"
        val overdueDays = if (isOverdue && bill.reminderDatetime != null) {
            ((now - bill.reminderDatetime) / (1000 * 60 * 60 * 24)).toInt()
        } else 0

        // Calculate on-time stats from customer bills
        val onTimeCount = 0
        val totalBillCount = 0

        val currencySymbol = CurrencyManager.currentCurrency.value.symbol

        val shareData = ShareCardGenerator.BillShareData(
            storeName = adminProfile.storeName,
            storeLogoPath = adminProfile.storeLogoPath,
            customerName = bill.customName,
            invoiceId = bill.invoiceNumber ?: "#${bill.id}",
            invoiceDate = bill.invoiceDate?.let { dateFormat.format(Date(it)) },
            dueDate = bill.reminderDatetime?.let { dateFormat.format(Date(it)) },
            todayDate = dateFormat.format(Date()),
            originalAmount = bill.totalAmount ?: 0.0,
            paidAmount = bill.paidAmount,
            remainingAmount = bill.remainingAmount,
            paymentStatus = bill.paymentStatus,
            isOverdue = isOverdue,
            overdueDays = overdueDays,
            onTimeCount = onTimeCount,
            totalBillCount = totalBillCount,
            paymentMethods = adminProfile.getShareablePaymentMethods(),
            currencySymbol = currencySymbol
        )

        Toast.makeText(ctx, R.string.generating_share_card, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val generator = ShareCardGenerator(ctx)
            val uri = generator.generateBillShareCard(shareData)
            if (_binding == null) return@launch

            if (uri != null) {
                val message = getString(R.string.share_bill_message, shareData.invoiceId)
                generator.shareWithFallback(uri, message)
            } else {
                Toast.makeText(ctx, R.string.share_card_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatusUI(status: String) {
        when (status) {
            "Paid" -> {
                binding.btnToggleStatus.text = getString(R.string.paid)
                binding.btnToggleStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_paid)
                )
                binding.btnToggleStatus.setIconResource(R.drawable.ic_check)
            }
            "Overdue" -> {
                binding.btnToggleStatus.text = getString(R.string.overdue)
                binding.btnToggleStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_overdue)
                )
                binding.btnToggleStatus.setIconResource(R.drawable.ic_close)
            }
            "Due Soon" -> {
                binding.btnToggleStatus.text = getString(R.string.due_soon)
                binding.btnToggleStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_due_soon)
                )
                binding.btnToggleStatus.setIconResource(R.drawable.ic_close)
            }
            "Partial" -> {
                binding.btnToggleStatus.text = getString(R.string.partial)
                binding.btnToggleStatus.setBackgroundColor(0xFFF44336.toInt())
                binding.btnToggleStatus.setIconResource(R.drawable.ic_clock)
            }
            else -> {
                binding.btnToggleStatus.text = getString(R.string.unpaid)
                binding.btnToggleStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_unpaid)
                )
                binding.btnToggleStatus.setIconResource(R.drawable.ic_close)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
