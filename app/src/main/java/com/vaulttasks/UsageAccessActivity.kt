package com.vaulttasks

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Bridges the widget's "Tap to show sync status" action to Usage Access settings so that every widget
 * updates the moment the permission changes—whether the user returns with Back or presses Home.
 */
class UsageAccessActivity : Activity() {
    private var lastAccess: Boolean? = null
    private var leftForSettings = false
    private val onOpChanged = AppOpsManager.OnOpChangedListener { _, _ ->
        runOnUiThread { syncWidgetsIfChanged(showToast = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Obsidian.hasUsageAccess(this)) {
            TasksWidget.refreshSyncStatus(this)
            finish()
            return
        }
        lastAccess = false
        leftForSettings = savedInstanceState?.getBoolean(KEY_LEFT, false) ?: false
        runCatching {
            getSystemService(AppOpsManager::class.java)
                .startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, onOpChanged)
        }.onFailure { Log.w("VaultTasks", "Couldn't watch usage access changes", it) }

        if (savedInstanceState == null) {
            openSettings()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LEFT, leftForSettings)
    }

    override fun onPause() {
        super.onPause()
        leftForSettings = true
    }

    override fun onResume() {
        super.onResume()
        if (leftForSettings) {
            syncWidgetsIfChanged(showToast = false)
            finish()
        }
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(AppOpsManager::class.java).stopWatchingMode(onOpChanged)
        }
        super.onDestroy()
    }

    private fun openSettings() {
        val targeted = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        runCatching { startActivity(targeted) }
            .recoverCatching { startActivity(fallback) }
            .onFailure {
                Log.w("VaultTasks", "Couldn't open Usage Access settings", it)
                Toast.makeText(this, "Couldn’t open Usage Access settings.", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun syncWidgetsIfChanged(showToast: Boolean) {
        val hasAccess = Obsidian.hasUsageAccess(this)
        if (hasAccess == lastAccess) return
        lastAccess = hasAccess
        TasksWidget.refreshSyncStatus(this)
        if (showToast && hasAccess) {
            Toast.makeText(applicationContext, R.string.usage_access_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_LEFT = "left_for_settings"
    }
}
