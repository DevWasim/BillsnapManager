package com.billsnap.manager.ui.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.billsnap.manager.BillSnapApp
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentProfilesBinding

/**
 * Profiles list screen showing all customers with unpaid bill count badges.
 */
class ProfilesFragment : Fragment() {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfilesViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as BillSnapApp
                return ProfilesViewModel(app.customerRepository) as T
            }
        }
    }

    private lateinit var adapter: CustomerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.fabAddProfile.setOnClickListener {
            findNavController().navigate(R.id.action_profiles_to_addCustomer)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            com.billsnap.manager.security.PermissionManager.session.collect { session ->
                if (session.hasPermission("createCustomers")) {
                    binding.fabAddProfile.visibility = View.VISIBLE
                } else {
                    binding.fabAddProfile.visibility = View.GONE
                }
            }
        }

        viewModel.customers.observe(viewLifecycleOwner) { customers ->
            adapter.submitList(customers)
            binding.layoutEmpty.visibility = if (customers.isEmpty()) View.VISIBLE else View.GONE
            binding.rvProfiles.visibility = if (customers.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        adapter = CustomerAdapter { item ->
            val bundle = Bundle().apply { putLong("customerId", item.customer.customerId) }
            findNavController().navigate(R.id.action_profiles_to_profileDetail, bundle)
        }
        binding.rvProfiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProfilesFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
