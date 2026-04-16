package com.billsnap.manager.security

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSession(
    val uid: String = "",
    val role: String = "worker",
    val shopId: String? = null,
    val isApproved: Boolean = false,
    val permissions: Map<String, Boolean> = emptyMap(),
    val accessJustRestored: Boolean = false
) {
    fun hasPermission(perm: String): Boolean {
        if (role == "owner") return true
        if (permissions["fullAccess"] == true) return true
        return permissions[perm] == true
    }

    /** Whether this worker can view Admin bills (approved + viewBills or fullAccess). */
    val canViewBills: Boolean
        get() = isApproved && (hasPermission("viewBills") || hasPermission("fullAccess"))
}

object PermissionManager {
    private const val TAG = "PermissionManager"
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    
    private val _session = MutableStateFlow(UserSession())
    val session: StateFlow<UserSession> = _session.asStateFlow()

    /** Tracks the previous canViewBills state for transition detection */
    private var previousCanViewBills = false

    fun initialize() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _session.value = UserSession()
            return
        }

        // Listen to user document to get role & currentShopId
        firestore.collection("users").document(uid).addSnapshotListener { userDoc, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to user doc", error)
                return@addSnapshotListener
            }
            
            if (userDoc != null && userDoc.exists()) {
                val role = userDoc.getString("role") ?: "worker"
                val shopId = userDoc.getString("currentShopId")
                
                if (role == "owner") {
                    _session.value = UserSession(uid = uid, role = role, shopId = shopId, isApproved = true)
                } else if (shopId != null) {
                    // It's a worker, listen to their specific worker document in the shop
                    listenToWorkerPermissions(uid, shopId, role)
                } else {
                    _session.value = UserSession(uid = uid, role = role, shopId = null, isApproved = false)
                }
            }
        }
    }

    private fun listenToWorkerPermissions(uid: String, shopId: String, role: String) {
        firestore.collection("shops").document(shopId)
            .collection("shopWorkers").document(uid)
            .addSnapshotListener { workerDoc, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to worker doc", error)
                    return@addSnapshotListener
                }
                
                if (workerDoc != null && workerDoc.exists()) {
                    val status = workerDoc.getString("status") ?: "pending"
                    val isApproved = status == "active"
                    val perms = workerDoc.get("permissions") as? Map<String, Boolean> ?: emptyMap()
                    
                    val newSession = UserSession(
                        uid = uid,
                        role = role,
                        shopId = shopId,
                        isApproved = isApproved,
                        permissions = perms
                    )

                    // Detect access restoration: false → true transition
                    val restored = !previousCanViewBills && newSession.canViewBills
                    previousCanViewBills = newSession.canViewBills

                    _session.value = newSession.copy(accessJustRestored = restored)
                } else {
                    // Worker removed from shop or not found
                    previousCanViewBills = false
                    _session.value = UserSession(uid = uid, role = role, shopId = shopId, isApproved = false)
                }
            }
    }
    
    fun clear() {
        previousCanViewBills = false
        _session.value = UserSession()
    }
}
