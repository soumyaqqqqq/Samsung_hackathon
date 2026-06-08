package com.friday.node.onboarding.model

data class SensorModule(
    val id: Int,
    val moduleNumber: String,
    val title: String,
    val description: String,
    val icon: String,
    val color: String,
    val permissionRequired: String,
    var isEnabled: Boolean = false,
    var statusMessage: String = "WAITING FOR SYNC"
)

data class OnboardingState(
    val modules: List<SensorModule> = emptyList(),
    val isInitializing: Boolean = false,
    val isComplete: Boolean = false,
    val currentStep: Int = 0,
    val error: String? = null,
    val userName: String = "",
    val userDob: String = ""
)

enum class OnboardingStep {
    HERO,
    IDENTITY,
    PERMISSIONS,
    INITIALIZATION,
    COMPLETE
}
