package com.billsnap.manager.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity mapping Admin→Worker bill access relationships.
 * Tracks whether a Worker has active access to an Admin's bills
 * and when the last successful sync occurred.
 *
 * Only one record per worker should exist (one worker → one shop/admin).
 */
@Entity(
    tableName = "worker_access",
    indices = [
        Index(value = ["admin_id", "worker_id"], unique = true)
    ]
)
data class WorkerAccessEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "admin_id")
    val adminId: String,

    @ColumnInfo(name = "worker_id")
    val workerId: String,

    @ColumnInfo(name = "shop_id")
    val shopId: String,

    @ColumnInfo(name = "access_status")
    val accessStatus: Boolean = false,

    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Long = 0L
)
