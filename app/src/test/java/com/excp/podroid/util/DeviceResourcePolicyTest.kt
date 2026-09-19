package com.excp.podroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceResourcePolicyTest {

    @Test
    fun balancedRamMb_scalesWithDevice() {
        assertEquals(512, DeviceResourcePolicy.balancedRamMb(2_048))
        assertEquals(1024, DeviceResourcePolicy.balancedRamMb(4_096))
        assertEquals(2048, DeviceResourcePolicy.balancedRamMb(8_192))
        assertEquals(4096, DeviceResourcePolicy.balancedRamMb(16_384))
    }

    @Test
    fun balancedCpus_usesHalfCappedAtFour() {
        assertEquals(1, DeviceResourcePolicy.balancedCpus(1))
        assertEquals(2, DeviceResourcePolicy.balancedCpus(4))
        assertEquals(4, DeviceResourcePolicy.balancedCpus(8))
        assertEquals(4, DeviceResourcePolicy.balancedCpus(12))
    }

    @Test
    fun balancedStorageGb_respectsAvailableSpace() {
        assertEquals(2, DeviceResourcePolicy.balancedStorageGb(4))
        assertEquals(8, DeviceResourcePolicy.balancedStorageGb(40))
        assertEquals(128, DeviceResourcePolicy.balancedStorageGb(512))
        // 25% of 1024 GB lands exactly on the 256 GB option, proving the cap
        // still follows the 25% rule at the top of the longer list.
        assertEquals(256, DeviceResourcePolicy.balancedStorageGb(1024))
    }

    @Test
    fun nearestAtMost_picksLargestOptionNotExceedingTarget() {
        assertEquals(4, DeviceResourcePolicy.nearestAtMost(listOf(2, 4, 8), 5))
        assertEquals(2, DeviceResourcePolicy.nearestAtMost(listOf(2, 4, 8), 1))
    }

    @Test
    fun ramOptionsFor_capsToLeaveHeadroomForAndroid() {
        // 6 GB device: only options <= 3072 MB fit, but the floor guarantees
        // at least the first three options anyway.
        assertEquals(listOf(512, 1024, 2048), DeviceResourcePolicy.ramOptionsFor(6_144))
    }

    @Test
    fun ramOptionsFor_returnsFullListOnBigDevice() {
        assertEquals(DeviceResourcePolicy.RAM_OPTIONS_MB, DeviceResourcePolicy.ramOptionsFor(24_576))
    }

    @Test
    fun ramOptionsFor_neverDropsBelowThreeOptions() {
        assertEquals(listOf(512, 1024, 2048), DeviceResourcePolicy.ramOptionsFor(2_048))
    }

    @Test
    fun storageOptionsFor_capsToFreeSpace() {
        assertEquals(listOf(2, 4, 8, 16, 32, 64, 128), DeviceResourcePolicy.storageOptionsFor(197))
    }

    @Test
    fun storageOptionsFor_returnsFullListOnBigDevice() {
        assertEquals(DeviceResourcePolicy.STORAGE_OPTIONS_GB, DeviceResourcePolicy.storageOptionsFor(2_048))
    }

    @Test
    fun storageOptionsFor_neverDropsBelowThreeOptions() {
        assertEquals(listOf(2, 4, 8), DeviceResourcePolicy.storageOptionsFor(1))
    }
}
