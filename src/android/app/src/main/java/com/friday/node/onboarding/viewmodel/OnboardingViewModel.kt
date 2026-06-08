package com.friday.node.onboarding.viewmodel

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.friday.node.config.OnboardingConfigManager
import com.friday.node.data.remote.OnboardingService
import com.friday.node.onboarding.model.OnboardingState
import com.friday.node.onboarding.model.SensorModule
import com.friday.node.permissions.OnboardingPermissionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for Onboarding Flow
 */
class OnboardingViewModel(
    private val configManager: OnboardingConfigManager,
    private val onboardingService: OnboardingService
) : ViewModel() {
    
    private val TAG = "OnboardingViewModel"
    
    private val _uiState = MutableStateFlow(OnboardingState())
    val uiState: StateFlow<OnboardingState> = _uiState.asStateFlow()
    
    init {
        loadInitialModules()
    }
    
    private fun loadInitialModules() {
        val initialModules = listOf(
            SensorModule(
                id = 1,
                moduleNumber = "MODULE 01",
                title = "Location & Environment",
                description = "Monitors localized ambient dynamics, weather patterns, and cellular baseline drifts.",
                icon = "location",
                color = "#4E9F3D",
                permissionRequired = android.Manifest.permission.ACCESS_FINE_LOCATION
            ),
            SensorModule(
                id = 2,
                moduleNumber = "MODULE 02",
                title = "Accessibility & Touch",
                description = "Monitors foreground application switches and calculates device interaction speed indicators.",
                icon = "accessibility",
                color = "#3282B8",
                permissionRequired = android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            ),
            SensorModule(
                id = 3,
                moduleNumber = "MODULE 03",
                title = "Notification & Activity",
                description = "Intercepts incoming application events and matches contextual behavioral alerts.",
                icon = "notification",
                color = "#FF7B54",
                permissionRequired = android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
            )
        )
        _uiState.update { it.copy(modules = initialModules) }
    }
    
    /**
     * Toggle status of a sensor module (enable/disable)
     */
    fun toggleModule(moduleId: Int) {
        _uiState.update { state ->
            val updated = state.modules.map { module ->
                if (module.id == moduleId) {
                    module.copy(isEnabled = !module.isEnabled)
                } else {
                    module
                }
            }
            state.copy(modules = updated)
        }
    }
    
    /**
     * Move to next step in onboarding
     */
    fun nextStep() {
        _uiState.update { state ->
            val nextStepVal = state.currentStep + 1
            state.copy(currentStep = nextStepVal)
        }
    }
    
    /**
     * Move to previous step in onboarding
     */
    fun previousStep() {
        _uiState.update { state ->
            if (state.currentStep > 0) {
                state.copy(currentStep = state.currentStep - 1)
            } else {
                state
            }
        }
    }
    
    /**
     * Request permissions for enabled modules
     */
    fun requestPermissions(activity: Activity) {
        val handler = OnboardingPermissionHandler(activity)
        handler.requestPermissionsForModules(_uiState.value.modules)
    }

    /**
     * Request permission for a single module
     */
    fun requestPermissionForModule(activity: Activity, moduleId: Int) {
        val module = _uiState.value.modules.find { it.id == moduleId } ?: return
        val handler = OnboardingPermissionHandler(activity)
        handler.requestPermissionForModule(module)
    }
    
    /**
     * Checks permissions status for each module
     */
    fun checkPermissionsStatus(activity: Activity) {
        val handler = OnboardingPermissionHandler(activity)
        _uiState.update { state ->
            val updated = state.modules.map { module ->
                val permission = when (module.title) {
                    "Location & Environment" -> android.Manifest.permission.ACCESS_FINE_LOCATION
                    "Accessibility & Touch" -> android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
                    "Notification & Activity" -> android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
                    else -> module.permissionRequired
                }
                val hasPerm = handler.hasPermission(permission)
                module.copy(
                    statusMessage = if (hasPerm) "GRANTED & ACTIVE" else "WAITING FOR SYNC",
                    isEnabled = if (hasPerm) true else module.isEnabled
                )
            }
            state.copy(modules = updated)
        }
    }

    /**
     * Set user moniker
     */
    fun setUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
    }

    /**
     * Set user date of birth
     */
    fun setUserDob(dob: String) {
        _uiState.update { it.copy(userDob = dob) }
    }
    
    /**
     * Initialize FRIDAY sensing node
     */
    fun initializeSensingNode(deviceId: String, onComplete: () -> Unit) {
        if (_uiState.value.isInitializing) return
        
        _uiState.update { it.copy(isInitializing = true) }
        
        viewModelScope.launch {
            try {
                Log.d(TAG, "Initializing sensing node. Saving module configurations...")
                // 1. Save config to SharedPreferences
                configManager.saveModulesConfiguration(_uiState.value.modules)
                
                // 1b. Save user profile moniker and DOB
                configManager.saveUserProfile(_uiState.value.userName, _uiState.value.userDob)
                
                // Simulate initialization progress steps to look premium
                _uiState.update { state ->
                    val updated = state.modules.map { module ->
                        if (module.isEnabled) module.copy(statusMessage = "SYNCING WITH HUB...") else module
                    }
                    state.copy(modules = updated)
                }
                delay(1000)
                
                _uiState.update { state ->
                    val updated = state.modules.map { module ->
                        if (module.isEnabled) module.copy(statusMessage = "ESTABLISHING AMBIENT LINK...") else module
                    }
                    state.copy(modules = updated)
                }
                delay(1200)
                
                // 2. Mark onboarding as complete in prefs
                configManager.markOnboardingComplete()
                
                // 3. Notify backend
                onboardingService.notifyBackendOfCompletion(deviceId)
                
                _uiState.update { it.copy(isInitializing = false, isComplete = true) }
                Log.i(TAG, "Initialization finished successfully!")
                
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed initialization: ${e.message}")
                _uiState.update { it.copy(isInitializing = false, error = e.localizedMessage) }
            }
        }
    }
}

/**
 * Factory class for OnboardingViewModel
 */
class OnboardingViewModelFactory(
    private val configManager: OnboardingConfigManager,
    private val onboardingService: OnboardingService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OnboardingViewModel(configManager, onboardingService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
