package com.billsnap.manager.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.R
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.util.CurrencyManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import java.io.File

/**
 * RecyclerView adapter for the bill grid.
 * Supports Smart Status System and multi-selection mode.
 */
class BillAdapter(
    private val onItemClick: (BillEntity) -> Unit,
    private val onItemLongClick: (BillEntity) -> Unit = {}
) : ListAdapter<BillEntity, BillAdapter.BillViewHolder>(BillDiffCallback()) {

    // Multi-selection state
    private val selectedIds = mutableSetOf<Long>()
    var isMultiSelectMode = false
        private set

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) isMultiSelectMode = false
        notifyDataSetChanged()
    }

    fun startMultiSelect(id: Long) {
        isMultiSelectMode = true
        selectedIds.add(id)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        isMultiSelectMode = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()
    fun getSelectedCount(): Int = selectedIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BillViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bill, parent, false)
        return BillViewHolder(view)
    }

    override fun onBindViewHolder(holder: BillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BillViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val ivStatusIcon: ImageView = itemView.findViewById(R.id.ivStatusIcon)
        private val viewStatusBg: View = itemView.findViewById(R.id.viewStatusBg)

        fun bind(bill: BillEntity) {
            tvName.text = bill.customName

            val file = File(bill.imagePath)
            Glide.with(itemView.context)
                .load(if (file.exists()) file else null)
                .transform(CenterCrop(), RoundedCorners(24))
                .override(300, 300)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(ivThumbnail)

            // Smart Status indicator
            val effectiveStatus = getEffectiveStatus(bill)
            when (effectiveStatus) {
                "Paid" -> {
                    viewStatusBg.setBackgroundResource(R.drawable.bg_status_paid)
                    ivStatusIcon.setImageResource(R.drawable.ic_check)
                }
                "Partial" -> {
                    viewStatusBg.setBackgroundResource(R.drawable.bg_status_due_soon)
                    ivStatusIcon.setImageResource(R.drawable.ic_clock)
                }
                "Overdue" -> {
                    viewStatusBg.setBackgroundResource(R.drawable.bg_status_overdue)
                    ivStatusIcon.setImageResource(R.drawable.ic_warning)
                }
                "Due Soon" -> {
                    viewStatusBg.setBackgroundResource(R.drawable.bg_status_due_soon)
                    ivStatusIcon.setImageResource(R.drawable.ic_clock)
                }
                else -> {
                    viewStatusBg.setBackgroundResource(R.drawable.bg_status_unpaid)
                    ivStatusIcon.setImageResource(R.drawable.ic_close)
                }
            }

            // Show amount info if bill has a totalAmount
            val total = bill.totalAmount ?: 0.0
            if (total > 0) {
                tvAmount.visibility = View.VISIBLE
                if (bill.remainingAmount > 0) {
                    val remStr = CurrencyManager.format(bill.remainingAmount)
                    val totStr = CurrencyManager.format(total)
                    tvAmount.text = itemView.context.getString(R.string.rem_format, remStr, totStr)
                    tvAmount.setTextColor(0xFFF44336.toInt()) // amber
                } else {
                    val totStr = CurrencyManager.format(total)
                    tvAmount.text = itemView.context.getString(R.string.paid_format, totStr)
                    tvAmount.setTextColor(0xFF69F0AE.toInt()) // green
                }
            } else {
                tvAmount.visibility = View.GONE
            }

            // Selection highlight
            itemView.alpha = if (isMultiSelectMode && selectedIds.contains(bill.id)) 0.6f else 1.0f

            itemView.setOnClickListener {
                if (isMultiSelectMode) {
                    toggleSelection(bill.id)
                } else {
                    onItemClick(bill)
                }
            }

            itemView.setOnLongClickListener {
                if (!isMultiSelectMode) {
                    startMultiSelect(bill.id)
                    onItemLongClick(bill)
                }
                true
            }
        }
    }

    companion object {
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

        fun getEffectiveStatus(bill: BillEntity): String {
            if (bill.paymentStatus == "Paid") return "Paid"
            if (bill.paymentStatus == "Partial") return "Partial"
            val reminder = bill.reminderDatetime
            if (reminder != null) {
                val now = System.currentTimeMillis()
                if (now >= reminder) return "Overdue"
                if (reminder - now <= TWENTY_FOUR_HOURS_MS) return "Due Soon"
            }
            return "Unpaid"
        }
    }

    class BillDiffCallback : DiffUtil.ItemCallback<BillEntity>() {
        override fun areItemsTheSame(old: BillEntity, new: BillEntity) = old.id == new.id
        override fun areContentsTheSame(old: BillEntity, new: BillEntity) = old == new
    }
}
