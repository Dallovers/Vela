package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceAvailabilityTest {
    private val packageName = "com.dallovers.phoneagent"

    @Test fun connectedIsReadyRegardlessOfTheList() {
        assertEquals(ServiceAvailability.READY, ServiceAvailabilityRules.of(true, emptyList(), packageName))
        assertEquals(ServiceAvailability.READY, ServiceAvailabilityRules.of(true, listOf(packageName), packageName))
    }

    @Test fun listedButNotAttachedIsStarting() {
        assertEquals(ServiceAvailability.STARTING, ServiceAvailabilityRules.of(false, listOf(packageName), packageName))
    }

    // A force-stop leaves enabled_accessibility_services empty, so this is the state right after one.
    @Test fun missingFromTheListIsDisabled() {
        assertEquals(ServiceAvailability.DISABLED, ServiceAvailabilityRules.of(false, emptyList(), packageName))
    }

    @Test fun otherServicesDoNotCount() {
        assertEquals(ServiceAvailability.DISABLED, ServiceAvailabilityRules.of(false,
            listOf("com.android.talkback", "com.dallovers.phoneagent2", "com.dallovers.phoneagent.extra"), packageName))
    }

    @Test fun surroundingWhitespaceInTheListIsIgnored() {
        assertEquals(ServiceAvailability.STARTING, ServiceAvailabilityRules.of(false, listOf("  $packageName  "), packageName))
    }
}
