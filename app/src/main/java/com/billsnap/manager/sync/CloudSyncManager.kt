package com.billsnap.manager.sync

import android.content.Context
import android.util.Log
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.CustomerEntity
import com.billsnap.manager.util.ImageCompressor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages cloud synchronization of bills and customers.
 *  - Metadata → Firebase Firestore (structured data)
 *  - Images  → Google Drive via [DriveManager] (no Firebase Storage / Blaze plan needed)
 *
 * Restore downloads from Firestore + Drive and merges with local DB using sync_id.
 * App remains fully usable offline — sync happens non-blocking in background.
 */
class CloudSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CloudSyncManager"
        const val STATUS_IDLE = "Idle"
        const val STATUS_SYNCING = "Syncing"
        const val STATUS_ERROR = "Error"

        @Volatile
        private var instance: CloudSyncManager? = null

        fun getInstance(context: Context): CloudSyncManager {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val db = AppDatabase.getInstance(context)
    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    /** Lazily initialized when user is signed in */
    private var driveManager: DriveManager? = null

    private fun getOrCreateDriveManager(): DriveManager {
        driveManager?.let { return it }

        val email = auth.currentUser?.email
            ?: throw Exception("Google account email not available. Please sign in again.")

        return DriveManager(context, email).also { driveManager = it }
    }

    fun isSignedIn(): Boolean = auth.currentUser != null

    fun getSyncStatus(): String = prefs.getString("sync_status", STATUS_IDLE) ?: STATUS_IDLE

    fun setSyncStatus(status: String) {
        prefs.edit().putString("sync_status", status).apply()
    }

    fun getLastSyncedTime(): Long = prefs.getLong("last_synced", 0)

    private suspend fun getShopId(uid: String): String? {
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            doc.getString("currentShopId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get shop ID for user", e)
            null
        }
    }

    suspend fun syncAll() {
        if (!isSignedIn()) {
            Log.d(TAG, "Not signed in, skipping sync")
            return
        }

        val uid = auth.currentUser?.uid ?: return

        try {
            setSyncStatus(STATUS_SYNCING)

            // Verify Firestore is reachable before starting (fails fast instead of retrying forever)
            verifyFirestoreAccess(uid)

            val shopId = getShopId(uid) ?: throw Exception("Shop ID not found for user. Please set up your app first.")

            // 120-second timeout to allow Drive uploads (images take longer than Firestore metadata)
            withTimeout(120_000L) {
                syncCustomers(uid, shopId)
                syncBills(uid, shopId)
                syncCorrections(uid, shopId)
            }

            prefs.edit().putLong("last_synced", System.currentTimeMillis()).apply()
            setSyncStatus(STATUS_IDLE)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Sync timed out after 120 seconds", e)
            setSyncStatus(STATUS_ERROR)
            throw Exception("Sync timed out. Check your internet connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            setSyncStatus(STATUS_ERROR)
            throw e
        }
    }

    /**
     * Quick test to verify Firestore is accessible.
     * Fails fast with a clear message if the API is not enabled.
     */
    private suspend fun verifyFirestoreAccess(uid: String) {
        try {
            withTimeout(10_000L) {
                firestore.collection("users").document(uid).get().await()
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                msg.contains("has not been used", ignoreCase = true) ||
                msg.contains("is disabled", ignoreCase = true)
            ) {
                throw Exception(
                    "Cloud Firestore is not enabled. Go to Firebase Console → Build → Firestore Database → Create database."
                )
            }
            // For other errors (network etc.), let sync try anyway
            Log.w(TAG, "Firestore access check returned error, attempting sync anyway", e)
        }
    }

    // ─── Sync Upload ────────────────────────────────────────────────

    private suspend fun syncCustomers(uid: String, shopId: String) {
        val customers = db.customerDao().getAllCustomersSync()
        val collection = firestore.collection("shops").document(shopId).collection("customers")

        for (customer in customers) {
            // Generate sync_id if missing
            val syncId = customer.syncId ?: UUID.randomUUID().toString().also {
                db.customerDao().updateSyncId(customer.customerId, it)
            }

            val creatorId = customer.createdBy ?: uid

            val data = hashMapOf<String, Any?>(
                "customer_id" to customer.customerId,
                "sync_id" to syncId,
                "name" to customer.name,
                "phone_number" to customer.phoneNumber,
                "details" to customer.details,
                "created_timestamp" to customer.createdTimestamp,
                "shop_id" to shopId,
                "created_by" to creatorId
            )

            // Upload profile image to Google Drive (non-fatal)
            if (customer.profileImagePath.isNotEmpty()) {
                val file = File(customer.profileImagePath)
                if (file.exists()) {
                    val driveFileId = uploadImageToDrive(
                        localFile = file,
                        driveFileName = "profile_${syncId}.jpg",
                        existingDriveId = customer.driveFileId
                    )
                    if (driveFileId != null) {
                        data["drive_file_id"] = driveFileId
                        db.customerDao().updateDriveFileId(customer.customerId, driveFileId)
                    }
                }
            }

            collection.document(syncId).set(data).await()
        }
    }

    private suspend fun syncBills(uid: String, shopId: String) {
        val bills = db.billDao().getAllBillsSync()
        val collection = firestore.collection("shops").document(shopId).collection("bills")

        for (bill in bills) {
            // Generate sync_id if missing
            val syncId = bill.syncId ?: UUID.randomUUID().toString().also {
                db.billDao().updateSyncId(bill.id, it)
            }

            val creatorId = bill.createdBy ?: uid

            val data = hashMapOf<String, Any?>(
                "id" to bill.id,
                "sync_id" to syncId,
                "custom_name" to bill.customName,
                "notes" to bill.notes,
                "timestamp" to bill.timestamp,
                "payment_status" to bill.paymentStatus,
                "customer_id" to bill.customerId,
                "customer_sync_id" to (bill.customerId?.let { cid ->
                    db.customerDao().getCustomerById(cid)?.syncId
                }),
                "reminder_datetime" to bill.reminderDatetime,
                "paid_timestamp" to bill.paidTimestamp,
                "shop_id" to shopId,
                "created_by" to creatorId,
                "is_smart_processed" to bill.isSmartProcessed,
                "smart_ocr_json" to bill.smartOcrJson,
                "late_risk_score" to bill.lateRiskScore,
                "paid_amount" to bill.paidAmount,
                "remaining_amount" to bill.remainingAmount,
                "last_payment_date" to bill.lastPaymentDate
            )

            // Upload bill image to Google Drive (non-fatal)
            // Prefer optimized image if available, fallback to original
            val imageToUploadPath = if (!bill.optimizedImagePath.isNullOrEmpty() && File(bill.optimizedImagePath).exists()) {
                bill.optimizedImagePath
            } else {
                bill.imagePath
            }
            val file = File(imageToUploadPath)
            if (file.exists()) {
                val driveFileId = uploadImageToDrive(
                    localFile = file,
                    driveFileName = "bill_${syncId}.jpg",
                    existingDriveId = bill.driveFileId
                )
                if (driveFileId != null) {
                    data["drive_file_id"] = driveFileId
                    db.billDao().updateDriveFileId(bill.id, driveFileId)
                }
            }

            collection.document(syncId).set(data).await()
        }
    }

    private suspend fun syncCorrections(uid: String, shopId: String) {
        val corrections = db.ocrCorrectionDao().getAllCorrectionsSync()
        val collection = firestore.collection("shops").document(shopId).collection("ocr_corrections")

        for (correction in corrections) {
            val syncId = correction.syncId ?: UUID.randomUUID().toString().also {
                db.ocrCorrectionDao().updateSyncId(correction.id, it)
            }

            val creatorId = correction.createdBy ?: uid

            val data = hashMapOf<String, Any?>(
                "id" to correction.id,
                "sync_id" to syncId,
                "original_text" to correction.originalText,
                "corrected_text" to correction.correctedText,
                "timestamp" to correction.timestamp,
                "shop_id" to shopId,
                "created_by" to creatorId
            )

            collection.document(syncId).set(data).await()
        }
    }

    /**
     * Upload image to Drive with compression.
     * Skips re-upload if [existingDriveId] is set and the file still exists on Drive.
     * Returns Drive file ID on success, null on failure.
     */
    private suspend fun uploadImageToDrive(
        localFile: File,
        driveFileName: String,
        existingDriveId: String?
    ): String? {
        return try {
            withContext(Dispatchers.IO) {
                val drive = getOrCreateDriveManager()

                // Skip if already uploaded and file still exists on Drive
                if (!existingDriveId.isNullOrEmpty() && drive.fileExists(existingDriveId)) {
                    Log.d(TAG, "Image already on Drive ($existingDriveId), skipping upload")
                    return@withContext existingDriveId
                }

                // Compress image to ≤500KB JPEG
                val compressed = ImageCompressor.compress(localFile, context.cacheDir)
                try {
                    drive.uploadImage(compressed, driveFileName)
                } finally {
                    compressed.delete() // cleanup temp file
                }
            }
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            // Must propagate so the UI can launch the consent screen
            Log.w(TAG, "Drive consent needed for $driveFileName", e)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload image to Drive ($driveFileName), skipping", e)
            null
        }
    }

    // ─── Restore / Download ─────────────────────────────────────────

    /**
     * Restore all data from Firebase + Google Drive.
     * Uses sync_id for dedup: if a record with the same sync_id exists locally, it is skipped.
     * Customer → Bill order is maintained to preserve foreign key relationships.
     */
    suspend fun restoreAll() {
        if (!isSignedIn()) return
        val uid = auth.currentUser?.uid ?: return

        try {
            setSyncStatus(STATUS_SYNCING)
            verifyFirestoreAccess(uid)

            val shopId = getShopId(uid) ?: throw Exception("Shop ID not found for user. Please set up your app first.")

            withTimeout(120_000L) {
                restoreCustomers(uid, shopId)
                restoreBills(uid, shopId)
                restoreCorrections(uid, shopId)
            }

            prefs.edit().putLong("last_synced", System.currentTimeMillis()).apply()
            setSyncStatus(STATUS_IDLE)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Restore timed out after 120 seconds", e)
            setSyncStatus(STATUS_ERROR)
            throw Exception("Restore timed out. Check your internet connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Restore error", e)
            setSyncStatus(STATUS_ERROR)
            throw e
        }
    }

    /**
     * Map of cloud customer sync_id → local customer_id (for FK remapping during bill restore)
     */
    private val customerSyncIdToLocalId = mutableMapOf<String, Long>()

    private suspend fun restoreCustomers(uid: String, shopId: String) {
        customerSyncIdToLocalId.clear()
        val snapshot = firestore.collection("shops").document(shopId)
            .collection("customers").get().await()

        for (doc in snapshot.documents) {
            val syncId = doc.getString("sync_id") ?: doc.id
            val name = doc.getString("name") ?: continue

            // Check if already exists locally
            val existing = db.customerDao().getCustomerBySyncId(syncId)
            if (existing != null) {
                customerSyncIdToLocalId[syncId] = existing.customerId
                continue
            }

            // Download profile image from Drive if available
            var profilePath = ""
            val driveFileId = doc.getString("drive_file_id")
            if (!driveFileId.isNullOrEmpty()) {
                profilePath = downloadImageFromDrive(driveFileId, "profile_images") ?: ""
            }

            val customer = CustomerEntity(
                name = name,
                phoneNumber = doc.getString("phone_number") ?: "",
                details = doc.getString("details") ?: "",
                profileImagePath = profilePath,
                createdTimestamp = doc.getLong("created_timestamp") ?: System.currentTimeMillis(),
                syncId = syncId,
                driveFileId = driveFileId,
                shopId = doc.getString("shop_id") ?: shopId,
                createdBy = doc.getString("created_by")
            )
            val localId = db.customerDao().insert(customer)
            customerSyncIdToLocalId[syncId] = localId
        }
    }

    private suspend fun restoreBills(uid: String, shopId: String) {
        val snapshot = firestore.collection("shops").document(shopId)
            .collection("bills").get().await()

        for (doc in snapshot.documents) {
            val syncId = doc.getString("sync_id") ?: doc.id
            val customName = doc.getString("custom_name") ?: continue

            // Check if already exists locally
            val existing = db.billDao().getBillBySyncId(syncId)
            if (existing != null) continue

            // Download bill image from Drive
            var imagePath = ""
            val driveFileId = doc.getString("drive_file_id")
            if (!driveFileId.isNullOrEmpty()) {
                imagePath = downloadImageFromDrive(driveFileId, "bill_images") ?: ""
            }

            // Remap customer_id using customer_sync_id
            val customerSyncId = doc.getString("customer_sync_id")
            val localCustomerId = customerSyncId?.let { customerSyncIdToLocalId[it] }

            // Check for name collision — append suffix if needed
            val finalName = if (db.billDao().countByName(customName) > 0) {
                "${customName}_restored_${syncId.take(6)}"
            } else {
                customName
            }

            val bill = BillEntity(
                customName = finalName,
                notes = doc.getString("notes") ?: "",
                imagePath = imagePath,
                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                paymentStatus = doc.getString("payment_status") ?: "Unpaid",
                customerId = localCustomerId,
                reminderDatetime = doc.getLong("reminder_datetime"),
                paidTimestamp = doc.getLong("paid_timestamp"),
                syncId = syncId,
                driveFileId = driveFileId,
                shopId = doc.getString("shop_id") ?: shopId,
                createdBy = doc.getString("created_by"),
                isSmartProcessed = doc.getBoolean("is_smart_processed") ?: false,
                smartOcrJson = doc.getString("smart_ocr_json"),
                lateRiskScore = doc.getDouble("late_risk_score")?.toFloat(),
                paidAmount = doc.getDouble("paid_amount") ?: 0.0,
                remainingAmount = doc.getDouble("remaining_amount") ?: 0.0,
                lastPaymentDate = doc.getLong("last_payment_date"),
                optimizedImagePath = null // Restored images are saved to the primary imagePath
            )
            db.billDao().insert(bill)
        }
    }

    private suspend fun restoreCorrections(uid: String, shopId: String) {
        val snapshot = firestore.collection("shops").document(shopId)
            .collection("ocr_corrections").get().await()

        for (doc in snapshot.documents) {
            val syncId = doc.getString("sync_id") ?: doc.id
            val originalText = doc.getString("original_text") ?: continue
            val correctedText = doc.getString("corrected_text") ?: continue

            // Check if already exists
            val existing = db.ocrCorrectionDao().getCorrectionBySyncId(syncId)
            if (existing != null) continue

            val correction = com.billsnap.manager.data.OcrCorrectionEntity(
                originalText = originalText,
                correctedText = correctedText,
                syncId = syncId,
                shopId = doc.getString("shop_id") ?: shopId,
                createdBy = doc.getString("created_by"),
                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
            )
            try {
                db.ocrCorrectionDao().insertCorrection(correction)
            } catch (e: Exception) {
                // Ignore constraint violations
            }
        }
    }

    /**
     * Download an image from Google Drive into app's internal storage.
     * @param driveFileId The Drive file ID
     * @param subDir Subdirectory under filesDir (e.g. "bill_images", "profile_images")
     * @return Absolute path to the downloaded file, or null on failure
     */
    private suspend fun downloadImageFromDrive(driveFileId: String, subDir: String): String? {
        return try {
            withContext(Dispatchers.IO) {
                val drive = getOrCreateDriveManager()
                val imageDir = File(context.filesDir, subDir)
                if (!imageDir.exists()) imageDir.mkdirs()
                val destFile = File(imageDir, "${UUID.randomUUID()}.jpg")
                drive.downloadImage(driveFileId, destFile)
                destFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image from Drive ($driveFileId)", e)
            null
        }
    }

    /**
     * Public API for downloading a bill image from Google Drive.
     * Used by [BillSyncRepository] for Worker bill sync downloads.
     *
     * @param driveFileId The Drive file ID
     * @return Absolute path to the downloaded file, or null on failure
     */
    suspend fun downloadBillImageFromDrive(driveFileId: String): String? {
        return downloadImageFromDrive(driveFileId, "bill_images")
    }

    // ─── Local Export ────────────────────────────────────────────────

    /**
     * Export local database as a ZIP containing:
     *  - backup.json  (metadata for all bills & customers)
     *  - images/      (all referenced bill & customer images)
     */
    suspend fun downloadBackupFile(): File {
        val bills = db.billDao().getAllBillsSync()
        val customers = db.customerDao().getAllCustomersSync()
        val corrections = db.ocrCorrectionDao().getAllCorrectionsSync()

        // Collect all image files that exist on disk
        val imageFiles = mutableMapOf<String, File>()  // zip entry name → file

        val json = JSONObject().apply {
            put("exported_at", System.currentTimeMillis())
            put("customers", JSONArray().apply {
                customers.forEach { c ->
                    put(JSONObject().apply {
                        put("customer_id", c.customerId)
                        put("name", c.name)
                        put("phone_number", c.phoneNumber)
                        put("details", c.details)
                        put("created_timestamp", c.createdTimestamp)
                        put("sync_id", c.syncId ?: "")
                        put("drive_file_id", c.driveFileId ?: "")

                        // Include image in ZIP if it exists
                        if (c.profileImagePath.isNotEmpty()) {
                            val imgFile = File(c.profileImagePath)
                            if (imgFile.exists()) {
                                val zipName = "images/customer_${c.customerId}_${imgFile.name}"
                                imageFiles[zipName] = imgFile
                                put("image_file", zipName)
                            }
                        }
                    })
                }
            })
            put("bills", JSONArray().apply {
                bills.forEach { b ->
                    put(JSONObject().apply {
                        put("id", b.id)
                        put("custom_name", b.customName)
                        put("notes", b.notes)
                        put("timestamp", b.timestamp)
                        put("payment_status", b.paymentStatus)
                        put("customer_id", b.customerId ?: JSONObject.NULL)
                        put("reminder_datetime", b.reminderDatetime ?: JSONObject.NULL)
                        put("paid_timestamp", b.paidTimestamp ?: JSONObject.NULL)
                        put("sync_id", b.syncId ?: "")
                        put("drive_file_id", b.driveFileId ?: "")
                        put("is_smart_processed", b.isSmartProcessed)
                        put("smart_ocr_json", b.smartOcrJson ?: JSONObject.NULL)
                        put("late_risk_score", b.lateRiskScore?.toDouble() ?: JSONObject.NULL)
                        put("paid_amount", b.paidAmount)
                        put("remaining_amount", b.remainingAmount)
                        put("last_payment_date", b.lastPaymentDate ?: JSONObject.NULL)

                        // Include image in ZIP if it exists
                        val imgFile = File(b.imagePath)
                        if (imgFile.exists()) {
                            val zipName = "images/bill_${b.id}_${imgFile.name}"
                            imageFiles[zipName] = imgFile
                            put("image_file", zipName)
                        }
                    })
                }
            })
            put("ocr_corrections", JSONArray().apply {
                corrections.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("original_text", c.originalText)
                        put("corrected_text", c.correctedText)
                        put("sync_id", c.syncId ?: "")
                        put("shop_id", c.shopId ?: JSONObject.NULL)
                        put("created_by", c.createdBy ?: JSONObject.NULL)
                        put("timestamp", c.timestamp)
                    })
                }
            })
        }

        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        val zipFile = File(exportDir, "BillSnap_Backup_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1) Write JSON metadata
            zos.putNextEntry(ZipEntry("backup.json"))
            zos.write(json.toString(2).toByteArray())
            zos.closeEntry()

            // 2) Write all image files
            val buffer = ByteArray(8192)
            for ((entryName, file) in imageFiles) {
                zos.putNextEntry(ZipEntry(entryName))
                BufferedInputStream(FileInputStream(file)).use { bis ->
                    var count: Int
                    while (bis.read(buffer).also { count = it } != -1) {
                        zos.write(buffer, 0, count)
                    }
                }
                zos.closeEntry()
            }
        }

        Log.d(TAG, "Backup ZIP created: ${zipFile.name} (${imageFiles.size} images, ${zipFile.length() / 1024}KB)")
        return zipFile
    }

    /**
     * Disconnect from cloud: sign out and clear sync state.
     */
    fun disconnect() {
        auth.signOut()
        prefs.edit().clear().apply()
        customerSyncIdToLocalId.clear()
        driveManager = null
    }
}
