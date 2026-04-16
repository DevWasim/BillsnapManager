package com.billsnap.manager.ui.gallery

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.BillRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for the Gallery screen.
 * Manages live search filtering, advanced filters, and suggestions.
 */
class GalleryViewModel(private val repository: BillRepository) : ViewModel() {

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _suggestions = MutableLiveData<List<String>>(emptyList())
    val suggestions: LiveData<List<String>> = _suggestions

    val bills: MediatorLiveData<List<BillEntity>> = MediatorLiveData()
    private var currentSource: LiveData<List<BillEntity>>? = null

    var currentQuery: String = ""
        private set

    // Filter state
    var filterStatus: String? = null
        private set
    var filterCustomerId: Long? = null
        private set
    var filterStartDate: Long? = null
        private set
    var filterEndDate: Long? = null
        private set

    init {
        setSearchQuery("")
    }

    fun setSearchQuery(query: String) {
        currentQuery = query
        _searchQuery.value = query
        applyFilters()

        if (query.isNotBlank()) {
            viewModelScope.launch {
                _suggestions.value = repository.getSuggestions(query)
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun setFilters(status: String?, customerId: Long?, startDate: Long?, endDate: Long?) {
        filterStatus = status
        filterCustomerId = customerId
        filterStartDate = startDate
        filterEndDate = endDate
        applyFilters()
    }

    fun clearFilters() {
        filterStatus = null
        filterCustomerId = null
        filterStartDate = null
        filterEndDate = null
        applyFilters()
    }

    private fun applyFilters() {
        currentSource?.let { bills.removeSource(it) }

        val hasFilters = filterStatus != null || filterCustomerId != null ||
                filterStartDate != null || filterEndDate != null

        val newSource = if (!hasFilters && currentQuery.isBlank()) {
            repository.getAllBills()
        } else {
            repository.searchBillsAdvanced(
                query = currentQuery,
                status = filterStatus,
                customerId = filterCustomerId,
                startDate = filterStartDate,
                endDate = filterEndDate
            )
        }

        currentSource = newSource
        bills.addSource(newSource) { bills.value = it }
    }

    fun markAsPaid(billId: Long) {
        viewModelScope.launch {
            repository.updatePaymentStatusWithTimestamp(billId, "Paid", System.currentTimeMillis())
        }
    }

    fun deleteBill(bill: BillEntity) {
        viewModelScope.launch {
            repository.delete(bill)
        }
    }

    fun deleteBills(ids: Set<Long>) {
        viewModelScope.launch {
            val bills = repository.getBillsByIds(ids.toList())
            repository.deleteAll(bills)
        }
    }

    fun getBillsByIds(ids: Set<Long>, callback: (List<BillEntity>) -> Unit) {
        viewModelScope.launch {
            callback(repository.getBillsByIds(ids.toList()))
        }
    }

    suspend fun getUnpaidBills(): List<BillEntity> = repository.getUnpaidBillsSync()

    suspend fun getBillsByDateRange(start: Long, end: Long): List<BillEntity> =
        repository.getBillsByDateRangeSync(start, end)

    suspend fun getBillsByCustomer(customerId: Long): List<BillEntity> =
        repository.getBillsByCustomerSync(customerId)
}
