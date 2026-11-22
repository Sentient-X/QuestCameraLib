package com.samusynth.questcamera.usb

import com.samusynth.questcamera.usb.BusWitness.Identity
import com.samusynth.questcamera.usb.BusWitness.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusWitnessTest {
    private val camera = Identity(0x32E4, 0x9230, "/dev/bus/usb/001/007", null)
    private val encoder = Identity(0x303A, 0x1001, "/dev/bus/usb/001/005", "F0:F5:BD:01:02:03")

    private fun bus(vararg devices: Pair<String, Identity>) = mapOf(*devices)

    @Test
    fun the_first_observation_seeds_without_events() {
        val witness = BusWitness()
        assertEquals(emptyList<BusWitness.Event>(), witness.observe(bus("cam#6" to camera, "enc#4" to encoder), 1_000, 1_700_000_000_000))
        assertEquals(emptyList<BusWitness.Event>(), witness.events())
        assertEquals(0, witness.detachments())
    }

    @Test
    fun a_camera_that_leaves_and_returns_is_timed_off_the_bus() {
        // Pod 12, 2026-11-22 20:54 IST: the left camera's take died at 11.4 s, the next take
        // started 43 s later and recorded 105 s before the same words.
        val witness = BusWitness()
        witness.observe(bus("cam#6" to camera, "enc#4" to encoder), 1_000, 1_700_000_000_000)

        val gone = witness.observe(bus("enc#4" to encoder), 12_400, 1_700_000_011_400)
        assertEquals(1, gone.size)
        assertEquals(Kind.DETACHED, gone[0].kind)
        assertEquals("cam#6", gone[0].key)
        assertEquals(12_400L, gone[0].atElapsedMs)
        assertEquals(1_700_000_011_400L, gone[0].atEpochMs)
        assertNull(gone[0].afterMs)
        assertEquals(listOf("cam#6"), witness.missing().map { it.key })

        // A replug is a new key, at a new bus address, and a camera carries no serial.
        val replugged = camera.copy(deviceName = "/dev/bus/usb/001/008")
        val back = witness.observe(bus("enc#4" to encoder, "cam#7" to replugged), 14_700, 1_700_000_013_700)
        assertEquals(1, back.size)
        assertEquals(Kind.ATTACHED, back[0].kind)
        assertEquals("cam#7", back[0].key)
        assertEquals(2_300L, back[0].afterMs)
        assertTrue(witness.missing().isEmpty())
        assertEquals(1, witness.detachments())
        assertEquals(listOf(Kind.DETACHED, Kind.ATTACHED), witness.events().map { it.kind })
    }

    @Test
    fun a_return_answers_the_oldest_departure_of_its_model_and_never_another_model() {
        val witness = BusWitness()
        val right = camera.copy(deviceName = "/dev/bus/usb/001/009")
        witness.observe(bus("left#6" to camera, "right#8" to right, "enc#4" to encoder), 0, 0)
        witness.observe(bus("right#8" to right, "enc#4" to encoder), 1_000, 1_000)
        witness.observe(bus("enc#4" to encoder), 2_000, 2_000)
        assertEquals(2, witness.detachments())

        // The encoder's return cannot answer a camera's departure.
        val encoderBack = witness.observe(bus("enc#5" to encoder.copy(deviceName = "/dev/bus/usb/001/010"), "enc#4" to encoder), 2_500, 2_500)
        assertNull(encoderBack.single().afterMs)

        val oneCameraBack = witness.observe(
            bus("enc#4" to encoder, "enc#5" to encoder.copy(deviceName = "/dev/bus/usb/001/010"), "cam#10" to camera.copy(deviceName = "/dev/bus/usb/001/011")),
            5_000, 5_000,
        )
        assertEquals(4_000L, oneCameraBack.single().afterMs)
        assertEquals(listOf("right#8"), witness.missing().map { it.key })
    }

    @Test
    fun an_encoder_is_matched_by_its_serial() {
        val witness = BusWitness()
        val other = encoder.copy(deviceName = "/dev/bus/usb/001/006", serial = "F0:F5:BD:AA:BB:CC")
        witness.observe(bus("left#4" to encoder, "right#5" to other), 0, 0)
        witness.observe(bus("right#5" to other), 100, 100)
        val wrongSerial = witness.observe(bus("right#5" to other, "x#9" to other.copy(deviceName = "/dev/bus/usb/001/012")), 200, 200)
        assertNull("another encoder's return answers nothing", wrongSerial.single().afterMs)
        val sameSerial = witness.observe(
            bus("right#5" to other, "x#9" to other.copy(deviceName = "/dev/bus/usb/001/012"), "left#10" to encoder.copy(deviceName = "/dev/bus/usb/001/013")),
            900, 900,
        )
        assertEquals(800L, sameSerial.single().afterMs)
    }

    @Test
    fun the_record_is_bounded_and_the_count_is_not() {
        val witness = BusWitness(capacity = 4)
        witness.observe(bus("cam#1" to camera), 0, 0)
        for (i in 1..10) {
            witness.observe(bus(), i * 10L, i * 10L)
            witness.observe(bus("cam#${i + 1}" to camera), i * 10L + 5, i * 10L + 5)
        }
        assertEquals(10, witness.detachments())
        assertEquals(4, witness.events().size)
        assertEquals(listOf(90L, 95L, 100L, 105L), witness.events().map { it.atElapsedMs })
    }
}
