package com.billsnap.manager.sync

import android.content.Context
import android.util.Log
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * DETERMINISTIC MINIMAL bill sync.
 *
 * Single entry point: fullSyncForWorker(workerId, shopId)
 *
 * Checkpoints (each logged with counts):
 *  FIRESTORE_STRUCTURE_VERIFIED  — shop doc exists, ownerId resolved
 *  WORKER_ACCESS_VERIFIED        — shopWorkers doc exists, status=active, viewBills=true
 *  FIRESTORE_DOC_COUNT           — bill documents fetched
 *  MAPPED_ENTITY_COUNT           — BillEntity list ready
 *  ROOM_INSERT_COUNT             — rows inserted
 *  ROOM_QUERY_COUNT              — rows read back from Room
 */
class BillSyncRepository(private val context: Context) {

    companion object {
        private const val TAG = "BillSync"

        @Volatile
        private var instance: BillSyncRepository? = null

        fun getInstance(context: Context): BillSyncRepository {
            return instance ?: synchronized(this) {
                instance ?: BillSyncRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val db = AppDatabase.getInstance(context)
    private val firestore = FirebaseFirestore.getInstance()

    data class SyncResult(
        val resolvedAdminId: String,
        val firestorePathUsed: String,
        val firestoreDocumentCount: Int,
        val mappedEntityCount: Int,
        val roomInsertCount: Int,
        val roomQueryCount: Int
    )

    class SyncException(
        val checkpoint: String,
        message: String,
        cause: Throwable? = null
    ) : Exception("[$checkpoint] $message", cause)

    /**
     * Complete deterministic sync pipeline.
     */
    suspend fun fullSyncForWorker(workerId: String, shopId: String): SyncResult =
        withContext(Dispatchers.IO) {

        Log.i(TAG, "╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  DETERMINISTIC SYNC  worker=$workerId    ║")
        Log.i(TAG, "║  shopId=$shopId")
        Log.i(TAG, "╠══════════════════════════════════════════╣")

        // ─── CHECKPOINT: FIRESTORE_STRUCTURE_VERIFIED ───────────
        Log.i(TAG, "║ [FIRESTORE_STRUCTURE_VERIFIED] Reading shops/$shopId")

        val shopDoc = try {
            firestore.collection("shops").document(shopId).get().await()
        } catch (e: Exception) {
            throw SyncException("FIRESTORE_STRUCTURE_VERIFIED",
                "Cannot read shop doc: ${e.message}", e)
        }

        if (!shopDoc.exists()) {
            throw SyncException("FIRESTORE_STRUCTURE_VERIFIED",
                "Shop '$shopId' does NOT exist")
        }

        val shopData = shopDoc.data
        val resolvedAdminId = shopDoc.getString("ownerId")
        Log.i(TAG, "║   Shop data: $shopData")
        Log.i(TAG, "║   ADMIN_ID_RESOLVED = '$resolvedAdminId'")

        if (resolvedAdminId.isNullOrEmpty()) {
            throw SyncException("FIRESTORE_STRUCTURE_VERIFIED",
                "ownerId is null/empty. Shop data: $shopData")
        }
        Log.i(TAG, "║ ✓ FIRESTORE_STRUCTURE_VERIFIED")

        // ─── CHECKPOINT: WORKER_ACCESS_VERIFIED ─────────────────
        val workerDocPath = "shops/$shopId/shopWorkers/$workerId"
        Log.i(TAG, "║ [WORKER_ACCESS_VERIFIED] Reading $workerDocPath")

        val workerDoc = try {
            firestore.collection("shops").document(shopId)
                .collection("shopWorkers").document(workerId).get().await()
        } catch (e: Exception) {
            throw SyncException("WORKER_ACCESS_VERIFIED",
                "Cannot read worker doc: ${e.message}", e)
        }

        if (!workerDoc.exists()) {
            throw SyncException("WORKER_ACCESS_VERIFIED",
                "Worker doc NOT found at $workerDocPath")
        }

        val workerData = workerDoc.data
        val workerStatus = workerDoc.getString("status") ?: "unknown"
        @Suppress("UNCHECKED_CAST")
        val permissions = workerDoc.get("permissions") as? Map<String, Boolean> ?: emptyMap()
        val hasViewBills = permissions["viewBills"] == true
        val hasFullAccess = permissions["fullAccess"] == true

        Log.i(TAG, "║   Worker data: $workerData")
        Log.i(TAG, "║   status=$workerStatus, viewBills=$hasViewBills, fullAccess=$hasFullAccess")

        if (workerStatus != "active") {
            throw SyncException("WORKER_ACCESS_VERIFIED",
                "Worker status='$workerStatus' (must be 'active')")
        }

        if (!hasViewBills && !hasFullAccess) {
            throw SyncException("WORKER_ACCESS_VERIFIED",
                "Worker has NEITHER viewBills NOR fullAccess permission. " +
                "Admin must enable viewBills in Worker settings. " +
                "Current permissions: $permissions")
        }

        Log.i(TAG, "║ ✓ WORKER_ACCESS_VERIFIED")

        // ─── CHECKPOINT: FIRESTORE_DOC_COUNT ────────────────────
        val billsPath = "shops/$shopId/bills"
        Log.i(TAG, "║ [FIRESTORE_DOC_COUNT] Querying $billsPath (NO filters)")

        val billsSnapshot = try {
            firestore.collection("shops").document(shopId)
                .collection("bills").get().await()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                msg.contains("Missing or insufficient permissions", ignoreCase = true)) {
                throw SyncException("SECURITY_RULES_BLOCKED",
                    "READ BLOCKED BY SECURITY RULES at '$billsPath'. " +
                    "Worker $workerId cannot read bills even though permissions appear correct. " +
                    "Check Firestore security rules: need viewBills=true AND status=active. " +
                    "Error: $msg", e)
            }
            throw SyncException("FIRESTORE_DOC_COUNT",
                "Query failed at '$billsPath': ${e.message}", e)
        }

        val firestoreDocumentCount = billsSnapshot.documents.size
        Log.i(TAG, "║   FIRESTORE_DOC_COUNT = $firestoreDocumentCount from $billsPath")

        if (firestoreDocumentCount == 0) {
            Log.w(TAG, "║   ⚠ ZERO bills! Reasons could be:")
            Log.w(TAG, "║     1. Admin has not uploaded any bills yet")
            Log.w(TAG, "║     2. Bills are at a different path")
            Log.w(TAG, "║     3. Security rules silently blocking (rare)")
            // Return zero result — this is valid (admin might have no bills)
            return@withContext SyncResult(
                resolvedAdminId = resolvedAdminId,
                firestorePathUsed = billsPath,
                firestoreDocumentCount = 0,
                mappedEntityCount = 0,
                roomInsertCount = 0,
                roomQueryCount = db.billDao().getTotalBillCount()
            )
        }

        // Log sample docs
        billsSnapshot.documents.take(3).forEach { doc ->
            Log.i(TAG, "║   Sample bill: id=${doc.id}, name=${doc.getString("custom_name")}, " +
                    "syncId=${doc.getString("sync_id")}")
        }
        Log.i(TAG, "║ ✓ FIRESTORE_DOC_COUNT = $firestoreDocumentCount")

        // ─── CHECKPOINT: MAPPED_ENTITY_COUNT ────────────────────
        Log.i(TAG, "║ [MAPPED_ENTITY_COUNT] Mapping docs to BillEntity")

        val mappedBills = mutableListOf<BillEntity>()
        val usedNames = mutableSetOf<String>()

        for (doc in billsSnapshot.documents) {
            val syncId = doc.getString("sync_id") ?: doc.id
            val rawName = doc.getString("custom_name") ?: "Bill_${syncId.take(8)}"

            // Ensure unique name (custom_name has UNIQUE index)
            var finalName = rawName
            var suffix = 1
            while (usedNames.contains(finalName.lowercase())) {
                finalName = "${rawName}_${suffix}"
                suffix++
            }
            usedNames.add(finalName.lowercase())

            mappedBills.add(BillEntity(
                customName = finalName,
                notes = doc.getString("notes") ?: "",
                imagePath = "",  // Skip image download — placeholder shows
                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                paymentStatus = doc.getString("payment_status") ?: "Unpaid",
                customerId = null,
                syncId = syncId,
                driveFileId = doc.getString("drive_file_id"),
                shopId = doc.getString("shop_id") ?: shopId,
                createdBy = doc.getString("created_by"),
                isSmartProcessed = doc.getBoolean("is_smart_processed") ?: false,
                smartOcrJson = doc.getString("smart_ocr_json"),
                lateRiskScore = doc.getDouble("late_risk_score")?.toFloat(),
                paidAmount = doc.getDouble("paid_amount") ?: 0.0,
                remainingAmount = doc.getDouble("remaining_amount") ?: 0.0,
                lastPaymentDate = doc.getLong("last_payment_date"),
                optimizedImagePath = null
            ))
        }

        val mappedEntityCount = mappedBills.size
        Log.i(TAG, "║   MAPPED_ENTITY_COUNT = $mappedEntityCount")
        Log.i(TAG, "║ ✓ MAPPED_ENTITY_COUNT")

        // ─── CHECKPOINT: ROOM_INSERT_COUNT ──────────────────────
        Log.i(TAG, "║ [ROOM_INSERT_COUNT] Clearing table + inserting $mappedEntityCount bills")

        var roomInsertCount = 0
        try {
            // Clear all existing bills first
            val allExisting = db.billDao().getAllBillsSync()
            if (allExisting.isNotEmpty()) {
                db.billDao().deleteAll(allExisting)
                Log.i(TAG, "║   Cleared ${allExisting.size} existing bills from Room")
            }

            // Insert all mapped bills
            for (bill in mappedBills) {
                try {
                    db.billDao().insert(bill)
                    roomInsertCount++
                } catch (e: Exception) {
                    Log.e(TAG, "║   Insert FAILED for '${bill.customName}': ${e.message}")
                }
            }
        } catch (e: Exception) {
            throw SyncException("ROOM_INSERT_COUNT",
                "Room operations failed: ${e.message}", e)
        }

        Log.i(TAG, "║   ROOM_INSERT_COUNT = $roomInsertCount / $mappedEntityCount attempted")
        Log.i(TAG, "║ ✓ ROOM_INSERT_COUNT")

        // ─── CHECKPOINT: ROOM_QUERY_COUNT ───────────────────────
        val roomQueryCount = db.billDao().getTotalBillCount()
        Log.i(TAG, "║ [ROOM_QUERY_COUNT] SELECT COUNT(*) FROM bills = $roomQueryCount")

        if (roomQueryCount == 0 && mappedEntityCount > 0) {
            throw SyncException("ROOM_QUERY_COUNT",
                "CRITICAL: Inserted $roomInsertCount but Room reads 0. " +
                "Possible schema mismatch or transaction rollback.")
        }

        Log.i(TAG, "╠══════════════════════════════════════════╣")
        Log.i(TAG, "║ ✓✓ ALL CHECKPOINTS PASSED                ║")
        Log.i(TAG, "║   ADMIN_ID_RESOLVED       = $resolvedAdminId")
        Log.i(TAG, "║   FIRESTORE_DOC_COUNT     = $firestoreDocumentCount")
        Log.i(TAG, "║   MAPPED_ENTITY_COUNT     = $mappedEntityCount")
        Log.i(TAG, "║   ROOM_INSERT_COUNT       = $roomInsertCount")
        Log.i(TAG, "║   ROOM_QUERY_COUNT        = $roomQueryCount")
        Log.i(TAG, "╚══════════════════════════════════════════╝")

        SyncResult(
            resolvedAdminId = resolvedAdminId,
            firestorePathUsed = billsPath,
            firestoreDocumentCount = firestoreDocumentCount,
            mappedEntityCount = mappedEntityCount,
            roomInsertCount = roomInsertCount,
            roomQueryCount = roomQueryCount
        )
    }
}
