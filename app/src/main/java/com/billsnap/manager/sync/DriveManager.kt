package com.billsnap.manager.sync

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.io.FileOutputStream

/**
 * Manages Google Drive operations for BillSnap image backup.
 *
 * Uses [GoogleAccountCredential.usingOAuth2] which:
 * - Authenticates via the device's AccountManager (same account used for Firebase)
 * - Handles token refresh automatically
 * - Prompts user for Drive consent on first API call if needed
 *
 * All images are stored in a "BillSnap_Backups" folder in the user's Drive.
 * Scope: drive.file — app can only access files it creates.
 */
class DriveManager(private val context: Context, accountEmail: String) {

    companion object {
        private const val TAG = "DriveManager"
        private const val APP_FOLDER_NAME = "BillSnap_Backups"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val driveService: Drive

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccountName = accountEmail

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("BillSnap Manager")
            .build()
    }

    /**
     * Get or create the "BillSnap_Backups" folder in the user's Drive root.
     * Returns the folder's Drive ID.
     */
    private fun getOrCreateAppFolder(): String {
        // Search for existing folder
        val result = driveService.files().list()
            .setQ("name = '$APP_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        // Create folder
        val folderMetadata = DriveFile().apply {
            name = APP_FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = driveService.files().create(folderMetadata)
            .setFields("id")
            .execute()

        Log.d(TAG, "Created Drive folder: $APP_FOLDER_NAME (${folder.id})")
        return folder.id
    }

    /**
     * Upload a local image file to the app's Drive folder.
     * @param localFile The image file to upload
     * @param fileName The desired filename in Drive
     * @return The Drive file ID of the uploaded file
     */
    fun uploadImage(localFile: File, fileName: String): String {
        return executeWithRetry("upload $fileName") {
            val folderId = getOrCreateAppFolder()

            val fileMetadata = DriveFile().apply {
                name = fileName
                parents = listOf(folderId)
            }

            val mediaContent = FileContent("image/jpeg", localFile)
            val uploaded = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()

            Log.d(TAG, "Uploaded image: $fileName -> ${uploaded.id}")
            uploaded.id
        }
    }

    /**
     * Download an image from Drive by its file ID.
     * @param driveFileId The Drive file ID
     * @param destFile The local destination file
     */
    fun downloadImage(driveFileId: String, destFile: File) {
        executeWithRetry("download $driveFileId") {
            FileOutputStream(destFile).use { outputStream ->
                driveService.files().get(driveFileId)
                    .executeMediaAndDownloadTo(outputStream)
            }
            Log.d(TAG, "Downloaded image: $driveFileId -> ${destFile.absolutePath}")
        }
    }

    /**
     * Check if a Drive file still exists (not trashed).
     * Useful for verifying before skipping re-upload.
     */
    fun fileExists(driveFileId: String): Boolean {
        return try {
            val file = driveService.files().get(driveFileId)
                .setFields("id, trashed")
                .execute()
            file.getTrashed() != true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete a file from Drive. Non-fatal if it fails.
     */
    fun deleteImage(driveFileId: String) {
        try {
            driveService.files().delete(driveFileId).execute()
            Log.d(TAG, "Deleted Drive file: $driveFileId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Drive file: $driveFileId", e)
        }
    }

    /**
     * Execute a Drive operation with retry logic.
     * Retries up to [MAX_RETRIES] times with exponential backoff.
     */
    private fun <T> executeWithRetry(operationName: String, block: () -> T): T {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                return block()
            } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                // Token expired or consent needed — this must be handled by the caller/UI
                Log.e(TAG, "User-recoverable auth error during $operationName", e)
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt $attempt/$MAX_RETRIES failed for $operationName: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }
        }

        throw lastException ?: Exception("Failed after $MAX_RETRIES retries: $operationName")
    }
}
