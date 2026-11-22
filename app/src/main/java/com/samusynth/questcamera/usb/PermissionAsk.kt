package com.samusynth.questcamera.usb

/**
 * When the bridge may ask the user for a rig device's USB permission itself.
 *
 * Plugging a device in while the app runs is Android's to answer: it sends
 * `USB_DEVICE_ATTACHED` to running receivers first and only then resolves the device's
 * handler, granting the permission to the app it was told to "always" open (Horizon OS keeps
 * one preference per camera model and one per encoder serial, `dumpsys usb`
 * `device_preferences`), or showing its own "open with" prompt, with that "always" box, for a
 * device it has no preference for. A request in between is a second, needless popup: pod
 * `3487C10J2S0LDW`, 2026-11-22 20:04:31, the bridge asked 36 ms after the camera attached and
 * 33 ms before the system's own grant, and the collector had to tap Allow; at the next replug
 * (20:07:03) the grant won the race and nothing showed.
 *
 * So a device that arrived while the bridge ran is asked for only once it has gone
 * [ATTACHED_GRACE_MS] without a grant (the system prompt was dismissed or never came), and a
 * device already on the bus when the bridge started (no attach is coming for it) after
 * [PRESENT_GRACE_MS]. Either way at most once per attachment.
 */
class PermissionAsk(private val presentAtStart: Set<String>) {
    private val firstSeen = HashMap<String, Long>()
    private val asked = HashSet<String>()

    /** Whether to call `requestPermission` now for the unpermitted attachment [key]. */
    fun shouldAsk(key: String, nowMs: Long): Boolean {
        val seen = firstSeen.getOrPut(key) { nowMs }
        val grace = if (key in presentAtStart) PRESENT_GRACE_MS else ATTACHED_GRACE_MS
        return nowMs - seen >= grace && asked.add(key)
    }

    /** Forget attachments no longer on the bus; a replug is a new key. */
    fun retain(present: Set<String>) {
        firstSeen.keys.retainAll(present)
        asked.retainAll(present)
    }

    companion object {
        const val PRESENT_GRACE_MS = 1_500L
        const val ATTACHED_GRACE_MS = 20_000L
    }
}
