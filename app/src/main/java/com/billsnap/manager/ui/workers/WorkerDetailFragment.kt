package com.billsnap.manager.ui.workers

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentWorkerDetailBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WorkerDetailFragment : Fragment() {

    private var _binding: FragmentWorkerDetailBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var workerId: String? = null
    private var shopId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workerId = arguments?.getString("workerId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWorkerDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (workerId == null) {
            Toast.makeText(context, getString(R.string.worker_id_missing), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        setupPermissionToggles()
        
        binding.btnApprove.setOnClickListener {
            approveWorker()
        }

        binding.btnRemoveWorker.setOnClickListener {
            confirmRemoveWorker()
        }

        binding.btnViewLogs.setOnClickListener {
            val bundle = androidx.core.os.bundleOf("workerId" to workerId)
            findNavController().navigate(com.billsnap.manager.R.id.action_workerDetail_to_workerLogs, bundle)
        }

        loadWorkerDetails()
    }

    private fun setupPermissionToggles() {
        val allSwitches = listOf(
            binding.switchViewBills, binding.switchCreateBills, binding.switchEditBills,
            binding.switchDeleteBills, binding.switchExportBills,
            binding.switchViewCustomers, binding.switchCreateCustomers, binding.switchEditCustomers,
            binding.switchDeleteCustomers,
            binding.switchViewDashboard, binding.switchManageWorkers, binding.switchViewLogs
        )

        binding.switchFullAccess.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                allSwitches.forEach { it.isChecked = true }
            }
            updatePermissions()
        }

        allSwitches.forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ -> updatePermissions() }
        }
    }

    private fun loadWorkerDetails() {
        binding.progressBar.visibility = View.VISIBLE
        val uid = auth.currentUser?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get shopId
                val ownerDoc = firestore.collection("users").document(uid).get().await()
                shopId = ownerDoc.getString("currentShopId")

                if (shopId.isNullOrEmpty()) {
                    showError("Shop not found")
                    return@launch
                }

                // Verify owner
                val shopDoc = firestore.collection("shops").document(shopId!!).get().await()
                if (shopDoc.getString("ownerId") != uid) {
                    showError("Unauthorized access")
                    binding.cardPermissions.visibility = View.GONE
                    binding.btnRemoveWorker.visibility = View.GONE
                    return@launch
                }

                // Get worker user data
                val workerUserDoc = firestore.collection("users").document(workerId!!).get().await()
                binding.tvName.text = workerUserDoc.getString("name") ?: "Unknown"
                binding.tvEmail.text = workerUserDoc.getString("email") ?: "No email"
                binding.chipRole.text = (workerUserDoc.getString("role") ?: "worker").replaceFirstChar { it.uppercase() }

                // Get worker shop data (permissions and status)
                val workerShopDoc = firestore.collection("shops").document(shopId!!)
                    .collection("shopWorkers").document(workerId!!).get().await()

                if (!workerShopDoc.exists()) {
                    showError("Worker not found in shop")
                    return@launch
                }

                val status = workerShopDoc.getString("status") ?: "pending"
                binding.chipStatus.text = status.uppercase()
                
                if (status == "pending") {
                    binding.chipStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#FFCC00")) // Yellowish for pending
                    binding.cardPendingActions.visibility = View.VISIBLE
                    binding.cardPermissions.visibility = View.GONE // Hide permissions until approved
                } else {
                    binding.chipStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#4CAF50")) // Green for active
                    binding.cardPendingActions.visibility = View.GONE
                    binding.cardPermissions.visibility = View.VISIBLE
                }

                // Load permissions
                val permissions = workerShopDoc.get("permissions") as? Map<String, Boolean>
                
                // Temporarily disable listeners to avoid recursive updates
                val allSwitches = listOf(
                    binding.switchViewBills, binding.switchCreateBills, binding.switchEditBills,
                    binding.switchDeleteBills, binding.switchExportBills,
                    binding.switchViewCustomers, binding.switchCreateCustomers, binding.switchEditCustomers,
                    binding.switchDeleteCustomers,
                    binding.switchViewDashboard, binding.switchManageWorkers, binding.switchViewLogs
                )
                allSwitches.forEach { it.setOnCheckedChangeListener(null) }
                binding.switchFullAccess.setOnCheckedChangeListener(null)

                if (permissions != null) {
                    binding.switchViewBills.isChecked = permissions["viewBills"] == true
                    binding.switchCreateBills.isChecked = permissions["createBills"] == true
                    binding.switchEditBills.isChecked = permissions["editBills"] == true
                    binding.switchDeleteBills.isChecked = permissions["deleteBills"] == true
                    binding.switchExportBills.isChecked = permissions["exportBills"] == true
                    binding.switchViewCustomers.isChecked = permissions["viewCustomers"] == true
                    binding.switchCreateCustomers.isChecked = permissions["createCustomers"] == true
                    binding.switchEditCustomers.isChecked = permissions["editCustomers"] == true
                    binding.switchDeleteCustomers.isChecked = permissions["deleteCustomers"] == true
                    binding.switchViewDashboard.isChecked = permissions["viewDashboard"] == true
                    binding.switchManageWorkers.isChecked = permissions["manageWorkers"] == true
                    binding.switchViewLogs.isChecked = permissions["viewLogs"] == true
                    binding.switchFullAccess.isChecked = permissions["fullAccess"] == true
                } else {
                    // Default to false for everything
                    allSwitches.forEach { it.isChecked = false }
                    binding.switchFullAccess.isChecked = false
                }

                setupPermissionToggles()

                binding.progressBar.visibility = View.GONE

            } catch (e: Exception) {
                Log.e("WorkerDetail", "Failed to load worker details", e)
                showError("Failed to load details")
            }
        }
    }

    private fun approveWorker() {
        if (shopId == null || workerId == null) return
        binding.progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                firestore.collection("shops").document(shopId!!)
                    .collection("shopWorkers").document(workerId!!)
                    .update("status", "active").await()
                
                Toast.makeText(context, getString(R.string.worker_approved), Toast.LENGTH_SHORT).show()
                loadWorkerDetails() // Reload to reflect changes
            } catch (e: Exception) {
                Log.e("WorkerDetail", "Failed to approve worker", e)
                showError("Failed to approve worker")
            }
        }
    }

    private fun updatePermissions() {
        if (shopId == null || workerId == null) return
        
        // Don't update if still pending
        if (binding.cardPendingActions.visibility == View.VISIBLE) return

        val newPermissions = hashMapOf(
            "viewBills" to binding.switchViewBills.isChecked,
            "createBills" to binding.switchCreateBills.isChecked,
            "editBills" to binding.switchEditBills.isChecked,
            "deleteBills" to binding.switchDeleteBills.isChecked,
            "exportBills" to binding.switchExportBills.isChecked,
            "viewCustomers" to binding.switchViewCustomers.isChecked,
            "createCustomers" to binding.switchCreateCustomers.isChecked,
            "editCustomers" to binding.switchEditCustomers.isChecked,
            "deleteCustomers" to binding.switchDeleteCustomers.isChecked,
            "viewDashboard" to binding.switchViewDashboard.isChecked,
            "manageWorkers" to binding.switchManageWorkers.isChecked,
            "viewLogs" to binding.switchViewLogs.isChecked,
            "fullAccess" to binding.switchFullAccess.isChecked
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                firestore.collection("shops").document(shopId!!)
                    .collection("shopWorkers").document(workerId!!)
                    .update("permissions", newPermissions).await()
                Log.d("WorkerDetail", "Permissions updated successfully")
            } catch (e: Exception) {
                Log.e("WorkerDetail", "Failed to update permissions", e)
                Toast.makeText(context, getString(R.string.failed_save_permissions), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmRemoveWorker() {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Worker")
            .setMessage("Are you sure you want to remove this worker from the shop? They will lose all access.")
            .setPositiveButton("Remove") { _, _ -> removeWorker() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeWorker() {
        if (shopId == null || workerId == null) return
        binding.progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Remove from shopWorkers
                firestore.collection("shops").document(shopId!!)
                    .collection("shopWorkers").document(workerId!!).delete().await()

                // 2. Clear currentShopId from user document
                firestore.collection("users").document(workerId!!)
                    .update("currentShopId", null).await()

                Toast.makeText(context, getString(R.string.worker_removed), Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()

            } catch (e: Exception) {
                Log.e("WorkerDetail", "Failed to remove worker", e)
                showError("Failed to remove worker")
            }
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
