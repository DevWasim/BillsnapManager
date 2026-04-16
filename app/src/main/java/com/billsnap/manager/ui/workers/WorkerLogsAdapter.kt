package com.billsnap.manager.ui.workers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.databinding.ItemActivityLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActivityLog(
    val id: String = "",
    val actionType: String = "",
    val details: String = "",
    val timestamp: Long = 0L
)

class WorkerLogsAdapter : ListAdapter<ActivityLog, WorkerLogsAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemActivityLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(private val binding: ItemActivityLogBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
        
        fun bind(log: ActivityLog) {
            binding.tvActionType.text = log.actionType
            binding.tvDetails.text = log.details
            binding.tvTimestamp.text = dateFormat.format(Date(log.timestamp))
        }
    }

    private class LogDiffCallback : DiffUtil.ItemCallback<ActivityLog>() {
        override fun areItemsTheSame(oldItem: ActivityLog, newItem: ActivityLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ActivityLog, newItem: ActivityLog): Boolean {
            return oldItem == newItem
        }
    }
}
