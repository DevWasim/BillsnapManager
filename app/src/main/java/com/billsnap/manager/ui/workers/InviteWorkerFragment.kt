package com.billsnap.manager.ui.workers

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentInviteWorkerBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class InviteWorkerFragment : Fragment() {

    private var _binding: FragmentInviteWorkerBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInviteWorkerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnGenerateEmailInvite.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isEmpty()) {
                binding.etEmail.error = "Email is required"
                return@setOnClickListener
            }
            generateInvite(email)
        }

        binding.btnGenerateGeneralLink.setOnClickListener {
            generateInvite(null)
        }
    }

    private fun generateInvite(email: String?) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(context, getString(R.string.sign_in_required), Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnGenerateEmailInvite.isEnabled = false
        binding.btnGenerateGeneralLink.isEnabled = false

        lifecycleScope.launch {
            try {
                // Get current shop ID
                val userDoc = firestore.collection("users").document(uid).get().await()
                val shopId = userDoc.getString("currentShopId")

                if (shopId.isNullOrEmpty()) {
                    Toast.makeText(context, getString(R.string.no_shop_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val inviteToken = UUID.randomUUID().toString()
                
                val inviteData = hashMapOf(
                    "shopId" to shopId,
                    "email" to email,
                    "inviteToken" to inviteToken,
                    "createdAt" to System.currentTimeMillis(),
                    "expiresAt" to System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000), // 7 days
                    "used" to false
                )

                firestore.collection("invites").document(inviteToken).set(inviteData).await()

                // Generate intent to share the link
                val deepLink = "https://billsnap.com/invite?token=$inviteToken"
                shareLink(deepLink)

            } catch (e: Exception) {
                Log.e("InviteWorker", "Failed to generate invite", e)
                Toast.makeText(context, getString(R.string.failed_generate_invite), Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnGenerateEmailInvite.isEnabled = true
                binding.btnGenerateGeneralLink.isEnabled = true
            }
        }
    }

    private fun shareLink(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my shop on BillSnap! Click here: $url")
        }
        startActivity(Intent.createChooser(intent, "Share Invite Link"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
