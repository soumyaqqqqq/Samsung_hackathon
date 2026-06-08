package com.friday.node.config

import android.content.Context
import com.friday.node.onboarding.model.SensorModule
import android.util.Log

/**
 * Manages onboarding configuration persistence
 * Saves which modules user enabled during onboarding
 */
class OnboardingConfigManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "friday_onboarding"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_MODULE_PREFIX = "module_"
        private const val TAG = "OnboardingConfigManager"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Save module configuration after onboarding
     */
    fun saveModulesConfiguration(modules: List<SensorModule>) {
        Log.d(TAG, "Saving module configuration for ${modules.size} modules")
        val editor = prefs.edit()
        modules.forEach { module ->
            val key = getModuleKey(module.title)
            editor.putBoolean(key, module.isEnabled)
            Log.d(TAG, "Module ${module.title} (${key}): ${module.isEnabled}")
        }
        editor.apply()
    }
    
    /**
     * Mark onboarding as complete
     */
    fun markOnboardingComplete() {
        prefs.edit().apply {
            putBoolean(KEY_ONBOARDING_COMPLETE, true)
            putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Onboarding marked as complete")
    }

    /**
     * Save user profile credentials
     */
    fun saveUserProfile(name: String, dob: String) {
        prefs.edit().apply {
            putString("user_name", name)
            putString("user_dob", dob)
            apply()
        }
        Log.d(TAG, "User profile saved: $name")
    }

    /**
     * Retrieve user name
     */
    fun getUserName(): String {
        return prefs.getString("user_name", "") ?: ""
    }
    
    /**
     * Check if onboarding is complete
     */
    fun isOnboardingComplete(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }
    
    /**
     * Get which modules are enabled
     */
    fun getEnabledModules(): List<String> {
        return listOf(
            "Location & Environment",
            "Accessibility & Touch",
            "Notification & Activity"
        ).filter { isModuleEnabled(it) }
    }
    
    /**
     * Check if specific module is enabled
     */
    fun isModuleEnabled(moduleName: String): Boolean {
        val key = getModuleKey(moduleName)
        return prefs.getBoolean(key, false)
    }
    
    /**
     * Get current configuration as map
     */
    fun getConfiguration(): Map<String, Boolean> {
        return mapOf(
            "location" to isModuleEnabled("Location & Environment"),
            "accessibility" to isModuleEnabled("Accessibility & Touch"),
            "notification" to isModuleEnabled("Notification & Activity")
        )
    }
    
    private fun getModuleKey(moduleName: String): String {
        return when (moduleName.lowercase().trim()) {
            "location & environment", "location" -> "${KEY_MODULE_PREFIX}location"
            "accessibility & touch", "accessibility" -> "${KEY_MODULE_PREFIX}accessibility"
            "notification & activity", "notification" -> "${KEY_MODULE_PREFIX}notification"
            else -> "$KEY_MODULE_PREFIX${moduleName.lowercase().replace(" ", "_").replace("&", "and")}"
        }
    }
}
