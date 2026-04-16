package com.billsnap.manager.ui.workers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.databinding.ItemWorkerBinding

class WorkerAdapter(
    private val items: List<WorkerItem>,
    private val onClick: (WorkerItem) -> Unit
) : RecyclerView.Adapter<WorkerAdapter.WorkerViewHolder>() {

    class WorkerViewHolder(val binding: ItemWorkerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val binding = ItemWorkerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WorkerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            tvName.text = item.name
            tvEmail.text = item.email
            tvRole.text = item.role.replaceFirstChar { it.uppercase() }
            tvStatus.text = item.status.uppercase()
            root.setOnClickListener { onClick(item) }
        }
    }

    override fun getItemCount() = items.size
}
