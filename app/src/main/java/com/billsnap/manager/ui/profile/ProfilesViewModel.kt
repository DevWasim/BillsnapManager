package com.billsnap.manager.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.billsnap.manager.data.CustomerRepository
import com.billsnap.manager.data.CustomerWithUnpaidCount

/**
 * ViewModel for the Profiles list screen.
 */
class ProfilesViewModel(private val repository: CustomerRepository) : ViewModel() {

    private val _searchQuery = MutableLiveData("")

    val customers: MediatorLiveData<List<CustomerWithUnpaidCount>> = MediatorLiveData()
    private var currentSource: LiveData<List<CustomerWithUnpaidCount>>? = null

    init {
        loadCustomers()
    }

    private fun loadCustomers() {
        currentSource?.let { customers.removeSource(it) }
        val source = repository.getCustomersWithUnpaidCount()
        currentSource = source
        customers.addSource(source) { list ->
            val query = _searchQuery.value ?: ""
            if (query.isBlank()) {
                customers.value = list
            } else {
                customers.value = list.filter {
                    it.customer.name.contains(query, ignoreCase = true)
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        // Re-filter current list
        val currentList = currentSource?.value ?: emptyList()
        if (query.isBlank()) {
            customers.value = currentList
        } else {
            customers.value = currentList.filter {
                it.customer.name.contains(query, ignoreCase = true)
            }
        }
    }
}
