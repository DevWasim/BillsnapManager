package com.billsnap.manager.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.R
import com.billsnap.manager.data.CustomerContribution
import com.billsnap.manager.util.CurrencyManager

/**
 * Adapter for the top-customers leaderboard.
 * Shows ranked rows with total billed and paid amounts.
 */
class TopCustomersAdapter(
    private val onClick: (CustomerContribution) -> Unit
) : ListAdapter<CustomerContribution, TopCustomersAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_customer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvRank   = view.findViewById<TextView>(R.id.tvRank)
        private val tvName   = view.findViewById<TextView>(R.id.tvCustomerName)
        private val tvSub    = view.findViewById<TextView>(R.id.tvCustomerSub)
        private val tvCount  = view.findViewById<TextView>(R.id.tvBillCount)

        fun bind(item: CustomerContribution, rank: Int) {
            tvRank.text = rank.toString()
            tvName.text = item.customerName
            
            val paidStr = CurrencyManager.formatCompact(item.totalPaid)
            val outStr = CurrencyManager.formatCompact(item.totalOutstanding)
            tvSub.text  = "Paid: $paidStr | Outstanding: $outStr"
            tvCount.text = paidStr
            itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CustomerContribution>() {
            override fun areItemsTheSame(a: CustomerContribution, b: CustomerContribution) =
                a.customerId == b.customerId
            override fun areContentsTheSame(a: CustomerContribution, b: CustomerContribution) =
                a == b
        }
    }
}
