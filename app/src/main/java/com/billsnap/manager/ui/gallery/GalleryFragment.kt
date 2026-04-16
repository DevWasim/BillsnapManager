package com.billsnap.manager.ui.gallery

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentGalleryBinding
import com.billsnap.manager.util.PdfExporter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gallery screen with search, filter bottom sheet, export bottom sheet,
 * swipe gestures, and multi-selection.
 */
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return GalleryViewModel(app.billRepository) as T
            }
        }
    }

    private lateinit var adapter: BillAdapter
    private lateinit var suggestionsAdapter: ArrayAdapter<String>
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearchView()
        setupSwipeGestures()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnExport.setOnClickListener { showExportBottomSheet() }
        binding.btnFilter.setOnClickListener { showFilterBottomSheet() }

        // Auto-apply filter from nav argument (e.g. from Dashboard stat cards)
        val filterArg = arguments?.getString("filterStatus")
        if (!filterArg.isNullOrEmpty()) {
            viewModel.setFilters(filterArg, null, null, null)
        }

        viewModel.bills.observe(viewLifecycleOwner) { bills ->
            adapter.submitList(bills)
            binding.tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
            binding.rvBills.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
            updateMultiSelectUI()
        }

        viewModel.suggestions.observe(viewLifecycleOwner) { suggestions ->
            suggestionsAdapter.clear()
            suggestionsAdapter.addAll(suggestions)
            suggestionsAdapter.notifyDataSetChanged()
        }

        // Multi-select action buttons
        binding.btnMultiDelete.setOnClickListener { confirmMultiDelete() }
        binding.btnMultiExport.setOnClickListener { exportSelectedBills() }
        binding.btnClearSelection.setOnClickListener {
            adapter.clearSelection()
            updateMultiSelectUI()
        }
    }

    private fun setupRecyclerView() {
        adapter = BillAdapter(
            onItemClick = { bill ->
                val bundle = Bundle().apply { putLong("billId", bill.id) }
                findNavController().navigate(R.id.action_gallery_to_detail, bundle)
            },
            onItemLongClick = { _ ->
                // Multi-select started via adapter
                updateMultiSelectUI()
            }
        )
        binding.rvBills.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = this@GalleryFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearchView() {
        suggestionsAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf()
        )
        binding.actvSearch.setAdapter(suggestionsAdapter)
        binding.actvSearch.threshold = 1

        binding.actvSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.actvSearch.setOnItemClickListener { _, _, position, _ ->
            val selected = suggestionsAdapter.getItem(position) ?: return@setOnItemClickListener
            binding.actvSearch.setText(selected)
            binding.actvSearch.setSelection(selected.length)
            viewModel.setSearchQuery(selected)
        }
    }

    private fun setupSwipeGestures() {
        val swipeCallback = SwipeCallback(
            onSwipeRight = { position ->
                val bill = adapter.currentList[position]
                viewModel.markAsPaid(bill.id)
                Snackbar.make(binding.root, R.string.marked_as_paid, Snackbar.LENGTH_SHORT).show()
            },
            onSwipeLeft = { position ->
                val bill = adapter.currentList[position]
                if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("deleteBills")) {
                    adapter.notifyItemChanged(position)
                    Toast.makeText(requireContext(), getString(R.string.no_permission_delete_bills), Toast.LENGTH_SHORT).show()
                    return@SwipeCallback
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewModel.deleteBill(bill)
                        com.billsnap.manager.security.ActivityLogger.logAction("Bill Deleted", "Deleted bill: ${bill.customName}")
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }
        )
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvBills)
    }

    // --- Filter Bottom Sheet ---
    private fun showFilterBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_filter, null)
        dialog.setContentView(sheetView)

        val actvStatus = sheetView.findViewById<AutoCompleteTextView>(R.id.actvStatus)
        val actvCustomer = sheetView.findViewById<AutoCompleteTextView>(R.id.actvCustomer)
        val btnStartDate = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartDate)
        val btnEndDate = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEndDate)
        val btnApply = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApply)
        val btnReset = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnReset)

        // Status options
        val statusOptions = listOf(getString(R.string.all_statuses), "Paid", "Unpaid", "Overdue")
        actvStatus.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statusOptions))
        actvStatus.setText(viewModel.filterStatus ?: getString(R.string.all_statuses), false)

        // Customer options
        val app = requireActivity().application as BillSnapApp
        val customerNames = mutableListOf(getString(R.string.all_customers))
        val customerIds = mutableListOf<Long?>(null)

        app.customerRepository.getAllCustomers().observe(viewLifecycleOwner) { customers ->
            customers.forEach {
                customerNames.add(it.name)
                customerIds.add(it.customerId)
            }
            actvCustomer.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, customerNames))
        }
        actvCustomer.setText(getString(R.string.all_customers), false)

        // Date range state
        var startDate: Long? = viewModel.filterStartDate
        var endDate: Long? = viewModel.filterEndDate

        if (startDate != null) btnStartDate.text = dateFormat.format(Date(startDate))
        if (endDate != null) btnEndDate.text = dateFormat.format(Date(endDate))

        btnStartDate.setOnClickListener {
            showDatePicker { date ->
                startDate = date
                btnStartDate.text = dateFormat.format(Date(date))
            }
        }

        btnEndDate.setOnClickListener {
            showDatePicker { date ->
                endDate = date
                btnEndDate.text = dateFormat.format(Date(date))
            }
        }

        btnApply.setOnClickListener {
            val selectedStatus = actvStatus.text.toString()
            val status = if (selectedStatus == getString(R.string.all_statuses)) null else selectedStatus
            val customerIdx = customerNames.indexOf(actvCustomer.text.toString())
            val customerId = if (customerIdx > 0) customerIds[customerIdx] else null
            viewModel.setFilters(status, customerId, startDate, endDate)
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            viewModel.clearFilters()
            dialog.dismiss()
        }

        dialog.show()
    }

    // --- Export Bottom Sheet ---
    private fun showExportBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_export, null)
        dialog.setContentView(sheetView)

        // Export single (if multi-selected)
        val canExport = com.billsnap.manager.security.PermissionManager.session.value.hasPermission("exportBills")
        
        sheetView.findViewById<View>(R.id.btnExportSingle).apply {
            visibility = if (adapter.isMultiSelectMode && canExport) View.VISIBLE else View.GONE
            setOnClickListener {
                dialog.dismiss()
                exportSelected()
            }
        }

        sheetView.findViewById<View>(R.id.btnExportUnpaid).setOnClickListener {
            dialog.dismiss()
            if (canExport) exportUnpaid() else Toast.makeText(requireContext(), getString(R.string.no_permission), Toast.LENGTH_SHORT).show()
        }

        sheetView.findViewById<View>(R.id.btnExportDateRange).setOnClickListener {
            dialog.dismiss()
            if (canExport) exportByDateRange() else Toast.makeText(requireContext(), getString(R.string.no_permission), Toast.LENGTH_SHORT).show()
        }

        sheetView.findViewById<View>(R.id.btnExportByCustomer).setOnClickListener {
            dialog.dismiss()
            if (canExport) exportByCustomer() else Toast.makeText(requireContext(), getString(R.string.no_permission), Toast.LENGTH_SHORT).show()
        }

        sheetView.findViewById<View>(R.id.btnExportFiltered).setOnClickListener {
            dialog.dismiss()
            if (com.billsnap.manager.security.PermissionManager.session.value.hasPermission("exportBills")) {
                exportCurrentResults()
            } else {
                Toast.makeText(requireContext(), getString(R.string.no_permission_export), Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun exportCurrentResults() {
        val bills = viewModel.bills.value
        if (bills.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_records_to_export), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val file = PdfExporter.generatePdf(requireContext(), bills)
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.export_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportSelected() {
        viewModel.getBillsByIds(adapter.getSelectedIds()) { bills ->
            if (bills.isEmpty()) return@getBillsByIds
            lifecycleScope.launch {
                val file = PdfExporter.generatePdf(requireContext(), bills)
                PdfExporter.sharePdf(requireContext(), file)
                adapter.clearSelection()
            }
        }
    }

    private fun exportUnpaid() {
        lifecycleScope.launch {
            val bills = viewModel.getUnpaidBills()
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_unpaid_bills), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = PdfExporter.generatePdf(requireContext(), bills)
            PdfExporter.sharePdf(requireContext(), file)
        }
    }

    private fun exportByDateRange() {
        showDatePicker { start ->
            showDatePicker { end ->
                lifecycleScope.launch {
                    val bills = viewModel.getBillsByDateRange(start, end)
                    if (bills.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.no_bills_in_range), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val file = PdfExporter.generatePdf(requireContext(), bills)
                    PdfExporter.sharePdf(requireContext(), file)
                }
            }
        }
    }

    private fun exportByCustomer() {
        val app = requireActivity().application as BillSnapApp
        app.customerRepository.getAllCustomers().observe(viewLifecycleOwner) { customers ->
            if (customers.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_customers), Toast.LENGTH_SHORT).show()
                return@observe
            }
            val names = customers.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_customer)
                .setItems(names) { _, which ->
                    lifecycleScope.launch {
                        val bills = viewModel.getBillsByCustomer(customers[which].customerId)
                        if (bills.isEmpty()) {
                            Toast.makeText(requireContext(), getString(R.string.no_bills_for_this_customer), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val file = PdfExporter.generatePdf(requireContext(), bills)
                        PdfExporter.sharePdf(requireContext(), file)
                    }
                }
                .show()
        }
    }

    private fun showDatePicker(onDateSelected: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0)
            onDateSelected(cal.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateMultiSelectUI() {
        val count = adapter.getSelectedCount()
        if (count > 0) {
            binding.layoutMultiSelect.visibility = View.VISIBLE
            binding.tvSelectedCount.text = getString(R.string.selected_count_format, count)
            
            val session = com.billsnap.manager.security.PermissionManager.session.value
            binding.btnMultiDelete.visibility = if (session.hasPermission("deleteBills")) View.VISIBLE else View.GONE
            binding.btnMultiExport.visibility = if (session.hasPermission("exportBills")) View.VISIBLE else View.GONE
        } else {
            binding.layoutMultiSelect.visibility = View.GONE
        }
    }

    private fun confirmMultiDelete() {
        val count = adapter.getSelectedCount()
        if (count == 0) return

        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("deleteBills")) {
            Toast.makeText(requireContext(), getString(R.string.no_permission_delete_bills), Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.delete_confirm_multi, count))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val ids = adapter.getSelectedIds()
                viewModel.deleteBills(ids)
                com.billsnap.manager.security.ActivityLogger.logAction("Multi Delete", "Deleted $count bills")
                adapter.clearSelection()
                updateMultiSelectUI()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun exportSelectedBills() {
        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("exportBills")) {
            Toast.makeText(requireContext(), getString(R.string.no_permission_export), Toast.LENGTH_SHORT).show()
            return
        }

        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) return

        viewModel.getBillsByIds(ids) { bills ->
            if (bills.isEmpty()) return@getBillsByIds
            lifecycleScope.launch {
                try {
                    val file = PdfExporter.generatePdf(requireContext(), bills)
                    PdfExporter.sharePdf(requireContext(), file)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.export_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
