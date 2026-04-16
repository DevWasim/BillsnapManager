package com.billsnap.manager.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.billsnap.manager.R
import com.billsnap.manager.data.BillEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact adapter for showing bills within the month detail panel.
 * Displays a horizontal compact row with thumbnail, name, status chip, and date.
 */
class MonthBillAdapter(
    private val onItemClick: (BillEntity) -> Unit
) : ListAdapter<BillEntity, MonthBillAdapter.MonthBillViewHolder>(MonthBillDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthBillViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_month_bill, parent, false)
        return MonthBillViewHolder(view)
    }

    override fun onBindViewHolder(holder: MonthBillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MonthBillViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivMonthBillThumb)
        private val tvName: TextView = itemView.findViewById(R.id.tvMonthBillName)
        private val tvDate: TextView = itemView.findViewById(R.id.tvMonthBillDate)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvMonthBillStatus)
        private val viewStatusStripe: View = itemView.findViewById(R.id.viewStatusStripe)

        fun bind(bill: BillEntity) {
            tvName.text = bill.customName
            tvDate.text = dateFormat.format(Date(bill.timestamp))

            // Status logic
            val status = bill.paymentStatus ?: "Unpaid"
            tvStatus.text = status
            
            // Map text color based on Material colors
            val statusColor = when (status) {
                "Paid" -> R.color.status_paid
                "Partial", "Due Soon" -> R.color.status_due_soon
                else -> R.color.status_unpaid
            }
            tvStatus.setTextColor(ContextCompat.getColor(itemView.context, statusColor))

            // Set 3dp vertical indicator stripe background color
            viewStatusStripe.setBackgroundColor(
                ContextCompat.getColor(itemView.context, statusColor)
            )

            // Thumbnail loading via Coil with rounding and crossfade
            val file = File(bill.imagePath)
            ivThumb.load(if (file.exists()) file else R.drawable.ic_placeholder) {
                crossfade(true)
                placeholder(R.drawable.ic_placeholder)
                transformations(RoundedCornersTransformation(16f))
            }

            itemView.setOnClickListener {
                // Scale animation (0.98 -> 1f)
                itemView.animate()
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(70)
                    .withEndAction {
                        itemView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(70)
                            .withEndAction {
                                onItemClick(bill)
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    class MonthBillDiffCallback : DiffUtil.ItemCallback<BillEntity>() {
        override fun areItemsTheSame(a: BillEntity, b: BillEntity) = a.id == b.id
        override fun areContentsTheSame(a: BillEntity, b: BillEntity) = a == b
    }
}
