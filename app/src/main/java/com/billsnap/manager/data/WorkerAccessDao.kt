package com.billsnap.manager.data

import androidx.room.*

/**
 * DAO for worker access mappings.
 * Manages the local record of Admin→Worker bill access relationships.
 */
@Dao
interface WorkerAccessDao {

    @Query("SELECT * FROM worker_access WHERE worker_id = :workerId LIMIT 1")
    suspend fun getAccess(workerId: String): WorkerAccessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccess(entity: WorkerAccessEntity): Long

    @Query("UPDATE worker_access SET access_status = :status WHERE worker_id = :workerId")
    suspend fun updateAccessStatus(workerId: String, status: Boolean)

    @Query("UPDATE worker_access SET last_sync_timestamp = :timestamp WHERE worker_id = :workerId")
    suspend fun updateLastSyncTimestamp(workerId: String, timestamp: Long)

    @Query("DELETE FROM worker_access WHERE worker_id = :workerId")
    suspend fun deleteAccess(workerId: String)
}
