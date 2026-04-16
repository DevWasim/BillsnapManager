package com.billsnap.manager.data

import androidx.lifecycle.LiveData
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for bill operations.
 * Provides both LiveData (observable) and suspend (one-shot) query variants.
 */
@Dao
interface BillDao {

    @Query("SELECT * FROM bills ORDER BY timestamp DESC")
    fun getAllBills(): LiveData<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY timestamp DESC")
    suspend fun getAllBillsSync(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE LOWER(custom_name) LIKE '%' || LOWER(:query) || '%' ORDER BY timestamp DESC")
    fun searchBills(query: String): LiveData<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: Long): BillEntity?

    @Query("SELECT COUNT(*) FROM bills WHERE LOWER(custom_name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bill: BillEntity): Long

    @Query("UPDATE bills SET payment_status = :status WHERE id = :id")
    suspend fun updatePaymentStatus(id: Long, status: String)

    @Query("UPDATE bills SET payment_status = :status, paid_timestamp = :paidTimestamp WHERE id = :id")
    suspend fun updatePaymentStatusWithTimestamp(id: Long, status: String, paidTimestamp: Long?)

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("SELECT custom_name FROM bills WHERE LOWER(custom_name) LIKE '%' || LOWER(:query) || '%' ORDER BY custom_name ASC LIMIT 10")
    suspend fun getSuggestions(query: String): List<String>



    @Query("SELECT * FROM bills WHERE LOWER(custom_name) LIKE '%' || LOWER(:query) || '%' ORDER BY timestamp DESC")
    suspend fun searchBillsSync(query: String): List<BillEntity>

    // --- New queries for customer profiles ---

    @Query("SELECT * FROM bills WHERE customer_id = :customerId ORDER BY timestamp DESC")
    fun getBillsByCustomer(customerId: Long): LiveData<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE customer_id = :customerId ORDER BY timestamp DESC")
    suspend fun getBillsByCustomerSync(customerId: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE customer_id = :customerId AND payment_status = 'Paid' AND paid_timestamp IS NOT NULL ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentPaidBillsForCustomerSync(customerId: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE payment_status = :status ORDER BY timestamp DESC")
    fun getBillsByStatus(status: String): LiveData<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE payment_status = :status ORDER BY timestamp DESC")
    suspend fun getBillsByStatusSync(status: String): List<BillEntity>

    @Query("SELECT * FROM bills WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp DESC")
    fun getBillsByDateRange(startDate: Long, endDate: Long): LiveData<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp DESC")
    suspend fun getBillsByDateRangeSync(startDate: Long, endDate: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE payment_status != 'Paid' ORDER BY timestamp DESC")
    suspend fun getUnpaidBillsSync(): List<BillEntity>

    @Query("""
        SELECT * FROM bills 
        WHERE (LOWER(custom_name) LIKE '%' || LOWER(:query) || '%' 
               OR LOWER(notes) LIKE '%' || LOWER(:query) || '%')
        AND (:status IS NULL 
             OR payment_status = :status 
             OR (:status = 'Overdue' AND payment_status = 'Unpaid' AND reminder_datetime IS NOT NULL AND reminder_datetime <= :now))
        AND (:customerId IS NULL OR customer_id = :customerId)
        AND (:startDate IS NULL OR timestamp >= :startDate)
        AND (:endDate IS NULL OR timestamp <= :endDate)
        ORDER BY timestamp DESC
    """)
    fun searchBillsAdvanced(
        query: String,
        now: Long,
        status: String? = null,
        customerId: Long? = null,
        startDate: Long? = null,
        endDate: Long? = null
    ): LiveData<List<BillEntity>>

    @Query("""
        SELECT * FROM bills 
        WHERE (LOWER(custom_name) LIKE '%' || LOWER(:query) || '%' 
               OR LOWER(notes) LIKE '%' || LOWER(:query) || '%')
        AND (:status IS NULL 
             OR payment_status = :status 
             OR (:status = 'Overdue' AND payment_status = 'Unpaid' AND reminder_datetime IS NOT NULL AND reminder_datetime <= :now))
        AND (:customerId IS NULL OR customer_id = :customerId)
        AND (:startDate IS NULL OR timestamp >= :startDate)
        AND (:endDate IS NULL OR timestamp <= :endDate)
        ORDER BY timestamp DESC
    """)
    suspend fun searchBillsAdvancedSync(
        query: String,
        now: Long,
        status: String? = null,
        customerId: Long? = null,
        startDate: Long? = null,
        endDate: Long? = null
    ): List<BillEntity>

    @Query("SELECT * FROM bills WHERE reminder_datetime IS NOT NULL AND reminder_datetime <= :now AND payment_status != 'Paid'")
    suspend fun getOverdueBills(now: Long): List<BillEntity>

    @Query("UPDATE bills SET payment_status = 'Overdue' WHERE reminder_datetime IS NOT NULL AND reminder_datetime <= :now AND payment_status = 'Unpaid'")
    suspend fun markOverdueBills(now: Long): Int

    // --- Dashboard stats ---

    @Query("SELECT COUNT(*) FROM bills")
    suspend fun getTotalBillCount(): Int

    @Query("SELECT COUNT(*) FROM bills WHERE payment_status = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM bills WHERE payment_status != 'Paid' AND reminder_datetime IS NOT NULL AND reminder_datetime <= :now")
    suspend fun getOverdueCount(now: Long): Int

    @Query("SELECT COUNT(*) FROM bills WHERE payment_status != 'Paid' AND reminder_datetime IS NOT NULL AND reminder_datetime > :now AND reminder_datetime <= :soon")
    suspend fun getDueSoonCount(now: Long, soon: Long): Int

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun getTotalCustomerCount(): Int

    @Query("SELECT timestamp FROM bills ORDER BY timestamp ASC")
    suspend fun getAllTimestamps(): List<Long>

    @Query("SELECT * FROM bills WHERE id IN (:ids)")
    suspend fun getBillsByIds(ids: List<Long>): List<BillEntity>

    @Delete
    suspend fun deleteAll(bills: List<BillEntity>)

    // --- Profile linking ---

    @Query("UPDATE bills SET customer_id = :customerId WHERE id = :billId")
    suspend fun updateCustomerId(billId: Long, customerId: Long)

    // --- Cloud sync queries ---

    @Query("SELECT * FROM bills WHERE sync_id = :syncId LIMIT 1")
    suspend fun getBillBySyncId(syncId: String): BillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bill: BillEntity): Long

    @Query("UPDATE bills SET sync_id = :syncId WHERE id = :id")
    suspend fun updateSyncId(id: Long, syncId: String)

    @Query("UPDATE bills SET drive_file_id = :driveFileId WHERE id = :id")
    suspend fun updateDriveFileId(id: Long, driveFileId: String)

    @Query("UPDATE bills SET ocr_text_file_path = :path WHERE id = :id")
    suspend fun updateOcrTextFilePath(id: Long, path: String)

    // --- Payment tracking queries ---

    /**
     * Atomically updates payment amounts and derived status for a bill.
     * remainingAmount must always equal totalAmount - paidAmount.
     */
    @Query("UPDATE bills SET paid_amount = :paidAmount, remaining_amount = :remainingAmount, payment_status = :status, last_payment_date = :lastPaymentDate, paid_timestamp = :paidTimestamp, payment_history_json = :paymentHistoryJson WHERE id = :id")
    suspend fun updatePaymentAmounts(id: Long, paidAmount: Double, remainingAmount: Double, status: String, lastPaymentDate: Long?, paidTimestamp: Long?, paymentHistoryJson: String?)

    @Query("UPDATE bills SET total_amount = :totalAmount, paid_amount = :paidAmount, remaining_amount = :remainingAmount, payment_status = :status WHERE id = :id")
    suspend fun updateBillAmounts(id: Long, totalAmount: Double, paidAmount: Double, remainingAmount: Double, status: String)

    /**
     * Aggregates financial data for a specific customer.
     * Uses indexed customer_id and remaining_amount columns for efficiency.
     */
    @Query("SELECT COALESCE(SUM(total_amount), 0) as totalBilled, COALESCE(SUM(paid_amount), 0) as totalPaid, COALESCE(SUM(remaining_amount), 0) as totalRemaining FROM bills WHERE customer_id = :customerId")
    suspend fun getCustomerFinancialSummary(customerId: Long): CustomerFinancialSummary

    /** Global outstanding amount across all bills. */
    @Query("SELECT COALESCE(SUM(remaining_amount), 0) FROM bills")
    suspend fun getTotalOutstanding(): Double

    /** Total paid amount within a date range (for "collected this month"). */
    @Query("SELECT COALESCE(SUM(paid_amount), 0) FROM bills WHERE last_payment_date BETWEEN :startDate AND :endDate")
    suspend fun getCollectedInDateRange(startDate: Long, endDate: Long): Double

    // ════════════════════════════════════════════════════════════════
    // FINANCIAL ANALYTICS (DASHBOARD EXPERT AGGREGATIONS)
    // ════════════════════════════════════════════════════════════════

    /** 
     * 1. Global Financial Summary
     * Dynamically sums all bills. Filters by creator if a worker ID is specified.
     */
    @Query("""
        SELECT 
            COALESCE(SUM(total_amount), 0.0) as totalBilled,
            COALESCE(SUM(paid_amount), 0.0) as totalPaid,
            COALESCE(SUM(remaining_amount), 0.0) as totalOutstanding,
            COALESCE(SUM(CASE WHEN payment_status = 'Overdue' OR (payment_status = 'Unpaid' AND reminder_datetime IS NOT NULL AND reminder_datetime <= :now) THEN remaining_amount ELSE 0.0 END), 0.0) as overdueOutstanding
        FROM bills 
        WHERE (:creatorId IS NULL OR created_by = :creatorId)
    """)
    fun getGlobalFinancialSummary(now: Long, creatorId: String? = null): Flow<GlobalFinancialSummary>

    /**
     * 2. Monthly Financial Summary (For current month analytics)
     */
    @Query("""
        SELECT 
            COALESCE(SUM(total_amount), 0.0) as totalBilled,
            COALESCE(SUM(paid_amount), 0.0) as totalPaid,
            COALESCE(SUM(remaining_amount), 0.0) as totalOutstanding,
            COALESCE(SUM(CASE WHEN payment_status = 'Overdue' THEN remaining_amount ELSE 0.0 END), 0.0) as overdueOutstanding
        FROM bills 
        WHERE timestamp >= :startEpoch AND timestamp <= :endEpoch
        AND (:creatorId IS NULL OR created_by = :creatorId)
    """)
    fun getMonthlyFinancialSummary(startEpoch: Long, endEpoch: Long, creatorId: String? = null): Flow<GlobalFinancialSummary>

    /**
     * 3. Payment Status Distribution (Donut Chart)
     */
    @Query("""
        SELECT payment_status as status, COUNT(*) as count 
        FROM bills 
        WHERE payment_status IN ('Paid', 'Unpaid', 'Overdue', 'Partial')
        AND (:creatorId IS NULL OR created_by = :creatorId)
        GROUP BY payment_status
    """)
    fun getPaymentStatusDistribution(creatorId: String? = null): Flow<List<StatusDistribution>>

    /**
     * 4. Top Customers By Revenue Contribution
     */
    @Query("""
        SELECT 
            c.customer_id as customerId,
            c.name as customerName,
            COALESCE(SUM(b.total_amount), 0.0) as totalBilled,
            COALESCE(SUM(b.paid_amount), 0.0) as totalPaid,
            COALESCE(SUM(b.remaining_amount), 0.0) as totalOutstanding
        FROM bills b
        JOIN customers c ON b.customer_id = c.customer_id
        WHERE (:creatorId IS NULL OR b.created_by = :creatorId)
        GROUP BY c.customer_id, c.name
        ORDER BY totalPaid DESC
        LIMIT :limit
    """)
    fun getTopCustomersByPaid(limit: Int = 5, creatorId: String? = null): Flow<List<CustomerContribution>>

    @Query("""
        SELECT 
            c.customer_id as customerId,
            c.name as customerName,
            COALESCE(SUM(b.total_amount), 0.0) as totalBilled,
            COALESCE(SUM(b.paid_amount), 0.0) as totalPaid,
            COALESCE(SUM(b.remaining_amount), 0.0) as totalOutstanding
        FROM bills b
        JOIN customers c ON b.customer_id = c.customer_id
        WHERE (:creatorId IS NULL OR b.created_by = :creatorId)
        GROUP BY c.customer_id, c.name
        ORDER BY totalOutstanding DESC
        LIMIT :limit
    """)
    fun getTopCustomersByOutstanding(limit: Int = 5, creatorId: String? = null): Flow<List<CustomerContribution>>
    
    /**
     * 5. Lightweight reactive counts for Dashboard Top row
     */
    @Query("""
        SELECT 
            COUNT(*) as totalBills,
            COUNT(DISTINCT customer_id) as totalCustomers,
            SUM(CASE WHEN payment_status = 'Paid' THEN 1 ELSE 0 END) as paidCount,
            SUM(CASE WHEN payment_status = 'Unpaid' THEN 1 ELSE 0 END) as unpaidCount,
            SUM(CASE WHEN payment_status = 'Overdue' OR (payment_status = 'Unpaid' AND reminder_datetime IS NOT NULL AND reminder_datetime <= :now) THEN 1 ELSE 0 END) as overdueCount
        FROM bills
        WHERE (:creatorId IS NULL OR created_by = :creatorId)
    """)
    fun getDashboardCounts(now: Long, creatorId: String? = null): Flow<DashboardCounts>

    /**
     * 6. Monthly Data points (for Bar Chart). 
     * Room SQLite doesn't natively support robust String formatting for date groupings trivially across APIs,
     * so it's generally best to query raw bills within a bounds or let the Viewmodel chunk them by month if data isn't huge.
     * We will keep the ViewModel chunker for BarChartView backwards compatibility, but expose a Flow.
     */
    @Query("SELECT * FROM bills WHERE (:creatorId IS NULL OR created_by = :creatorId) ORDER BY timestamp ASC")
    fun getAllBillsFlow(creatorId: String? = null): Flow<List<BillEntity>>
}

/**
 * Lightweight projection for customer financial aggregation.
 * Used by ProfileDetailViewModel to display the financial summary card.
 */
data class CustomerFinancialSummary(
    val totalBilled: Double,
    val totalPaid: Double,
    val totalRemaining: Double
)

data class GlobalFinancialSummary(
    val totalBilled: Double,
    val totalPaid: Double,
    val totalOutstanding: Double,
    val overdueOutstanding: Double
) {
    val collectionRate: Double
        get() = if (totalBilled > 0) (totalPaid / totalBilled) * 100.0 else 0.0
}

data class StatusDistribution(
    val status: String,
    val count: Int
)

data class CustomerContribution(
    val customerId: Long,
    val customerName: String,
    val totalBilled: Double,
    val totalPaid: Double,
    val totalOutstanding: Double
)

data class DashboardCounts(
    val totalBills: Int,
    val totalCustomers: Int,
    val paidCount: Int,
    val unpaidCount: Int,
    val overdueCount: Int
)
