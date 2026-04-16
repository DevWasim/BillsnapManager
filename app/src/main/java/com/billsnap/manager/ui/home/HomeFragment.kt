package com.billsnap.manager.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentHomeBinding
import com.billsnap.manager.sync.BillSyncRepository
import kotlinx.coroutines.launch

/**
 * Home screen — entry point of the app.
 *
 * For Workers: executes deterministic full sync DIRECTLY in a coroutine,
 * blocking Gallery navigation until sync completes.
 * No WorkManager, no incremental logic, no background scheduling.
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** true after sync coroutine has finished successfully */
    private var syncComplete = false
    /** true while sync coroutine is running */
    private var syncInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in_up)
        val fadeInDelayed = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in_up_delayed)
        binding.btnCamera.startAnimation(fadeIn)
        binding.btnGallery.startAnimation(fadeInDelayed)
        binding.btnProfiles.startAnimation(fadeInDelayed)

        binding.btnCamera.setOnClickListener { findNavController().navigate(R.id.action_home_to_camera) }

        // Gallery nav BLOCKED until sync complete for workers
        binding.btnGallery.setOnClickListener {
            if (!syncComplete && isWorker()) {
                Toast.makeText(requireContext(), "Syncing bills… please wait", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findNavController().navigate(R.id.action_home_to_gallery)
        }

        binding.btnProfiles.setOnClickListener { findNavController().navigate(R.id.action_home_to_profiles) }
        binding.btnWorkers.setOnClickListener { findNavController().navigate(R.id.action_home_to_workers) }
        binding.btnDashboard.setOnClickListener { findNavController().navigate(R.id.action_home_to_dashboard) }
        binding.btnSettings.setOnClickListener { findNavController().navigate(R.id.action_home_to_settings) }

        checkWorkerStatus()
    }

    private fun isWorker(): Boolean {
        val session = com.billsnap.manager.security.PermissionManager.session.value
        return session.role == "worker"
    }

    private fun checkWorkerStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            com.billsnap.manager.security.PermissionManager.session.collect { session ->
                Log.d(TAG, "Session: role=${session.role}, approved=${session.isApproved}, " +
                        "canViewBills=${session.canViewBills}, shopId=${session.shopId}")

                if (!session.isApproved && session.role != "owner") {
                    // Worker not approved — show pending
                    binding.layoutPending.visibility = View.VISIBLE
                    binding.btnCamera.visibility = View.GONE
                    binding.btnGallery.visibility = View.GONE
                    binding.btnProfiles.visibility = View.GONE
                    binding.btnWorkers.visibility = View.GONE
                    binding.btnDashboard.visibility = View.GONE
                } else {
                    // Approved — show all buttons (no conditional visibility for debugging)
                    binding.layoutPending.visibility = View.GONE
                    binding.btnCamera.visibility = View.VISIBLE
                    binding.btnGallery.visibility = View.VISIBLE
                    binding.btnProfiles.visibility = View.VISIBLE
                    binding.btnDashboard.visibility = View.VISIBLE

                    // Show Workers button only for owners
                    binding.btnWorkers.visibility =
                        if (session.role == "owner" || session.hasPermission("manageWorkers"))
                            View.VISIBLE else View.GONE

                    // Trigger sync for worker with viewBills access
                    if (session.role == "worker" && session.canViewBills
                        && !session.shopId.isNullOrEmpty()
                        && !syncInProgress && !syncComplete
                    ) {
                        executeDeterministicSync(session.uid, session.shopId)
                    }
                }
            }
        }
    }

    /**
     * Execute the deterministic full sync DIRECTLY.
     * This blocks within the coroutine — Gallery nav is locked until done.
     */
    private fun executeDeterministicSync(workerId: String, shopId: String) {
        syncInProgress = true
        Log.i(TAG, "▶▶ Starting deterministic sync for worker=$workerId, shop=$shopId")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val syncRepo = BillSyncRepository.getInstance(requireContext())
                val result = syncRepo.fullSyncForWorker(workerId, shopId)

                // Log final assertion checkpoints
                Log.i(TAG, "═══ SYNC RESULT ═══")
                Log.i(TAG, "  resolvedAdminId       = ${result.resolvedAdminId}")
                Log.i(TAG, "  firestorePathUsed     = ${result.firestorePathUsed}")
                Log.i(TAG, "  firestoreDocumentCount= ${result.firestoreDocumentCount}")
                Log.i(TAG, "  mappedEntityCount     = ${result.mappedEntityCount}")
                Log.i(TAG, "  roomInsertCount       = ${result.roomInsertCount}")
                Log.i(TAG, "  roomQueryCount        = ${result.roomQueryCount}")

                syncComplete = true
                syncInProgress = false

                if (result.roomQueryCount > 0) {
                    Toast.makeText(requireContext(),
                        "Synced ${result.roomQueryCount} bills", Toast.LENGTH_SHORT).show()
                } else if (result.firestoreDocumentCount == 0) {
                    Toast.makeText(requireContext(),
                        "No bills found in admin store", Toast.LENGTH_SHORT).show()
                    syncComplete = true  // Allow Gallery nav even with 0 bills
                }

            } catch (e: BillSyncRepository.SyncException) {
                syncInProgress = false
                Log.e(TAG, "═══ SYNC FAILED at ${e.checkpoint} ═══", e)

                // Show failing checkpoint to user
                Toast.makeText(requireContext(),
                    "Sync failed: ${e.checkpoint}\n${e.message}", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                syncInProgress = false
                Log.e(TAG, "═══ SYNC FAILED (unknown) ═══", e)
                Toast.makeText(requireContext(),
                    "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
