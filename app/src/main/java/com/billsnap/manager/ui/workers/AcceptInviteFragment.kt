package com.billsnap.manager.ui.workers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentAcceptInviteBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AcceptInviteFragment : Fragment() {

    private var _binding: FragmentAcceptInviteBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = arguments?.getString("token")

        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account?.idToken
                    if (idToken != null) {
                        firebaseAuthWithGoogle(idToken)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.no_id_token), Toast.LENGTH_LONG).show()
                    }
                } catch (e: ApiException) {
                    Toast.makeText(requireContext(), getString(R.string.sign_in_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAcceptInviteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val webClientId = resolveWebClientId()
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
        if (webClientId != null) gsoBuilder.requestIdToken(webClientId)
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gsoBuilder.build())

        binding.btnSignInGoogle.setOnClickListener {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.btnJoinShop.setOnClickListener {
            joinShop()
        }

        validateState()
    }

    private fun resolveWebClientId(): String? {
        val webClientIdRes = resources.getIdentifier("default_web_client_id", "string", requireContext().packageName)
        if (webClientIdRes == 0) return null
        val webClientId = getString(webClientIdRes).trim()
        if (webClientId.isEmpty() || webClientId.contains("placeholder", true)) return null
        return webClientId
    }

    private fun validateState() {
        if (token.isNullOrEmpty()) {
            binding.tvStatus.text = getString(R.string.invalid_invite_link)
            binding.progressBar.visibility = View.GONE
            return
        }

        if (auth.currentUser == null) {
            binding.tvStatus.text = getString(R.string.sign_in_to_accept)
            binding.progressBar.visibility = View.GONE
            binding.btnSignInGoogle.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = getString(R.string.ready_to_join)
            binding.progressBar.visibility = View.GONE
            binding.btnSignInGoogle.visibility = View.GONE
            binding.btnJoinShop.visibility = View.VISIBLE
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSignInGoogle.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.signing_in)

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    validateState()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.firebase_auth_failed), Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnSignInGoogle.visibility = View.VISIBLE
                }
            }
    }

    private fun joinShop() {
        val uid = auth.currentUser?.uid ?: return
        val inviteToken = token ?: return

        binding.progressBar.visibility = View.VISIBLE
        binding.btnJoinShop.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.joining_shop)

        lifecycleScope.launch {
            try {
                val inviteDoc = firestore.collection("invites").document(inviteToken).get().await()
                if (!inviteDoc.exists()) {
                    showError("Invalid invite token")
                    return@launch
                }

                val used = inviteDoc.getBoolean("used") ?: false
                val expiresAt = inviteDoc.getLong("expiresAt") ?: 0L

                if (used) {
                    showError("Invite link has already been used")
                    return@launch
                }
                if (System.currentTimeMillis() > expiresAt) {
                    showError("Invite link has expired")
                    return@launch
                }

                val shopId = inviteDoc.getString("shopId")
                if (shopId.isNullOrEmpty()) {
                    showError("Invalid shop data")
                    return@launch
                }

                val targetEmail = inviteDoc.getString("email")
                val isGeneralLink = targetEmail.isNullOrEmpty()

                if (!isGeneralLink && targetEmail != auth.currentUser?.email) {
                    showError("This invite is not for your email address")
                    return@launch
                }

                // Add to shopWorkers
                val workerData = hashMapOf(
                    "userId" to uid,
                    "joinedAt" to System.currentTimeMillis(),
                    "status" to if (isGeneralLink) "pending" else "active",
                    "permissions" to hashMapOf(
                        "viewBills" to true,
                        "editBills" to false,
                        "viewCustomers" to true,
                        "editCustomers" to false,
                        "fullAccess" to false
                    ),
                    "invitedBy" to inviteDoc.getString("createdBy") // optional
                )
                firestore.collection("shops").document(shopId)
                    .collection("shopWorkers").document(uid).set(workerData).await()

                // Mark invite as used only if it's a specific email invite
                if (!isGeneralLink) {
                    firestore.collection("invites").document(inviteToken).update("used", true).await()
                }

                // Update user's currentShopId and mark onboarding complete
                val userRef = firestore.collection("users").document(uid)
                val userDocSnap = userRef.get().await()
                if (!userDocSnap.exists()) {
                    val userData = hashMapOf(
                        "uid" to uid,
                        "email" to auth.currentUser?.email,
                        "name" to auth.currentUser?.displayName,
                        "role" to "worker",
                        "currentShopId" to shopId
                    )
                    userRef.set(userData).await()
                } else {
                    userRef.update("currentShopId", shopId).await()
                }

                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("onboarding_complete", true).apply()

                Toast.makeText(context, getString(R.string.joined_shop), Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_acceptInvite_to_home)

            } catch (e: Exception) {
                Log.e("AcceptInvite", "Join failed", e)
                showError("Failed to join shop")
            }
        }
    }

    private fun showError(msg: String) {
        binding.tvStatus.text = msg
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
