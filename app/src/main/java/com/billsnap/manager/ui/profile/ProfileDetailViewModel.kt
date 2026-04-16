package com.billsnap.manager.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.BillRepository
import com.billsnap.manager.data.CustomerEntity
import com.billsnap.manager.data.CustomerRepository
import com.billsnap.manager.data.CustomerFinancialSummary
import kotlinx.coroutines.launch

/**
 * ViewModel for the Profile Detail screen.
 * Loads customer info and their linked bills.
 * Supports profile deletion.
 */
class ProfileDetailViewModel(
    private val billRepository: BillRepository,
    private val customerRepository: CustomerRepository
) : ViewModel() {

    private val _customer = MutableLiveData<CustomerEntity?>()
    val customer: LiveData<CustomerEntity?> = _customer

    private val _bills = MutableLiveData<List<BillEntity>>(emptyList())
    val bills: LiveData<List<BillEntity>> = _bills

    private val _deleteResult = MutableLiveData<Boolean>()
    val deleteResult: LiveData<Boolean> = _deleteResult

    private val _financialSummary = MutableLiveData<CustomerFinancialSummary?>()
    val financialSummary: LiveData<CustomerFinancialSummary?> = _financialSummary

    private var customerId: Long = 0

    fun loadCustomer(id: Long) {
        customerId = id
        viewModelScope.launch {
            _customer.value = customerRepository.getCustomerById(id)
        }
        // Observe bills for this customer
        billRepository.getBillsByCustomer(id).observeForever { billList ->
            _bills.value = billList
        }
        // Load financial summary using efficient aggregate query
        viewModelScope.launch {
            _financialSummary.value = billRepository.getCustomerFinancialSummary(id)
        }
    }

    fun deleteCustomer() {
        viewModelScope.launch {
            try {
                val c = _customer.value ?: return@launch
                customerRepository.delete(c)
                _deleteResult.value = true
            } catch (e: Exception) {
                _deleteResult.value = false
            }
        }
    }

    fun getBillCount(): Int = _bills.value?.size ?: 0
}
