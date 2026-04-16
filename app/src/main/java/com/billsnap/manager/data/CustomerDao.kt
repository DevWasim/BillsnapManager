package com.billsnap.manager.data

import androidx.lifecycle.LiveData
import androidx.room.*

/**
 * Data Access Object for customer operations.
 */
@Dao
interface CustomerDao {

    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): LiveData<List<CustomerEntity>>

    @Query("SELECT customer_id, name FROM customers")
    suspend fun getCustomerNamesAndIds(): List<CustomerNameIdPair>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomersSync(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE customer_id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE customer_id = :id")
    fun getCustomerByIdLive(id: Long): LiveData<CustomerEntity?>

    @Query("""
        SELECT c.*, 
        (SELECT COUNT(*) FROM bills b WHERE b.customer_id = c.customer_id AND b.payment_status != 'Paid') AS unpaid_count
        FROM customers c 
        ORDER BY c.name ASC
    """)
    fun getCustomersWithUnpaidCount(): LiveData<List<CustomerWithUnpaidCount>>

    @Query("SELECT * FROM customers WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%' ORDER BY name ASC")
    fun searchCustomers(query: String): LiveData<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity): Long

    @Update
    suspend fun update(customer: CustomerEntity)

    @Delete
    suspend fun delete(customer: CustomerEntity)

    // --- Cloud sync queries ---

    @Query("SELECT * FROM customers WHERE sync_id = :syncId LIMIT 1")
    suspend fun getCustomerBySyncId(syncId: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(customer: CustomerEntity): Long

    @Query("UPDATE customers SET sync_id = :syncId WHERE customer_id = :id")
    suspend fun updateSyncId(id: Long, syncId: String)

    @Query("UPDATE customers SET drive_file_id = :driveFileId WHERE customer_id = :id")
    suspend fun updateDriveFileId(id: Long, driveFileId: String)
}

/**
 * Data class for customer with unpaid bill count (used in profile list).
 */
data class CustomerWithUnpaidCount(
    @Embedded val customer: CustomerEntity,
    @ColumnInfo(name = "unpaid_count") val unpaidCount: Int
)

/**
 * Lightweight data class for fuzzy matching customer names.
 */
data class CustomerNameIdPair(
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "name") val name: String
)
