package com.billsnap.manager.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentAddCustomerBinding
import com.bumptech.glide.Glide
import java.io.File
import java.util.UUID

/**
 * Form screen for creating or editing a customer profile.
 * In edit mode (when customerId is passed), pre-fills fields and uses update instead of insert.
 */
class AddCustomerFragment : Fragment() {

    private var _binding: FragmentAddCustomerBinding? = null
    private val binding get() = _binding!!
    private var profileImagePath: String = ""
    private var editCustomerId: Long = 0L
    private var isEditMode = false

    private val viewModel: AddCustomerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return AddCustomerViewModel(app.customerRepository) as T
            }
        }
    }

    // Image picker
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                // Copy to internal storage
                val profileDir = File(requireContext().filesDir, "profile_images")
                if (!profileDir.exists()) profileDir.mkdirs()
                val destFile = File(profileDir, "${UUID.randomUUID()}.jpg")

                requireContext().contentResolver.openInputStream(it)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                profileImagePath = destFile.absolutePath
                Glide.with(this).load(destFile).centerCrop().into(binding.ivProfileImage)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.failed_load_image), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddCustomerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if edit mode
        editCustomerId = arguments?.getLong("customerId", 0L) ?: 0L
        isEditMode = editCustomerId > 0L

        if (isEditMode) {
            binding.btnSave.text = getString(R.string.update_profile)
            viewModel.loadCustomer(editCustomerId)
        }

        // Pre-fill profile image from bill (when adding profile from detail screen)
        val prefillImagePath = arguments?.getString("prefillImagePath", "") ?: ""
        if (!isEditMode && prefillImagePath.isNotEmpty() && profileImagePath.isEmpty()) {
            val srcFile = File(prefillImagePath)
            if (srcFile.exists()) {
                try {
                    val profileDir = File(requireContext().filesDir, "profile_images")
                    if (!profileDir.exists()) profileDir.mkdirs()
                    val destFile = File(profileDir, "${UUID.randomUUID()}.jpg")
                    srcFile.copyTo(destFile, overwrite = true)
                    profileImagePath = destFile.absolutePath
                    Glide.with(this).load(destFile).centerCrop().into(binding.ivProfileImage)
                } catch (_: Exception) { }
            }
        }

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnSave.setOnClickListener { validateAndSave() }

        // Pre-fill fields in edit mode
        viewModel.customer.observe(viewLifecycleOwner) { customer ->
            customer?.let {
                binding.etName.setText(it.name)
                binding.etPhone.setText(it.phoneNumber)
                binding.etDetails.setText(it.details)
                profileImagePath = it.profileImagePath
                if (it.profileImagePath.isNotEmpty()) {
                    val file = File(it.profileImagePath)
                    if (file.exists()) {
                        Glide.with(this).load(file).centerCrop().into(binding.ivProfileImage)
                    }
                }
            }
        }

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AddCustomerViewModel.SaveResult.Success -> {
                    val msg = if (isEditMode) "Profile updated!" else "Profile saved!"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

                    if (isEditMode) {
                        // Notify ProfileDetail to refresh
                        findNavController().previousBackStackEntry?.savedStateHandle?.set(
                            "profileUpdated", true
                        )
                    } else {
                        // Set result so SaveForm can pick it up
                        findNavController().previousBackStackEntry?.savedStateHandle?.set(
                            "newCustomerId", result.customerId
                        )
                    }
                    findNavController().popBackStack()
                }
                is AddCustomerViewModel.SaveResult.Error -> {
                    Toast.makeText(requireContext(), getString(R.string.error_format, result.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.isSaving.observe(viewLifecycleOwner) { saving ->
            binding.btnSave.isEnabled = !saving
            binding.progressBar.visibility = if (saving) View.VISIBLE else View.GONE
        }
    }

    private fun validateAndSave() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val phone = binding.etPhone.text?.toString()?.trim() ?: ""
        val details = binding.etDetails.text?.toString()?.trim() ?: ""

        binding.tilName.error = null
        if (name.isEmpty()) {
            binding.tilName.error = "Name is required."
            binding.etName.requestFocus()
            return
        }

        if (isEditMode) {
            viewModel.updateCustomer(editCustomerId, name, phone, details, "", profileImagePath)
        } else {
            viewModel.saveCustomer(name, phone, details, profileImagePath)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
