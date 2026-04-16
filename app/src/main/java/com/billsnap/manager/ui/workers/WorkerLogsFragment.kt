package com.billsnap.manager.ui.workers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.billsnap.manager.databinding.FragmentWorkerLogsBinding

class WorkerLogsFragment : Fragment() {

    private var _binding: FragmentWorkerLogsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkerLogsViewModel by viewModels()
    private val adapter = WorkerLogsAdapter()
    private var workerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workerId = arguments?.getString("workerId")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkerLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        if (workerId == null) {
            Toast.makeText(context, "Worker ID missing", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = adapter

        viewModel.logs.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.tvEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            binding.rvLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.loadLogs(workerId!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
