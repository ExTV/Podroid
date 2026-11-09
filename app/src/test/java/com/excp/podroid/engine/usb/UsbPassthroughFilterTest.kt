package com.excp.podroid.engine.usb

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPassthroughFilterTest {

    @Test
    fun `wifi adapter passes through`() {
        assertTrue(UsbPassthroughManager.isPassthroughCandidate(0, listOf(UsbConstants.USB_CLASS_VENDOR_SPEC)))
    }

    @Test
    fun `mass storage passes through`() {
        assertTrue(UsbPassthroughManager.isPassthroughCandidate(0, listOf(UsbConstants.USB_CLASS_MASS_STORAGE)))
    }

    @Test
    fun `keyboard stays with Android`() {
        assertFalse(UsbPassthroughManager.isPassthroughCandidate(0, listOf(UsbConstants.USB_CLASS_HID)))
    }

    @Test
    fun `composite device with any HID interface stays with Android`() {
        // A dock exposing keyboard + trackpad alongside audio: passing it through
        // would take the dock's input away from Android.
        assertFalse(
            UsbPassthroughManager.isPassthroughCandidate(
                0,
                listOf(UsbConstants.USB_CLASS_AUDIO, UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_AUDIO),
            )
        )
    }

    @Test
    fun `device-level HID class stays with Android`() {
        assertFalse(UsbPassthroughManager.isPassthroughCandidate(UsbConstants.USB_CLASS_HID, emptyList()))
    }

    @Test
    fun `passthrough is armed only on the qemu backend`() {
        assertTrue(UsbPassthroughManager.backendSupportsPassthrough("qemu"))
        assertFalse(UsbPassthroughManager.backendSupportsPassthrough("uml"))
        assertFalse(UsbPassthroughManager.backendSupportsPassthrough("avf"))
    }
}
