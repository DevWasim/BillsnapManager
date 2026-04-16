package com.billsnap.manager.ui.workers

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentWorkersBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WorkersFragment : Fragment() {

    private var _binding: FragmentWorkersBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWorkersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.recyclerViewWorkers.layoutManager = LinearLayoutManager(requireContext())
        
        binding.fabInvite.setOnClickListener {
            findNavController().navigate(R.id.action_workers_to_invite)
        }

        loadWorkers()
    }

    private fun loadWorkers() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(context, getString(R.string.sign_in_required), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get current shop ID
                val userDoc = firestore.collection("users").document(uid).get().await()
                val shopId = userDoc.getString("currentShopId")

                if (shopId.isNullOrEmpty()) {
                    Toast.makeText(context, getString(R.string.no_shop_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Check if user is owner
                val shopDoc = firestore.collection("shops").document(shopId).get().await()
                val isOwner = shopDoc.getString("ownerId") == uid
                if (!isOwner) {
                    binding.fabInvite.visibility = View.GONE
                }

                // Load workers
                val workersSnapshot = firestore.collection("shops").document(shopId).collection("shopWorkers").get().await()
                val workerIds = workersSnapshot.documents.map { it.id }

                if (workerIds.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.recyclerViewWorkers.adapter = WorkerAdapter(emptyList()) {}
                    return@launch
                }

                val userDocs = firestore.collection("users").whereIn("uid", workerIds).get().await()

                val items = userDocs.documents.mapNotNull { doc ->
                    val workerDoc = workersSnapshot.documents.find { it.id == doc.id }
                    if (workerDoc != null) {
                        WorkerItem(
                            uid = doc.id,
                            name = doc.getString("name") ?: "Unknown",
                            email = doc.getString("email") ?: "",
                            role = doc.getString("role") ?: "worker",
                            status = workerDoc.getString("status") ?: "pending"
                        )
                    } else null
                }

                binding.tvEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewWorkers.adapter = WorkerAdapter(items) { worker ->
                    val bundle = Bundle().apply { putString("workerId", worker.uid) }
                    findNavController().navigate(R.id.action_workers_to_workerDetail, bundle)
                }

            } catch (e: Exception) {
                Log.e("WorkersFragment", "Failed to load workers", e)
                Toast.makeText(context, getString(R.string.failed_load_workers), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class WorkerItem(
    val uid: String,
    val name: String,
    val email: String,
    val role: String,
    val status: String
)
