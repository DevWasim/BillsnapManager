package com.billsnap.manager.ui.settings

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import android.content.Intent
import com.billsnap.manager.R
import com.billsnap.manager.databinding.FragmentSettingsBinding
import com.billsnap.manager.security.AppLockManager
import com.billsnap.manager.sync.CloudSyncManager
import com.billsnap.manager.data.AdminProfileManager
import com.billsnap.manager.data.PaymentMethodType
import com.billsnap.manager.util.CurrencyManager
import com.billsnap.manager.util.LocaleManager
import com.billsnap.manager.worker.SyncWorker
import android.widget.ArrayAdapter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings screen:
 *  - App Lock (PIN + Biometric)
 *  - Cloud Backup (Google Sign-In, Backup, Restore, Disconnect)
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsFragment"
        private const val PLACEHOLDER_WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var appLockManager: AppLockManager
    private lateinit var syncManager: CloudSyncManager

    /** Legacy GoogleSignInClient — reliable across all devices including MIUI */
    private lateinit var googleSignInClient: GoogleSignInClient

    /** Tracks whether to retry backup or restore after Drive consent */
    private var pendingDriveAction: String? = null  // "backup" or "restore"

    /** Launched when Google Drive needs user consent for the drive.file scope */
    private lateinit var driveConsentLauncher: ActivityResultLauncher<Intent>

    /** Launched for Google Sign-In intent */
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register sign-in launcher BEFORE the fragment is STARTED
        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account?.idToken
                    if (idToken != null) {
                        Log.d(TAG, "Google Sign-In successful, authenticating with Firebase")
                        firebaseAuthWithGoogle(idToken)
                    } else {
                        Log.e(TAG, "Google Sign-In succeeded but no ID token received")
                        context?.let {
                            Toast.makeText(it,
                                getString(R.string.google_sign_in_failed, "No ID token received"),
                                Toast.LENGTH_LONG).show()
                        }
                        _binding?.btnGoogleSignIn?.isEnabled = true
                    }
                } catch (e: ApiException) {
                    Log.e(TAG, "Google Sign-In failed with status code: ${e.statusCode}", e)
                    val message = when (e.statusCode) {
                        12501 -> "Sign-in cancelled"
                        12502 -> "Sign-in is already in progress"
                        7 -> "Network error. Please check your connection."
                        else -> "Sign-in failed (code: ${e.statusCode})"
                    }
                    context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
                    _binding?.btnGoogleSignIn?.isEnabled = true
                }
            } else {
                Log.w(TAG, "Google Sign-In cancelled or failed, resultCode: ${result.resultCode}")
                context?.let {
                    Toast.makeText(it,
                        getString(R.string.google_sign_in_canceled),
                        Toast.LENGTH_SHORT).show()
                }
                _binding?.btnGoogleSignIn?.isEnabled = true
            }
        }

        // Register consent launcher BEFORE the fragment is STARTED
        driveConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Drive consent granted, retrying: $pendingDriveAction")
                when (pendingDriveAction) {
                    "backup" -> performBackup()
                    "restore" -> performRestoreInternal()
                }
            } else {
                Log.w(TAG, "Drive consent denied by user")
                Toast.makeText(requireContext(),
                    "Google Drive access is required for image backup. Please grant permission.",
                    Toast.LENGTH_LONG
                ).show()
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = getString(R.string.backup_now)
                binding.btnRestoreBackup.isEnabled = true
                binding.btnRestoreBackup.text = getString(R.string.restore_backup)
            }
            pendingDriveAction = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appLockManager = AppLockManager(requireContext())
        syncManager = CloudSyncManager.getInstance(requireContext())

        // Configure GoogleSignInClient
        val webClientId = resolveWebClientId()
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (webClientId != null) {
            gsoBuilder.requestIdToken(webClientId)
        }
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gsoBuilder.build())

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        setupAppLock()
        setupCloudBackup()
        setupOcrSettings()
        setupLanguageToggle()
        setupThemeToggle()
        setupCurrencySelector()
        setupPaymentMethods()
    }

    private fun setupCurrencySelector() {
        val currencies = CurrencyManager.getSupportedCurrencies()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, currencies)
        binding.autoCompleteCurrency.setAdapter(adapter)

        val currentCode = CurrencyManager.currentCurrency.value.code
        binding.autoCompleteCurrency.setText(currentCode, false)

        binding.autoCompleteCurrency.setOnItemClickListener { _, _, position, _ ->
            val selected = currencies[position]
            if (selected != currentCode) {
                CurrencyManager.setCurrency(selected)
                // Force an application-wide recreate to instantly apply the new format
                com.billsnap.manager.util.TransitionController.snapshotAndRecreate(
                    requireActivity(),
                    binding.autoCompleteCurrency
                )
            }
        }
    }

    private fun setupThemeToggle() {
        val currentTheme = com.billsnap.manager.util.ThemeManager.getTheme(requireContext())
        if (currentTheme == com.billsnap.manager.util.ThemeManager.THEME_LIGHT) {
            binding.toggleTheme.check(R.id.btnThemeDay)
        } else {
            binding.toggleTheme.check(R.id.btnThemeNight)
        }

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newTheme = if (checkedId == R.id.btnThemeDay) {
                com.billsnap.manager.util.ThemeManager.THEME_LIGHT
            } else {
                com.billsnap.manager.util.ThemeManager.THEME_DARK
            }

            val oldTheme = com.billsnap.manager.util.ThemeManager.getTheme(requireContext())
            if (newTheme != oldTheme) {
                com.billsnap.manager.util.ThemeManager.setTheme(requireContext(), newTheme)
                com.billsnap.manager.util.TransitionController.snapshotAndRecreate(
                    requireActivity(),
                    binding.toggleTheme
                )
            }
        }
    }

    private fun setupOcrSettings() {
        val prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        binding.switchOcrAccuracy.isChecked = prefs.getBoolean("ocr_high_accuracy", false)
        binding.switchOptimization.isChecked = prefs.getBoolean("advanced_optimization", true) // Default to true for best quality
        
        binding.switchOcrAccuracy.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ocr_high_accuracy", isChecked).apply()
        }
        
        binding.switchOptimization.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("advanced_optimization", isChecked).apply()
        }
    }

    private fun setupLanguageToggle() {
        val currentLang = LocaleManager.getLanguage(requireContext())
        if (currentLang == LocaleManager.URDU) {
            binding.toggleLanguage.check(R.id.btnLangUrdu)
        } else {
            binding.toggleLanguage.check(R.id.btnLangEnglish)
        }

        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newLang = if (checkedId == R.id.btnLangUrdu) LocaleManager.URDU else LocaleManager.ENGLISH
            val oldLang = LocaleManager.getLanguage(requireContext())
            if (newLang != oldLang) {
                LocaleManager.setLanguage(requireContext(), newLang)
                com.billsnap.manager.util.TransitionController.snapshotAndRecreate(
                    requireActivity(),
                    binding.toggleLanguage
                )
            }
        }
    }

    // ─── App Lock ──────────────────────────────────────────────────

    private fun setupAppLock() {
        binding.switchAppLock.isChecked = appLockManager.isLockEnabled

        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showSetPinDialog()
            } else {
                appLockManager.isLockEnabled = false
                binding.layoutFingerprint.visibility = View.GONE
                binding.btnSetPin.visibility = View.GONE
            }
        }

        // Show fingerprint & PIN options if lock is already enabled
        if (appLockManager.isLockEnabled) {
            binding.layoutFingerprint.visibility = View.VISIBLE
            binding.btnSetPin.visibility = View.VISIBLE
            binding.switchFingerprint.isChecked = appLockManager.useFingerprint
        }

        // Fingerprint availability check
        val canUseBiometric = BiometricManager.from(requireContext())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

        binding.switchFingerprint.isEnabled = canUseBiometric
        binding.switchFingerprint.setOnCheckedChangeListener { _, isChecked ->
            appLockManager.useFingerprint = isChecked
        }

        binding.btnSetPin.setOnClickListener { showSetPinDialog() }
    }

    private fun showSetPinDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_set_pin, null)
        val etPin = dialogView.findViewById<android.widget.EditText>(R.id.etPin)
        val etConfirm = dialogView.findViewById<android.widget.EditText>(R.id.etConfirmPin)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.set_pin))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val pin = etPin.text.toString()
                val confirm = etConfirm.text.toString()
                if (pin.length == 4 && pin == confirm) {
                    appLockManager.setPin(pin)
                    appLockManager.isLockEnabled = true
                    binding.layoutFingerprint.visibility = View.VISIBLE
                    binding.btnSetPin.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), getString(R.string.pin_set_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.pin_mismatch), Toast.LENGTH_SHORT).show()
                    binding.switchAppLock.isChecked = appLockManager.isLockEnabled
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                binding.switchAppLock.isChecked = appLockManager.isLockEnabled
            }
            .show()
    }

    // ─── Cloud Backup ──────────────────────────────────────────────

    private fun setupCloudBackup() {
        updateCloudUI()

        binding.btnGoogleSignIn.setOnClickListener { signInWithGoogle() }
        binding.btnLogout.setOnClickListener { confirmDisconnect() }
        binding.btnSyncNow.setOnClickListener { performBackup() }
        binding.btnRestoreBackup.setOnClickListener { performRestore() }
        binding.btnDownloadBackup.setOnClickListener { downloadBackupFile() }
    }

    private fun updateCloudUI() {
        val isSignedIn = syncManager.isSignedIn()
        val user = FirebaseAuth.getInstance().currentUser

        binding.btnGoogleSignIn.visibility = if (isSignedIn) View.GONE else View.VISIBLE
        binding.layoutProfileInfo.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        
        binding.btnSyncNow.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        binding.btnRestoreBackup.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        binding.btnDownloadBackup.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        binding.tvSyncStatus.visibility = if (isSignedIn) View.VISIBLE else View.GONE
        binding.tvLastSynced.visibility = if (isSignedIn) View.VISIBLE else View.GONE

        if (isSignedIn) {
            binding.tvProfileName.text = user?.displayName ?: "User"
            binding.tvProfileEmail.text = user?.email ?: ""
            updateSyncStatusUI()
        }
    }

    private fun updateSyncStatusUI() {
        val status = syncManager.getSyncStatus()
        binding.tvSyncStatus.text = when (status) {
            CloudSyncManager.STATUS_SYNCING -> getString(R.string.sync_status_syncing)
            CloudSyncManager.STATUS_ERROR -> getString(R.string.sync_status_error)
            else -> getString(R.string.sync_status_idle)
        }

        val lastSynced = syncManager.getLastSyncedTime()
        binding.tvLastSynced.text = if (lastSynced > 0) {
            val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
            "${getString(R.string.last_synced)}: ${sdf.format(Date(lastSynced))}"
        } else {
            "${getString(R.string.last_synced)}: ${getString(R.string.never_synced)}"
        }
    }

    /**
     * Uses legacy GoogleSignInClient — launches the Google account chooser Activity.
     * This is far more reliable than Credential Manager on MIUI and custom ROM devices.
     */
    private fun signInWithGoogle() {
        val webClientId = resolveWebClientId()
        if (webClientId == null) {
            Toast.makeText(requireContext(), getString(R.string.google_sign_in_not_configured), Toast.LENGTH_LONG).show()
            return
        }

        binding.btnGoogleSignIn.isEnabled = false
        binding.btnGoogleSignIn.text = getString(R.string.signing_in)

        Log.d(TAG, "Starting Google Sign-In intent")
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun resolveWebClientId(): String? {
        val webClientIdRes = resources.getIdentifier(
            "default_web_client_id",
            "string",
            requireContext().packageName
        )
        if (webClientIdRes == 0) return null

        val webClientId = getString(webClientIdRes).trim()
        if (webClientId.isEmpty()) return null
        if (webClientId.equals(PLACEHOLDER_WEB_CLIENT_ID, ignoreCase = true)) return null
        if (webClientId.contains("placeholder", ignoreCase = true)) return null

        return webClientId
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase auth successful")
                    setupFirebaseUser()
                } else {
                    Log.e(TAG, "Firebase auth failed", task.exception)
                    if (_binding != null) {
                        binding.btnGoogleSignIn.isEnabled = true
                        binding.btnGoogleSignIn.text = getString(R.string.sign_in_google)
                        Toast.makeText(requireContext(), getString(R.string.sync_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        context?.let {
                            Toast.makeText(it, getString(R.string.sign_in_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    private fun setupFirebaseUser() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()

        lifecycleScope.launch {
            try {
                val userDocRef = firestore.collection("users").document(uid)
                val userDoc = userDocRef.get().await()

                if (!userDoc.exists() || !userDoc.contains("currentShopId")) {
                    val shopId = java.util.UUID.randomUUID().toString()
                    val shopData = hashMapOf(
                        "shopId" to shopId,
                        "ownerId" to uid,
                        "name" to "${user.displayName ?: "My"} Shop",
                        "createdAt" to System.currentTimeMillis()
                    )
                    firestore.collection("shops").document(shopId).set(shopData).await()

                    val userData = hashMapOf(
                        "uid" to uid,
                        "email" to user.email,
                        "name" to user.displayName,
                        "role" to "owner",
                        "currentShopId" to shopId
                    )
                    userDocRef.set(userData).await()
                }

                syncManager.setSyncStatus(CloudSyncManager.STATUS_IDLE)
                if (_binding != null) {
                    binding.btnGoogleSignIn.isEnabled = true
                    binding.btnGoogleSignIn.text = getString(R.string.sign_in_google)
                    Toast.makeText(requireContext(), getString(R.string.signed_in_success), Toast.LENGTH_SHORT).show()
                    com.billsnap.manager.security.ActivityLogger.logLogin()
                    updateCloudUI()
                    scheduleSyncWorker()
                } else {
                    context?.let {
                        Toast.makeText(it, getString(R.string.signed_in_success), Toast.LENGTH_SHORT).show()
                    }
                    com.billsnap.manager.security.ActivityLogger.logLogin()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup user", e)
                if (_binding != null) {
                    binding.btnGoogleSignIn.isEnabled = true
                    binding.btnGoogleSignIn.text = getString(R.string.sign_in_google)
                    Toast.makeText(requireContext(), getString(R.string.sync_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performBackup() {
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = getString(R.string.syncing)
        updateSyncStatusUI()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { syncManager.syncAll() }
                Toast.makeText(requireContext(), getString(R.string.sync_complete), Toast.LENGTH_SHORT).show()
            } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                // User hasn't granted Drive scope yet — launch consent screen
                Log.d(TAG, "Drive consent needed, launching consent screen for backup")
                pendingDriveAction = "backup"
                driveConsentLauncher.launch(e.intent)
                return@launch  // don't reset buttons; consent callback will retry
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.sync_failed_format, getString(R.string.sync_failed), e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                if (pendingDriveAction == null) {
                    binding.btnSyncNow.isEnabled = true
                    binding.btnSyncNow.text = getString(R.string.backup_now)
                    updateSyncStatusUI()
                }
            }
        }
    }

    private fun performRestore() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.restore_backup))
            .setMessage("Restore data from cloud? Existing data will be preserved. Only new items will be added.")
            .setPositiveButton("Restore") { _, _ ->
                performRestoreInternal()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performRestoreInternal() {
        binding.btnRestoreBackup.isEnabled = false
        binding.btnRestoreBackup.text = getString(R.string.restoring)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { syncManager.restoreAll() }
                Toast.makeText(requireContext(), getString(R.string.restore_complete), Toast.LENGTH_SHORT).show()
            } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                Log.d(TAG, "Drive consent needed, launching consent screen for restore")
                pendingDriveAction = "restore"
                driveConsentLauncher.launch(e.intent)
                return@launch
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.restore_failed_format, getString(R.string.restore_failed), e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                if (pendingDriveAction == null) {
                    binding.btnRestoreBackup.isEnabled = true
                    binding.btnRestoreBackup.text = getString(R.string.restore_backup)
                    updateSyncStatusUI()
                }
            }
        }
    }

    private fun downloadBackupFile() {
        binding.btnDownloadBackup.isEnabled = false

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { syncManager.downloadBackupFile() }
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.download_backup)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.export_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnDownloadBackup.isEnabled = true
            }
        }
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.disconnect))
            .setMessage("Sign out from cloud backup? Your local data will not be deleted.")
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                // Sign out from Google Sign-In
                googleSignInClient.signOut().addOnCompleteListener {
                    Log.d(TAG, "GoogleSignInClient signed out")
                }
                // Sign out from Firebase & clear sync state
                syncManager.disconnect()
                updateCloudUI()
                Toast.makeText(requireContext(), getString(R.string.disconnected), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun scheduleSyncWorker() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .addTag("cloud_sync")
            .build()
        WorkManager.getInstance(requireContext())
            .enqueueUniquePeriodicWork("cloud_sync", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Payment Methods ──────────────────────────────────────────

    private fun setupPaymentMethods() {
        val adminProfile = AdminProfileManager.getInstance(requireContext())
        val methods = adminProfile.getPaymentMethods()

        // Store name
        binding.etStoreName.setText(adminProfile.storeName)
        binding.etStoreName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adminProfile.storeName = s?.toString() ?: ""
            }
        })

        // Setup each payment method group
        setupPaymentMethodGroup(
            adminProfile,
            PaymentMethodType.BANK,
            methods.first { it.type == PaymentMethodType.BANK },
            binding.switchBankEnabled,
            binding.layoutBankFields,
            binding.etBankTitle,
            binding.etBankNumber,
            binding.switchBankShare
        )

        setupPaymentMethodGroup(
            adminProfile,
            PaymentMethodType.EASYPAISA,
            methods.first { it.type == PaymentMethodType.EASYPAISA },
            binding.switchEasyPaisaEnabled,
            binding.layoutEasyPaisaFields,
            binding.etEasyPaisaTitle,
            binding.etEasyPaisaNumber,
            binding.switchEasyPaisaShare
        )

        setupPaymentMethodGroup(
            adminProfile,
            PaymentMethodType.JAZZCASH,
            methods.first { it.type == PaymentMethodType.JAZZCASH },
            binding.switchJazzCashEnabled,
            binding.layoutJazzCashFields,
            binding.etJazzCashTitle,
            binding.etJazzCashNumber,
            binding.switchJazzCashShare
        )
    }

    private fun setupPaymentMethodGroup(
        adminProfile: AdminProfileManager,
        type: PaymentMethodType,
        initial: com.billsnap.manager.data.PaymentMethodInfo,
        enableSwitch: com.google.android.material.materialswitch.MaterialSwitch,
        fieldsLayout: LinearLayout,
        titleInput: com.google.android.material.textfield.TextInputEditText,
        numberInput: com.google.android.material.textfield.TextInputEditText,
        shareSwitch: com.google.android.material.materialswitch.MaterialSwitch
    ) {
        // Set initial state
        enableSwitch.isChecked = initial.enabled
        fieldsLayout.visibility = if (initial.enabled) View.VISIBLE else View.GONE
        titleInput.setText(initial.accountTitle)
        numberInput.setText(initial.accountNumber)
        shareSwitch.isChecked = initial.allowShare

        // Toggle enable → show/hide fields
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            fieldsLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            adminProfile.updatePaymentMethod(type) { it.copy(enabled = isChecked) }
        }

        // Share toggle
        shareSwitch.setOnCheckedChangeListener { _, isChecked ->
            adminProfile.updatePaymentMethod(type) { it.copy(allowShare = isChecked) }
        }

        // Account title
        titleInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adminProfile.updatePaymentMethod(type) { it.copy(accountTitle = s?.toString() ?: "") }
            }
        })

        // Account number
        numberInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adminProfile.updatePaymentMethod(type) { it.copy(accountNumber = s?.toString() ?: "") }
            }
        })
    }
}
