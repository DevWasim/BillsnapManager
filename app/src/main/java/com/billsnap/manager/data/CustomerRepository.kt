package com.billsnap.manager.data

import androidx.lifecycle.LiveData

/**
 * Repository layer for customer data access.
 */
class CustomerRepository(private val customerDao: CustomerDao) {

    fun getAllCustomers(): LiveData<List<CustomerEntity>> = customerDao.getAllCustomers()

    suspend fun getAllCustomersSync(): List<CustomerEntity> = customerDao.getAllCustomersSync()

    suspend fun getCustomerById(id: Long): CustomerEntity? = customerDao.getCustomerById(id)

    fun getCustomerByIdLive(id: Long): LiveData<CustomerEntity?> = customerDao.getCustomerByIdLive(id)

    fun getCustomersWithUnpaidCount(): LiveData<List<CustomerWithUnpaidCount>> =
        customerDao.getCustomersWithUnpaidCount()

    fun searchCustomers(query: String): LiveData<List<CustomerEntity>> =
        customerDao.searchCustomers(query)

    suspend fun insert(customer: CustomerEntity): Long = customerDao.insert(customer)

    suspend fun update(customer: CustomerEntity) = customerDao.update(customer)

    suspend fun delete(customer: CustomerEntity) = customerDao.delete(customer)
}
