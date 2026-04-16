package com.billsnap.manager.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.launch
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.data.AdminProfileManager
import com.billsnap.manager.databinding.FragmentProfileDetailBinding
import com.billsnap.manager.ui.gallery.BillAdapter
import com.billsnap.manager.util.CurrencyManager
import com.billsnap.manager.util.ShareCardGenerator
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Displays customer profile details and their linked bills grid.
 * Supports Edit (navigates to AddCustomer in edit mode) and Delete (with cascade warning).
 */
class ProfileDetailFragment : Fragment() {

    private var _binding: FragmentProfileDetailBinding? = null
    private val binding get() = _binding!!
    private var customerId: Long = 0

    private val viewModel: ProfileDetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return ProfileDetailViewModel(app.billRepository, app.customerRepository) as T
            }
        }
    }

    private lateinit var adapter: BillAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        customerId = arguments?.getLong("customerId", 0L) ?: 0L
        if (customerId == 0L) {
            findNavController().popBackStack()
            return
        }

        adapter = BillAdapter(
            onItemClick = { bill ->
                findNavController().navigate(
                    R.id.action_profileDetail_to_detail,
                    bundleOf("billId" to bill.id)
                )
            }
        )

        binding.rvBills.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvBills.adapter = adapter

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // Edit button → navigate to AddCustomer in edit mode
        binding.btnEdit.setOnClickListener {
            if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("editCustomers")) {
                Toast.makeText(requireContext(), getString(R.string.no_permission_edit_customers), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findNavController().navigate(
                R.id.action_profileDetail_to_addCustomer,
                bundleOf("customerId" to customerId)
            )
        }

        // Delete button with confirmation
        binding.btnDelete.setOnClickListener { confirmDelete() }

        // Share profile as image card
        binding.btnShareProfile.setOnClickListener {
            shareProfileAsCard()
        }

        // Camera FAB → open camera with this customer pre-assigned
        binding.fabCamera.setOnClickListener {
            findNavController().navigate(
                R.id.action_profileDetail_to_camera,
                bundleOf("customerId" to customerId)
            )
        }

        viewModel.loadCustomer(customerId)

        viewLifecycleOwner.lifecycleScope.launch {
            com.billsnap.manager.security.PermissionManager.session.collect { session ->
                val canEditCustomers = session.hasPermission("editCustomers")
                val canDeleteCustomers = session.hasPermission("deleteCustomers")
                val canCreateBills = session.hasPermission("createBills")
                
                binding.btnEdit.visibility = if (canEditCustomers) View.VISIBLE else View.GONE
                binding.btnDelete.visibility = if (canDeleteCustomers) View.VISIBLE else View.GONE
                binding.fabCamera.visibility = if (canCreateBills) View.VISIBLE else View.GONE
            }
        }

        viewModel.customer.observe(viewLifecycleOwner) { customer ->
            customer?.let {
                binding.tvTitle.text = it.name
                binding.tvName.text = it.name

                if (it.phoneNumber.isNotEmpty()) {
                    binding.tvPhone.visibility = View.VISIBLE
                    binding.tvPhone.text = it.phoneNumber
                }

                if (it.details.isNotEmpty()) {
                    binding.tvDetails.visibility = View.VISIBLE
                    binding.tvDetails.text = it.details
                }

                if (it.profileImagePath.isNotEmpty()) {
                    val file = File(it.profileImagePath)
                    if (file.exists()) {
                        Glide.with(this).load(file).centerCrop().into(binding.ivProfileImage)
                    }
                }
            }
        }

        viewModel.bills.observe(viewLifecycleOwner) { bills ->
            adapter.submitList(bills)
            binding.layoutEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
            binding.rvBills.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
        }

        // Financial Summary Card
        viewModel.financialSummary.observe(viewLifecycleOwner) { summary ->
            if (summary != null && summary.totalBilled > 0) {
                binding.cardFinancial.visibility = View.VISIBLE
                binding.tvFinTotalBilled.text = "%,.0f".format(summary.totalBilled)
                binding.tvFinTotalPaid.text = "%,.0f".format(summary.totalPaid)
                binding.tvFinOutstanding.text = "%,.0f".format(summary.totalRemaining)
            } else {
                binding.cardFinancial.visibility = View.GONE
            }
        }

        viewModel.deleteResult.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), getString(R.string.profile_deleted), Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            } else {
                Toast.makeText(requireContext(), getString(R.string.failed_to_delete), Toast.LENGTH_SHORT).show()
            }
        }

        // Listen for edit result refresh
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<Boolean>("profileUpdated")
            ?.observe(viewLifecycleOwner) { updated ->
                if (updated) viewModel.loadCustomer(customerId)
            }
    }

    private fun confirmDelete() {
        if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("deleteCustomers")) {
            Toast.makeText(requireContext(), getString(R.string.no_permission_delete_customers), Toast.LENGTH_SHORT).show()
            return
        }

        val billCount = viewModel.getBillCount()
        val message = if (billCount > 0) {
            "This profile has $billCount bill(s). Deleting will unlink those bills from this customer. Continue?"
        } else {
            "Delete this profile?"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Profile")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteCustomer()
                com.billsnap.manager.security.ActivityLogger.logAction("Customer Deleted", "Deleted customer ID: $customerId")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun shareProfileAsCard() {
        val customer = viewModel.customer.value ?: return
        val bills = viewModel.bills.value ?: emptyList()
        val summary = viewModel.financialSummary.value
        val ctx = requireContext()
        val adminProfile = AdminProfileManager.getInstance(ctx)
        requireNotNull(adminProfile) { "AdminProfile was null in ProfileDetailFragment" }
        android.util.Log.d("ShareCard", "AdminProfile received: StoreName=${adminProfile.storeName}")
        
        val currencySymbol = CurrencyManager.currentCurrency.value.symbol

        // Build pending bills list (remaining > 0)
        val pendingBills = bills.filter { (it.remainingAmount ?: 0.0) > 0.0 }
        val recentBills = pendingBills.take(3).map { bill ->
            ShareCardGenerator.RecentBillInfo(
                invoiceId = bill.invoiceNumber ?: "#${bill.id}",
                amount = bill.remainingAmount ?: 0.0,
                status = BillAdapter.getEffectiveStatus(bill)
            )
        }

        // Calculate on-time percentage
        val paidBills = bills.filter { it.paymentStatus == "Paid" }
        val totalBills = bills.size
        val onTimePercentage = if (totalBills > 0) ((paidBills.size.toDouble() / totalBills) * 100).toInt() else 0

        // Count overdue bills
        val now = System.currentTimeMillis()
        val overdueBillCount = bills.count { bill ->
            bill.paymentStatus != "Paid" &&
            bill.reminderDatetime != null &&
            bill.reminderDatetime <= now
        }

        val shareData = ShareCardGenerator.ProfileShareData(
            storeName = adminProfile.storeName,
            storeLogoPath = null, // Store logo not yet implemented
            customerName = customer.name,
            customerImagePath = null, // Customer photo not yet implemented
            totalBills = totalBills,
            totalPaidAmount = summary?.totalPaid ?: 0.0,
            totalOutstandingAmount = summary?.totalRemaining ?: 0.0,
            onTimePercentage = onTimePercentage,
            recentBills = recentBills,
            overdueBillCount = overdueBillCount,
            paymentMethods = adminProfile.getShareablePaymentMethods(),
            currencySymbol = currencySymbol
        )

        Toast.makeText(ctx, R.string.generating_share_card, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val generator = ShareCardGenerator(ctx)
            val uri = generator.generateProfileShareCard(shareData)
            if (_binding == null) return@launch

            if (uri != null) {
                val message = getString(R.string.share_profile_message, customer.name)
                generator.shareWithFallback(uri, message)
            } else {
                Toast.makeText(ctx, R.string.share_card_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
