package com.excp.podroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionsTest {

    @Test
    fun applicable_excludesNotificationsBelowTiramisu() {
        assertEquals(false, AppPermissions.applicable(32).contains(AppPermission.NOTIFICATIONS))
        assertEquals(true, AppPermissions.applicable(33).contains(AppPermission.NOTIFICATIONS))
    }

    @Test
    fun applicable_includesBatteryOptimizationOnMarshmallow() {
        assertEquals(true, AppPermissions.applicable(26).contains(AppPermission.BATTERY_OPTIMIZATION))
    }

    @Test
    fun missing_returnsAllApplicableInDeclarationOrder_whenNoneGranted() {
        assertEquals(
            listOf(AppPermission.NOTIFICATIONS, AppPermission.BATTERY_OPTIMIZATION),
            AppPermissions.missing(33) { false },
        )
    }

    @Test
    fun missing_returnsEmpty_whenAllGranted() {
        assertEquals(emptyList<AppPermission>(), AppPermissions.missing(33) { true })
    }

    @Test
    fun missing_excludesOnlyTheGrantedOnes() {
        assertEquals(
            listOf(AppPermission.BATTERY_OPTIMIZATION),
            AppPermissions.missing(33) { it == AppPermission.NOTIFICATIONS },
        )
    }
}
