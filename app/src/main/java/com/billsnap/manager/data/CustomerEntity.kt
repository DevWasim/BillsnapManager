package com.billsnap.manager.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a customer profile.
 */
@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "customer_id")
    val customerId: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String = "",

    @ColumnInfo(name = "details")
    val details: String = "",

    @ColumnInfo(name = "profile_image_path")
    val profileImagePath: String = "",

    @ColumnInfo(name = "created_timestamp")
    val createdTimestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_id")
    val syncId: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "shop_id")
    val shopId: String? = null,

    @ColumnInfo(name = "created_by")
    val createdBy: String? = null
)
