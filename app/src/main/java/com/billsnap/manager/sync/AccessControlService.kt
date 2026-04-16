package com.billsnap.manager.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Service for validating Worker access to Admin bills.
 * Always checks Firestore as the single source of truth — no caching.
 *
 * Access is granted when:
 *  1. shopWorkers document exists for this worker
 *  2. status == "active"
 *  3. permissions.viewBills == true OR permissions.fullAccess == true
 */
object AccessControlService {

    private const val TAG = "AccessCtrl"
    private val firestore = FirebaseFirestore.getInstance()

    data class AccessResult(
        val granted: Boolean,
        val adminId: String? = null,
        val shopId: String? = null
    )

    /**
     * Validates whether a worker currently has bill-viewing access.
     * Logs every step for runtime diagnosis.
     */
    suspend fun validateAccess(workerId: String, shopId: String): AccessResult {
        Log.i(TAG, "── Validating access: worker=$workerId, shop=$shopId ──")

        return try {
            // 1. Get shop doc → find ownerId (the Admin)
            val shopDoc = firestore.collection("shops").document(shopId).get().await()
            if (!shopDoc.exists()) {
                Log.e(TAG, "  Shop doc '$shopId' does NOT exist in Firestore!")
                return AccessResult(granted = false)
            }

            val adminId = shopDoc.getString("ownerId")
            Log.i(TAG, "  Shop ownerId (adminId) = '$adminId'")

            if (adminId.isNullOrEmpty()) {
                Log.e(TAG, "  Shop '$shopId' has NULL/empty ownerId — cannot resolve admin!")
                return AccessResult(granted = false)
            }

            // 2. Get worker's document in shopWorkers
            val workerDocPath = "shops/$shopId/shopWorkers/$workerId"
            Log.d(TAG, "  Reading worker doc: $workerDocPath")
            val workerDoc = firestore.collection("shops").document(shopId)
                .collection("shopWorkers").document(workerId).get().await()

            if (!workerDoc.exists()) {
                Log.e(TAG, "  Worker doc NOT found at: $workerDocPath")
                return AccessResult(granted = false)
            }

            // 3. Check status
            val status = workerDoc.getString("status") ?: "pending"
            Log.i(TAG, "  Worker status = '$status'")
            if (status != "active") {
                Log.w(TAG, "  Worker NOT active (status='$status'), access DENIED")
                return AccessResult(granted = false, adminId = adminId, shopId = shopId)
            }

            // 4. Check permissions
            @Suppress("UNCHECKED_CAST")
            val permissions = workerDoc.get("permissions") as? Map<String, Boolean> ?: emptyMap()
            val hasFullAccess = permissions["fullAccess"] == true
            val hasViewBills = permissions["viewBills"] == true
            Log.i(TAG, "  Permissions: fullAccess=$hasFullAccess, viewBills=$hasViewBills, all=$permissions")

            val hasAccess = hasFullAccess || hasViewBills
            Log.i(TAG, "  Final access result: GRANTED=$hasAccess")

            AccessResult(granted = hasAccess, adminId = adminId, shopId = shopId)

        } catch (e: Exception) {
            Log.e(TAG, "  Access validation FAILED with exception: ${e.message}", e)
            AccessResult(granted = false)
        }
    }

    /**
     * Quick lookup of the shop owner (Admin) UID.
     */
    suspend fun getAdminId(shopId: String): String? {
        return try {
            val doc = firestore.collection("shops").document(shopId).get().await()
            doc.getString("ownerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get admin ID for shop $shopId: ${e.message}", e)
            null
        }
    }
}
