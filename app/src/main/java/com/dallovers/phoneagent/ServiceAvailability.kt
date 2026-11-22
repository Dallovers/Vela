package com.dallovers.phoneagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Why the operation service is not usable right now.
 *
 * `connected` alone cannot tell the two apart: a force-stop removes the service from the
 * enabled accessibility list (the secure setting becomes `null`), so the user has to switch
 * it back on in Settings, while a service that is switched on but still starting up needs
 * nothing but a moment. Telling them "it is not connected" in both cases sends the first
 * group looking for a button the app never offers.
 */
internal enum class ServiceAvailability {
    /** Listed as enabled and attached: actions are available. */
    READY,

    /** Still listed as enabled, but the service has not attached yet. */
    STARTING,

    /** Not in the enabled list: the system switched it off, the user must re-enable it. */
    DISABLED,
}

internal object ServiceAvailabilityRules {
    /**
     * [enabledPackages] is the set of package names the system reports in its enabled
     * accessibility service list.
     */
    fun of(connected: Boolean, enabledPackages: Collection<String>, packageName: String): ServiceAvailability = when {
        connected -> ServiceAvailability.READY
        enabledPackages.any { it.trim() == packageName } -> ServiceAvailability.STARTING
        else -> ServiceAvailability.DISABLED
    }
}

/** Package names of the accessibility services the system currently has switched on. */
internal fun Context.enabledAccessibilityPackages(): List<String> = try {
    getSystemService(AccessibilityManager::class.java)
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
        ?.distinct()
        .orEmpty()
} catch (_: Exception) {
    // Reading the list must never take the screen down; an unknown state falls back to DISABLED
    // only when connected is false, which is where we already are.
    emptyList()
}
