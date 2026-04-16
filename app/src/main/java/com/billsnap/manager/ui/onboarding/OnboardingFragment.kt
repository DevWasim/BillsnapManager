package com.billsnap.manager.ui.onboarding

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
import com.billsnap.manager.databinding.FragmentOnboardingBinding
import com.billsnap.manager.sync.CloudSyncManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        resetButtons()
                    }
                } catch (e: ApiException) {
                    Toast.makeText(requireContext(), getString(R.string.sign_in_failed), Toast.LENGTH_LONG).show()
                    resetButtons()
                }
            } else {
                resetButtons()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val webClientId = resolveWebClientId()
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
        if (webClientId != null) gsoBuilder.requestIdToken(webClientId)
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gsoBuilder.build())

        binding.btnContinueWithGoogle.setOnClickListener {
            if (webClientId == null) {
                Toast.makeText(requireContext(), getString(R.string.google_sign_in_not_configured_short), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            binding.btnContinueWithGoogle.isEnabled = false
            binding.btnContinueWithGoogle.text = getString(R.string.signing_in)
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.btnContinueWithoutLogin.setOnClickListener {
            markOnboardingComplete()
            navigateToHome()
        }
    }

    private fun resolveWebClientId(): String? {
        val webClientIdRes = resources.getIdentifier("default_web_client_id", "string", requireContext().packageName)
        if (webClientIdRes == 0) return null
        val webClientId = getString(webClientIdRes).trim()
        if (webClientId.isEmpty() || webClientId.contains("placeholder", true)) return null
        return webClientId
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    setupFirebaseUser()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.firebase_auth_failed), Toast.LENGTH_SHORT).show()
                    resetButtons()
                }
            }
    }

    private fun setupFirebaseUser() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            resetButtons()
            return
        }
        val uid = user.uid
        val firestore = FirebaseFirestore.getInstance()

        lifecycleScope.launch {
            try {
                val userDocRef = firestore.collection("users").document(uid)
                val userDoc = userDocRef.get().await()

                if (!userDoc.exists() || !userDoc.contains("currentShopId")) {
                    val shopId = UUID.randomUUID().toString()
                    val shopData = hashMapOf(
                        "shopId" to shopId,
                        "ownerId" to uid,
                        "name" to "${user.displayName ?: "My"} Shop",
                        "createdAt" to System.currentTimeMillis()
                    )
                    firestore.collection("shops").document(shopId).set(shopData).await()

                    val userData = hashMapOf(
                        "uid" to uid,
                        "email" to user.email,
                        "name" to user.displayName,
                        "role" to "owner",
                        "currentShopId" to shopId
                    )
                    userDocRef.set(userData).await()
                }

                markOnboardingComplete()
                navigateToHome()
            } catch (e: Exception) {
                Log.e("Onboarding", "Failed to setup user", e)
                Toast.makeText(requireContext(), getString(R.string.failed_setup_account), Toast.LENGTH_LONG).show()
                resetButtons()
            }
        }
    }

    private fun resetButtons() {
        binding.btnContinueWithGoogle.isEnabled = true
        binding.btnContinueWithGoogle.text = getString(R.string.continue_with_google)
    }

    private fun markOnboardingComplete() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_complete", true).apply()
    }

    private fun navigateToHome() {
        findNavController().navigate(R.id.action_onboardingFragment_to_homeFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
