package com.samusynth.questcamera.usb

/**
 * What the USB bus did to the rig's devices, by the clock, so a hand camera that died
 * `device disconnected` can be laid beside the bus.
 *
 * The words come from libsx_rig: `poll()` on the camera's usbfs fd returned `POLLERR|POLLHUP`,
 * which the kernel raises only once the device is gone from the bus (`usbdev_poll`, device
 * `NOTATTACHED`), so the stream alone already says the device left; what it cannot say is when
 * it came back and how often, and that is what tells a cable from a hub port from a camera
 * resetting itself, and any of them from the software. Pod 12, 2026-11-22: 22 of the fleet's 23
 * `device disconnected: USB device disconnected` takes (13 left camera, 9 right, 15:14-22:17 IST,
 * build 76, accepted takes between them; the other build-76 pods had none), and nothing on the
 * record said how long each camera was away.
 *
 * Fed from [RigUsbBridge.refresh], which runs on every attach and detach broadcast and on
 * Unity's poll: the first observation seeds the bus, and from then on a key that vanishes is a
 * [Kind.DETACHED] event and a key that appears an [Kind.ATTACHED] one, the latter paired with the
 * oldest departure of the same model (and serial, when both carry one) still unanswered, so its
 * [Event.afterMs] is how long that device was off the bus. Android's `deviceId` makes a replug a
 * new key, and a camera (`serial_number=null`) can only be matched by model.
 *
 * The record is bounded to [capacity] events; [detachments] counts every departure regardless.
 * A pure class: the bridge supplies the clocks.
 */
class BusWitness(private val capacity: Int = DEFAULT_CAPACITY) {
    enum class Kind { ATTACHED, DETACHED }

    /** One rig device as the bridge enumerated it. */
    data class Identity(val vendorId: Int, val productId: Int, val deviceName: String, val serial: String?) {
        /** Whether a device that came back may be this one: same model, and same serial when both have one. */
        fun sameDeviceAs(other: Identity): Boolean =
            vendorId == other.vendorId && productId == other.productId &&
                (serial == null || other.serial == null || serial == other.serial)
    }

    data class Event(
        val kind: Kind,
        val key: String,
        val device: Identity,
        /** `SystemClock.elapsedRealtime()` when the bridge saw it. */
        val atElapsedMs: Long,
        /** `System.currentTimeMillis()` when the bridge saw it, to lay beside the pod's record. */
        val atEpochMs: Long,
        /** For an attach: how long the matched departure left the device off the bus; null without one. */
        val afterMs: Long?,
    )

    private var seeded = false
    private val present = HashMap<String, Identity>()
    private val events = ArrayDeque<Event>()
    /** Departures no return has answered yet, oldest first. */
    private val unanswered = ArrayList<Event>()
    private var detachments = 0

    /**
     * Diff [now] against the last observation; returns the events this one produced, oldest first
     * (departures before arrivals), after recording them. The first call seeds and returns nothing.
     */
    fun observe(now: Map<String, Identity>, nowElapsedMs: Long, nowEpochMs: Long): List<Event> {
        if (!seeded) {
            seeded = true
            present.putAll(now)
            return emptyList()
        }
        val produced = ArrayList<Event>()
        for ((key, device) in present.entries.toList()) {
            if (key in now) continue
            present.remove(key)
            val event = Event(Kind.DETACHED, key, device, nowElapsedMs, nowEpochMs, null)
            detachments += 1
            unanswered.add(event)
            produced.add(event)
        }
        for ((key, device) in now) {
            if (key in present) continue
            present[key] = device
            val answered = unanswered.firstOrNull { it.device.sameDeviceAs(device) }
            if (answered != null) unanswered.remove(answered)
            produced.add(
                Event(Kind.ATTACHED, key, device, nowElapsedMs, nowEpochMs, answered?.let { nowElapsedMs - it.atElapsedMs })
            )
        }
        for (event in produced) {
            events.addLast(event)
            while (events.size > capacity) events.removeFirst()
        }
        return produced
    }

    /** The last [capacity] events, oldest first. */
    fun events(): List<Event> = events.toList()

    /** Every departure seen since the bridge started, bounded by nothing. */
    fun detachments(): Int = detachments

    /** Devices that left and have not come back, oldest departure first. */
    fun missing(): List<Event> = unanswered.toList()

    companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
