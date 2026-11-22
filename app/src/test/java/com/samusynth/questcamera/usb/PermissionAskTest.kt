package com.samusynth.questcamera.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAskTest {
    @Test
    fun a_plugged_device_is_left_to_the_system_grant() {
        val ask = PermissionAsk(presentAtStart = emptySet())
        // The 2026-11-22 race: the bridge saw the camera 36 ms before the system granted it.
        assertFalse(ask.shouldAsk("cam#6", 0))
        assertFalse(ask.shouldAsk("cam#6", 36))
        assertFalse(ask.shouldAsk("cam#6", PermissionAsk.ATTACHED_GRACE_MS - 1))
        assertTrue(ask.shouldAsk("cam#6", PermissionAsk.ATTACHED_GRACE_MS))
        assertFalse("asked once per attachment", ask.shouldAsk("cam#6", 60_000))
    }

    @Test
    fun a_device_there_at_start_is_asked_soon() {
        val ask = PermissionAsk(presentAtStart = setOf("enc#3"))
        assertFalse(ask.shouldAsk("enc#3", 0))
        assertTrue(ask.shouldAsk("enc#3", PermissionAsk.PRESENT_GRACE_MS))
    }

    @Test
    fun a_replug_starts_its_own_wait() {
        val ask = PermissionAsk(presentAtStart = emptySet())
        assertFalse(ask.shouldAsk("enc#3", 0))
        ask.retain(emptySet())
        assertFalse(ask.shouldAsk("enc#3", 30_000))
        assertTrue(ask.shouldAsk("enc#3", 30_000 + PermissionAsk.ATTACHED_GRACE_MS))
    }
}
