package com.billsnap.manager.ui.workers

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.security.PermissionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WorkerLogsViewModel : ViewModel() {
    private val firestore = FirebaseFirestore.getInstance()

    private val _logs = MutableLiveData<List<ActivityLog>>()
    val logs: LiveData<List<ActivityLog>> = _logs

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadLogs(workerId: String) {
        val shopId = PermissionManager.session.value.shopId
        if (shopId.isNullOrEmpty()) {
            _error.value = "Shop ID not found"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val snapshot = firestore.collection("shops").document(shopId)
                    .collection("activity_logs")
                    .whereEqualTo("workerId", workerId)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .await()

                val logList = snapshot.documents.mapNotNull { doc ->
                    val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    ActivityLog(
                        id = doc.getString("id") ?: doc.id,
                        actionType = doc.getString("actionType") ?: "Unknown Action",
                        details = doc.getString("details") ?: "",
                        timestamp = timestamp
                    )
                }

                _logs.value = logList
            } catch (e: Exception) {
                Log.e("WorkerLogsViewModel", "Failed to load logs", e)
                _error.value = e.message ?: "Failed to load activity logs"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
