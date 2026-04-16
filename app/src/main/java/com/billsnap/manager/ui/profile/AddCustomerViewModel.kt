package com.billsnap.manager.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.CustomerEntity
import com.billsnap.manager.data.CustomerRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for adding or editing a customer profile.
 */
class AddCustomerViewModel(private val repository: CustomerRepository) : ViewModel() {

    sealed class SaveResult {
        data class Success(val customerId: Long) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    private val _isSaving = MutableLiveData(false)
    val isSaving: LiveData<Boolean> = _isSaving

    private val _customer = MutableLiveData<CustomerEntity?>()
    val customer: LiveData<CustomerEntity?> = _customer

    fun loadCustomer(id: Long) {
        viewModelScope.launch {
            _customer.value = repository.getCustomerById(id)
        }
    }

    fun saveCustomer(name: String, phone: String, details: String, imagePath: String?) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (name.isBlank()) {
                    _saveResult.value = SaveResult.Error("Name is required")
                    return@launch
                }
                
                if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("createCustomers")) {
                    _saveResult.value = SaveResult.Error("You do not have permission to create customers.")
                    return@launch
                }

                val customer = CustomerEntity(
                    name = name,
                    phoneNumber = phone,
                    details = details,
                    profileImagePath = imagePath ?: ""
                )
                val id = repository.insert(customer)
                com.billsnap.manager.security.ActivityLogger.logAction("Customer Created", "Created customer: $name")
                _saveResult.value = SaveResult.Success(id)
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Failed to save customer")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateCustomer(customerId: Long, name: String, phone: String, details: String, currentImagePath: String, newImagePath: String?) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (name.isBlank()) {
                    _saveResult.value = SaveResult.Error("Name is required")
                    return@launch
                }

                if (!com.billsnap.manager.security.PermissionManager.session.value.hasPermission("editCustomers")) {
                    _saveResult.value = SaveResult.Error("You do not have permission to edit customers.")
                    return@launch
                }

                val existing = repository.getCustomerById(customerId) ?: throw Exception("Customer not found")
                val updated = existing.copy(
                    name = name,
                    phoneNumber = phone,
                    details = details,
                    profileImagePath = newImagePath ?: currentImagePath
                )
                repository.update(updated)
                com.billsnap.manager.security.ActivityLogger.logAction("Customer Updated", "Updated customer: $name")
                _saveResult.value = SaveResult.Success(customerId)
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Unknown error")
            } finally {
                _isSaving.value = false
            }
        }
    }
}
