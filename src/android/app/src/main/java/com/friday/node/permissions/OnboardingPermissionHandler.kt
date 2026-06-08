package com.friday.node.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.friday.node.onboarding.model.SensorModule

/**
 * Handles permission requests from onboarding
 */
class OnboardingPermissionHandler(private val activity: Activity) {
    
    companion object {
        private const val REQUEST_CODE = 100
        private const val TAG = "OnboardingPermissionH"
    }
    
    /**
     * Request permissions for enabled modules
     */
    fun requestPermissionsForModules(modules: List<SensorModule>) {
        val enabledModules = modules.filter { it.isEnabled }
        
        if (enabledModules.isEmpty()) {
            Log.d(TAG, "No modules enabled - no permissions to request")
            return
        }
        
        val permissionsToRequest = mutableListOf<String>()
        
        enabledModules.forEach { module ->
            val permission = getPermissionForModule(module)
            if (permission != null) {
                // If it's a runtime permission, check and add to requests
                if (isRuntimePermission(permission)) {
                    if (!hasPermission(permission)) {
                        permissionsToRequest.add(permission)
                        Log.d(TAG, "Will request runtime permission: $permission for ${module.title}")
                    }
                } else {
                    // For system settings permissions (accessibility/notification listener), 
                    // we launch their respective settings screens.
                    launchSettingsScreenForPermission(permission)
                }
            }
        }
        
        // Also request POST_NOTIFICATIONS defensively on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting ${permissionsToRequest.size} runtime permissions")
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                REQUEST_CODE
            )
        }
    }
    
    /**
     * Request permission for a single module
     */
    fun requestPermissionForModule(module: SensorModule) {
        val permission = getPermissionForModule(module) ?: return
        
        if (isRuntimePermission(permission)) {
            if (!hasPermission(permission)) {
                Log.d(TAG, "Requesting single runtime permission: $permission for ${module.title}")
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(permission),
                    REQUEST_CODE
                )
            }
        } else {
            // Also request POST_NOTIFICATIONS defensively on Android 13+ if this is the Notification module
            if (permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_CODE
                        )
                    }
                }
            }
            // For system settings permissions (accessibility/notification listener), 
            // launch their settings screens.
            launchSettingsScreenForPermission(permission)
        }
    }
    
    /**
     * Handle permission request results
     */
    fun handlePermissionResults(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Map<String, Boolean> {
        if (requestCode != REQUEST_CODE) {
            return emptyMap()
        }
        
        val results = mutableMapOf<String, Boolean>()
        
        permissions.forEachIndexed { index, permission ->
            val isGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
            results[permission] = isGranted
            Log.d(TAG, "Permission ${permission}: ${if (isGranted) "GRANTED" else "DENIED"}")
        }
        
        return results
    }
    
    /**
     * Check if permission is granted
     */
    fun hasPermission(permission: String): Boolean {
        return when (permission) {
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> {
                isAccessibilityServiceEnabled(activity, com.friday.node.service.FRIDAYAccessibilityService::class.java)
            }
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> {
                val enabledListeners = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
                enabledListeners != null && enabledListeners.contains(activity.packageName)
            }
            else -> {
                ContextCompat.checkSelfPermission(
                    activity,
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
    
    private fun isRuntimePermission(permission: String): Boolean {
        return permission != Manifest.permission.BIND_ACCESSIBILITY_SERVICE &&
               permission != Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
    }
    
    private fun launchSettingsScreenForPermission(permission: String) {
        try {
            when (permission) {
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> {
                    Log.d(TAG, "Redirecting user to Accessibility Settings")
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    activity.startActivity(intent)
                }
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> {
                    Log.d(TAG, "Redirecting user to Notification Listener Settings")
                    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    } else {
                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    }.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    activity.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch settings screen: ${e.message}")
        }
    }
    
    /**
     * Map module to required permission
     */
    private fun getPermissionForModule(module: SensorModule): String? {
        return when (module.title) {
            "Location & Environment" -> Manifest.permission.ACCESS_FINE_LOCATION
            "Accessibility & Touch" -> Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            "Notification & Activity" -> Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
            else -> if (module.permissionRequired.isNotEmpty()) module.permissionRequired else null
        }
    }
    
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }
}
