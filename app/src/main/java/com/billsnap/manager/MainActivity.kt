package com.billsnap.manager

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.billsnap.manager.databinding.ActivityMainBinding
import com.billsnap.manager.security.AppLockManager
import com.billsnap.manager.util.LocaleManager

/**
 * Single-activity host for Navigation component.
 * Gates the app on LockFragment when App Lock is enabled.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var wasLocked = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHost?.navController

        if (navController != null) {
            val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val onboardingComplete = prefs.getBoolean("onboarding_complete", false)
            
            val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
            if (!onboardingComplete) {
                navGraph.setStartDestination(R.id.onboardingFragment)
            } else {
                navGraph.setStartDestination(R.id.homeFragment)
            }
            navController.graph = navGraph
        }

        com.billsnap.manager.security.PermissionManager.initialize()
        com.billsnap.manager.util.TransitionController.animateReveal(this)
        checkAppLock()
    }

    override fun onResume() {
        super.onResume()
        com.billsnap.manager.security.ActivityLogger.logAppOpened()
        if (wasLocked) {
            checkAppLock()
        }
    }

    override fun onPause() {
        super.onPause()
        val lockManager = AppLockManager.getInstance(this)
        wasLocked = lockManager.isLockEnabled
    }

    private fun checkAppLock() {
        try {
            val lockManager = AppLockManager.getInstance(this)
            if (lockManager.isLockEnabled) {
                val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val navController = navHost?.navController ?: return
                // Navigate to lock screen if not already there
                if (navController.currentDestination?.id != R.id.lockFragment) {
                    navController.navigate(R.id.lockFragment)
                }
            }
        } catch (_: Exception) {
            // EncryptedSharedPreferences may fail on first run, skip lock check
        }
    }
}
