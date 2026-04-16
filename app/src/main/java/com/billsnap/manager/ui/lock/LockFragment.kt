package com.billsnap.manager.ui.lock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentLockBinding
import com.billsnap.manager.security.AppLockManager

/**
 * Lock screen shown on app launch when App Lock is enabled.
 * Supports biometric (fingerprint) and 4-digit PIN authentication.
 */
class LockFragment : Fragment() {

    private var _binding: FragmentLockBinding? = null
    private val binding get() = _binding!!
    private lateinit var lockManager: AppLockManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lockManager = AppLockManager.getInstance(requireContext())

        binding.btnUnlock.setOnClickListener {
            val pin = binding.etPin.text?.toString() ?: ""
            if (lockManager.verifyPin(pin)) {
                navigateToHome()
            } else {
                binding.tvError.text = getString(R.string.wrong_pin)
                binding.tvError.visibility = View.VISIBLE
                binding.etPin.text?.clear()
            }
        }

        // Show fingerprint button only if biometric is available and user chose it
        val canBiometric = BiometricManager.from(requireContext())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

        binding.btnFingerprint.visibility =
            if (canBiometric && lockManager.useFingerprint) View.VISIBLE else View.GONE

        binding.btnFingerprint.setOnClickListener { showBiometricPrompt() }

        // Auto-show biometric if available
        if (canBiometric && lockManager.useFingerprint) {
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                navigateToHome()
            }

            override fun onAuthenticationFailed() {
                Toast.makeText(requireContext(), R.string.use_pin_instead, Toast.LENGTH_SHORT).show()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_locked))
            .setSubtitle(getString(R.string.unlock_with_fingerprint))
            .setNegativeButtonText(getString(R.string.use_pin_instead))
            .build()

        prompt.authenticate(info)
    }

    private fun navigateToHome() {
        findNavController().navigate(R.id.action_lock_to_home)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
