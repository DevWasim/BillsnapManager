package com.billsnap.manager.data

import androidx.lifecycle.LiveData

/**
 * Repository layer abstracting data source access.
 * All database operations are delegated to the DAO.
 */
class BillRepository(private val billDao: BillDao) {

    fun getAllBills(): LiveData<List<BillEntity>> = billDao.getAllBills()

    fun searchBills(query: String): LiveData<List<BillEntity>> = billDao.searchBills(query)

    suspend fun getBillById(id: Long): BillEntity? = billDao.getBillById(id)

    suspend fun isNameExists(name: String): Boolean = billDao.countByName(name) > 0

    suspend fun insert(bill: BillEntity): Long = billDao.insert(bill)

    suspend fun updatePaymentStatus(id: Long, status: String) =
        billDao.updatePaymentStatus(id, status)

    suspend fun updatePaymentStatusWithTimestamp(id: Long, status: String, paidTimestamp: Long?) =
        billDao.updatePaymentStatusWithTimestamp(id, status, paidTimestamp)

    suspend fun delete(bill: BillEntity) = billDao.delete(bill)

    suspend fun getSuggestions(query: String): List<String> = billDao.getSuggestions(query)

    suspend fun getAllBillsSync(): List<BillEntity> = billDao.getAllBillsSync()

    suspend fun searchBillsSync(query: String): List<BillEntity> = billDao.searchBillsSync(query)

    // --- Customer-related queries ---

    fun getBillsByCustomer(customerId: Long): LiveData<List<BillEntity>> =
        billDao.getBillsByCustomer(customerId)

    suspend fun getBillsByCustomerSync(customerId: Long): List<BillEntity> =
        billDao.getBillsByCustomerSync(customerId)

    fun getBillsByStatus(status: String): LiveData<List<BillEntity>> =
        billDao.getBillsByStatus(status)

    suspend fun getBillsByStatusSync(status: String): List<BillEntity> =
        billDao.getBillsByStatusSync(status)

    fun getBillsByDateRange(startDate: Long, endDate: Long): LiveData<List<BillEntity>> =
        billDao.getBillsByDateRange(startDate, endDate)

    suspend fun getBillsByDateRangeSync(startDate: Long, endDate: Long): List<BillEntity> =
        billDao.getBillsByDateRangeSync(startDate, endDate)

    suspend fun getUnpaidBillsSync(): List<BillEntity> = billDao.getUnpaidBillsSync()

    fun searchBillsAdvanced(
        query: String,
        status: String? = null,
        customerId: Long? = null,
        startDate: Long? = null,
        endDate: Long? = null
    ): LiveData<List<BillEntity>> =
        billDao.searchBillsAdvanced(query, System.currentTimeMillis(), status, customerId, startDate, endDate)

    suspend fun searchBillsAdvancedSync(
        query: String,
        status: String? = null,
        customerId: Long? = null,
        startDate: Long? = null,
        endDate: Long? = null
    ): List<BillEntity> =
        billDao.searchBillsAdvancedSync(query, System.currentTimeMillis(), status, customerId, startDate, endDate)

    suspend fun getOverdueBills(now: Long): List<BillEntity> = billDao.getOverdueBills(now)

    suspend fun markOverdueBills(now: Long) = billDao.markOverdueBills(now)

    // --- Dashboard stats ---
    suspend fun getTotalBillCount(): Int = billDao.getTotalBillCount()
    suspend fun getCountByStatus(status: String): Int = billDao.getCountByStatus(status)
    suspend fun getOverdueCount(now: Long): Int = billDao.getOverdueCount(now)
    suspend fun getDueSoonCount(now: Long, soon: Long): Int = billDao.getDueSoonCount(now, soon)
    suspend fun getTotalCustomerCount(): Int = billDao.getTotalCustomerCount()
    suspend fun getAllTimestamps(): List<Long> = billDao.getAllTimestamps()

    // --- Profile linking ---
    suspend fun updateCustomerId(billId: Long, customerId: Long) =
        billDao.updateCustomerId(billId, customerId)

    // --- Bulk operations ---
    suspend fun getBillsByIds(ids: List<Long>): List<BillEntity> = billDao.getBillsByIds(ids)
    suspend fun deleteAll(bills: List<BillEntity>) = billDao.deleteAll(bills)

    // --- OCR file path ---
    suspend fun updateOcrTextFilePath(billId: Long, path: String) =
        billDao.updateOcrTextFilePath(billId, path)

    // --- Payment tracking ---
    suspend fun updatePaymentAmounts(id: Long, paidAmount: Double, remainingAmount: Double, status: String, lastPaymentDate: Long?, paidTimestamp: Long?, paymentHistoryJson: String?) =
        billDao.updatePaymentAmounts(id, paidAmount, remainingAmount, status, lastPaymentDate, paidTimestamp, paymentHistoryJson)

    suspend fun updateBillAmounts(id: Long, totalAmount: Double, paidAmount: Double, remainingAmount: Double, status: String) =
        billDao.updateBillAmounts(id, totalAmount, paidAmount, remainingAmount, status)

    suspend fun getCustomerFinancialSummary(customerId: Long) =
        billDao.getCustomerFinancialSummary(customerId)

    suspend fun getTotalOutstanding() = billDao.getTotalOutstanding()

    suspend fun getCollectedInDateRange(startDate: Long, endDate: Long) =
        billDao.getCollectedInDateRange(startDate, endDate)

    // --- Financial Analytics ---
    fun getGlobalFinancialSummary(now: Long, creatorId: String? = null) = billDao.getGlobalFinancialSummary(now, creatorId)
    fun getMonthlyFinancialSummary(startEpoch: Long, endEpoch: Long, creatorId: String? = null) = billDao.getMonthlyFinancialSummary(startEpoch, endEpoch, creatorId)
    fun getPaymentStatusDistribution(creatorId: String? = null) = billDao.getPaymentStatusDistribution(creatorId)
    fun getTopCustomersByPaid(limit: Int = 5, creatorId: String? = null) = billDao.getTopCustomersByPaid(limit, creatorId)
    fun getTopCustomersByOutstanding(limit: Int = 5, creatorId: String? = null) = billDao.getTopCustomersByOutstanding(limit, creatorId)
    fun getDashboardCounts(now: Long, creatorId: String? = null) = billDao.getDashboardCounts(now, creatorId)
    fun getAllBillsFlow(creatorId: String? = null) = billDao.getAllBillsFlow(creatorId)
}
