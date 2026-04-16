package com.billsnap.manager.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.R
import com.billsnap.manager.data.CustomerWithUnpaidCount
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import java.io.File

/**
 * RecyclerView adapter for the customer profile list.
 */
class CustomerAdapter(
    private val onItemClick: (CustomerWithUnpaidCount) -> Unit
) : ListAdapter<CustomerWithUnpaidCount, CustomerAdapter.CustomerViewHolder>(CustomerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return CustomerViewHolder(view)
    }

    override fun onBindViewHolder(holder: CustomerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CustomerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProfileImage: ImageView = itemView.findViewById(R.id.ivProfileImage)
        private val tvCustomerName: TextView = itemView.findViewById(R.id.tvCustomerName)
        private val tvPhone: TextView = itemView.findViewById(R.id.tvPhone)
        private val tvUnpaidBadge: TextView = itemView.findViewById(R.id.tvUnpaidBadge)

        fun bind(item: CustomerWithUnpaidCount) {
            val customer = item.customer
            tvCustomerName.text = customer.name

            // Phone
            if (customer.phoneNumber.isNotBlank()) {
                tvPhone.text = customer.phoneNumber
                tvPhone.visibility = View.VISIBLE
            } else {
                tvPhone.visibility = View.GONE
            }

            // Unpaid badge
            if (item.unpaidCount > 0) {
                tvUnpaidBadge.text = item.unpaidCount.toString()
                tvUnpaidBadge.visibility = View.VISIBLE
            } else {
                tvUnpaidBadge.visibility = View.GONE
            }

            // Profile image
            val imagePath = customer.profileImagePath
            if (imagePath.isNotBlank()) {
                val file = File(imagePath)
                Glide.with(itemView.context)
                    .load(if (file.exists()) file else null)
                    .transform(CenterCrop(), RoundedCorners(100))
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(ivProfileImage)
            } else {
                ivProfileImage.setImageResource(R.drawable.ic_person)
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    class CustomerDiffCallback : DiffUtil.ItemCallback<CustomerWithUnpaidCount>() {
        override fun areItemsTheSame(old: CustomerWithUnpaidCount, new: CustomerWithUnpaidCount) =
            old.customer.customerId == new.customer.customerId
        override fun areContentsTheSame(old: CustomerWithUnpaidCount, new: CustomerWithUnpaidCount) =
            old == new
    }
}
