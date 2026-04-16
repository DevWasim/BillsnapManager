package com.billsnap.manager.security

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

object ActivityLogger {
    private const val TAG = "ActivityLogger"
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun logAppOpened() {
        logAction("App Opened", "User opened the application")
    }

    fun logLogin() {
        logAction("Login", "User successfully logged in")
    }

    fun logAction(actionType: String, details: String) {
        val uid = auth.currentUser?.uid ?: return
        val currentShopId = PermissionManager.session.value.shopId ?: return
        
        // Log to shops/{shopId}/activity_logs
        val logData = hashMapOf(
            "id" to UUID.randomUUID().toString(),
            "workerId" to uid,
            "actionType" to actionType,
            "details" to details,
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("shops").document(currentShopId)
            .collection("activity_logs").add(logData)
            .addOnSuccessListener {
                Log.d(TAG, "Logged activity: $actionType")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to log activity", e)
            }
            
        // Also update lastActive on the worker document
        if (PermissionManager.session.value.role == "worker") {
            firestore.collection("shops").document(currentShopId)
                .collection("shopWorkers").document(uid)
                .update("lastActive", FieldValue.serverTimestamp())
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update lastActive", e)
                }
        }
    }
}
