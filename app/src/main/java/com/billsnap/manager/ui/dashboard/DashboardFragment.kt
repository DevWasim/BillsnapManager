package com.billsnap.manager.ui.dashboard

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentDashboardBinding
import com.billsnap.manager.util.CurrencyManager
import com.billsnap.manager.util.PdfExporter
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * Analytics Dashboard with restored original top level elements
 * plus the newly redesigned compact Monthly Chart and Bill List.
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return DashboardViewModel(app.billRepository) as T
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Navigation ──
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        
        binding.btnExport.visibility = if (com.billsnap.manager.security.PermissionManager.session.value.hasPermission("exportBills")) View.VISIBLE else View.GONE
        binding.btnExport.setOnClickListener { showDashboardExportSheet() }

        // ── Stat card clicks ──
        binding.cardTotalBills.setOnClickListener { navigateToGallery(null) }
        binding.cardPaid.setOnClickListener { navigateToGallery("Paid") }
        binding.cardUnpaid.setOnClickListener { navigateToGallery("Unpaid") }
        binding.cardOverdue.setOnClickListener { navigateToGallery("Overdue") }
        binding.cardDueSoon.setOnClickListener { navigateToGallery(null) }
        binding.cardCustomers.setOnClickListener { navigateToProfiles() }

        // ── Date label ──
        viewModel.currentDateLabel.observe(viewLifecycleOwner) {
            binding.tvDashboardDate.text = it
        }

        // ── 1. Dashboard Counts (Secondary Row) ──
        viewModel.dashboardCounts.observe(viewLifecycleOwner) { counts ->
            animateCount(binding.tvDueSoonCount, counts.totalBills)
            animateCount(binding.tvCustomerCount, counts.totalCustomers)
        }

        // ── 2. Global Financial Summary (Hero Cards) ──
        viewModel.globalFinancialSummary.observe(viewLifecycleOwner) { summary ->
            animateMoney(binding.tvTotalBills, summary.totalBilled)
            animateMoney(binding.tvPaidCount, summary.totalPaid)
            animateMoney(binding.tvUnpaidCount, summary.totalOutstanding)
            animateMoney(binding.tvOverdueCount, summary.overdueOutstanding)
        }

        // ── 3. Current Month Analytics (Progress Ring) ──
        viewModel.currentMonthSummary.observe(viewLifecycleOwner) { monthSummary ->
            binding.tvMonthBilled.text = CurrencyManager.format(monthSummary.totalBilled)
            binding.tvMonthCollected.text = CurrencyManager.format(monthSummary.totalPaid)
            
            val rate = if (monthSummary.totalBilled > 0) 
                (monthSummary.totalPaid / monthSummary.totalBilled) * 100f 
            else 0f
            binding.progressRing.setProgress(rate.toFloat())
        }

        // ── 4. Status Distribution (Donut Chart) ──
        val colorPaid = requireContext().getColor(R.color.status_paid)
        val colorUnpaid = requireContext().getColor(R.color.status_unpaid)
        val colorOverdue = requireContext().getColor(R.color.status_overdue)
        val colorPartial = requireContext().getColor(R.color.status_due_soon)
        
        viewModel.statusDistribution.observe(viewLifecycleOwner) { distList ->
            val data = mutableListOf<Pair<Float, Int>>()
            var txtLegend = ""
            for (dist in distList) {
                val color = when (dist.status) {
                    "Paid" -> colorPaid
                    "Unpaid" -> colorUnpaid
                    "Overdue" -> colorOverdue
                    "Partial", "Due Soon" -> colorPartial
                    else -> Color.GRAY
                }
                data.add(dist.count.toFloat() to color)
                txtLegend += "${dist.status}: ${dist.count}\n"
            }
            binding.donutChart.setSegments(data)
            binding.tvDonutLegend.text = txtLegend.trim()
        }

        // ── 5. Trend Chart (Monthly BarChart) ──
        viewModel.monthlyData.observe(viewLifecycleOwner) { data ->
            binding.barChart.setData(data)
        }

        // ── Month Drill-down ──
        val monthBillAdapter = MonthBillAdapter { bill ->
            val bundle = Bundle().apply { putLong("billId", bill.id) }
            findNavController().navigate(R.id.action_dashboard_to_detail, bundle)
        }
        binding.rvMonthBills.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = monthBillAdapter
        }

        binding.barChart.setOnBarClickListener(object : BarChartView.OnBarClickListener {
            override fun onBarClicked(index: Int, label: String, year: Int, month: Int) {
                if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("viewBills")) {
                    Toast.makeText(requireContext(), "Permission denied: Cannot view bills", Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.loadMonthBills(year, month)
            }
        })

        viewModel.selectedMonthDetail.observe(viewLifecycleOwner) { detail ->
            if (detail != null) {
                binding.layoutMonthHeader.visibility = View.VISIBLE
                binding.tvEmptyState.visibility = View.GONE

                binding.tvMonthDetailTitle.text = getString(R.string.bills_in_month, detail.monthLabel, detail.year)
                binding.tvMonthDetailCount.text = getString(R.string.bills_count_format, detail.totalCount)

                if (detail.bills.isEmpty()) {
                    binding.rvMonthBills.visibility = View.GONE
                } else {
                    binding.rvMonthBills.visibility = View.VISIBLE
                    monthBillAdapter.submitList(detail.bills)
                }
            } else {
                binding.layoutMonthHeader.visibility = View.GONE
                binding.rvMonthBills.visibility = View.GONE
                binding.tvEmptyState.visibility = View.VISIBLE
            }
        }

        // ── 6. Top Customers ──
        val topCustomersAdapter = TopCustomersAdapter { customer ->
            navigateToProfileDetail(customer.customerId)
        }
        binding.rvTopCustomers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = topCustomersAdapter
        }

        viewModel.topCustomersByPaid.observe(viewLifecycleOwner) { list ->
            if (list.isNullOrEmpty()) {
                binding.rvTopCustomers.visibility = View.GONE
                binding.tvNoCustomers.visibility = View.VISIBLE
            } else {
                binding.rvTopCustomers.visibility = View.VISIBLE
                binding.tvNoCustomers.visibility = View.GONE
                topCustomersAdapter.submitList(list)
            }
        }
    }

    private fun animateCount(view: android.widget.TextView, target: Int) {
        val animator = ValueAnimator.ofInt(0, target)
        animator.duration = 600
        animator.addUpdateListener { view.text = it.animatedValue.toString() }
        animator.start()
    }

    private fun animateMoney(view: android.widget.TextView, target: Double) {
        val animator = ValueAnimator.ofFloat(0f, target.toFloat())
        animator.duration = 800
        animator.addUpdateListener { 
            val v = it.animatedValue as Float
            view.text = CurrencyManager.formatCompact(v.toDouble())
        }
        animator.start()
    }

    private fun navigateToGallery(status: String?) {
        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("viewBills")) {
            Toast.makeText(requireContext(), "Permission denied: Cannot view bills", Toast.LENGTH_SHORT).show()
            return
        }
        val bundle = Bundle().apply { putString("filterStatus", status ?: "") }
        findNavController().navigate(R.id.action_dashboard_to_gallery, bundle)
    }

    private fun navigateToProfiles() {
        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("viewCustomers")) {
            Toast.makeText(requireContext(), "Permission denied: Cannot view customers", Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.action_dashboard_to_profiles)
    }

    private fun navigateToProfileDetail(customerId: Long) {
        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("viewCustomers")) {
            Toast.makeText(requireContext(), "Permission denied: Cannot view customers", Toast.LENGTH_SHORT).show()
            return
        }
        val bundle = Bundle().apply { putLong("customerId", customerId) }
        findNavController().navigate(R.id.action_dashboard_to_profileDetail, bundle)
    }

    private fun showDashboardExportSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_dashboard_export, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.btnExportFullSummary).setOnClickListener {
            dialog.dismiss()
            exportFullSummary()
        }

        sheetView.findViewById<View>(R.id.btnExportBillsBreakdown).setOnClickListener {
            dialog.dismiss()
            exportBillsBreakdown()
        }

        sheetView.findViewById<View>(R.id.btnExportCustomersSummary).setOnClickListener {
            dialog.dismiss()
            exportCustomersSummary()
        }

        sheetView.findViewById<View>(R.id.btnExportPaymentsReport).setOnClickListener {
            dialog.dismiss()
            exportPaymentsReport()
        }

        dialog.show()
    }

    private fun exportBillsBreakdown() {
        lifecycleScope.launch {
            try {
                val app = requireActivity().application as BillSnapApp
                val bills = app.billRepository.getAllBillsSync()
                val file = PdfExporter.generateBillsBreakdownPdf(requireContext(), bills)
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportFullSummary() {
        lifecycleScope.launch {
            try {
                val app = requireActivity().application as BillSnapApp
                val bills = app.billRepository.getAllBillsSync()
                val customers = app.customerRepository.getAllCustomersSync()
                val counts = viewModel.dashboardCounts.value ?: com.billsnap.manager.data.DashboardCounts(
                    totalBills = bills.size,
                    totalCustomers = customers.size,
                    paidCount = bills.count { it.paymentStatus == "Paid" },
                    unpaidCount = bills.count { it.paymentStatus == "Unpaid" },
                    overdueCount = bills.count { it.paymentStatus == "Overdue" || (it.paymentStatus == "Unpaid" && it.reminderDatetime != null && it.reminderDatetime <= System.currentTimeMillis()) }
                )
                val file = PdfExporter.generateDashboardSummaryPdf(requireContext(), counts, bills, customers)
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportCustomersSummary() {
        lifecycleScope.launch {
            try {
                val app = requireActivity().application as BillSnapApp
                val bills = app.billRepository.getAllBillsSync()
                val customers = app.customerRepository.getAllCustomersSync()
                val file = PdfExporter.generateCustomersSummaryPdf(requireContext(), customers, bills)
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportPaymentsReport() {
        lifecycleScope.launch {
            try {
                val app = requireActivity().application as BillSnapApp
                val bills = app.billRepository.getAllBillsSync()
                val file = PdfExporter.generatePaymentsReportPdf(requireContext(), bills)
                PdfExporter.sharePdf(requireContext(), file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
