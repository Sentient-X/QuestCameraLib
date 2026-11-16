package com.samusynth.questcamera.io

import android.Manifest
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samusynth.questcamera.core.CameraPermissionManager
import com.samusynth.questcamera.core.CameraSessionManager
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device camera gate (sx#4210): both passthrough cameras record ~15 s through the real
 * Camera2 + MediaCodec + encoder-output-thread pipeline, stop, and each eye must leave a finished
 * MP4, one timestamp row per encoded frame and no capture error. No tracking gate, no wearer:
 * OQC's scripts/device_smoke.sh runs it over adb before any APK is handed over.
 *
 * Builds 74 and 75 passed every host test and failed the first real recording on pod5. The JNI
 * descriptors OQC calls are held separately (UnityJniAbiTest, OQC check_jni_abi.py and the app's
 * own camera self-test); this test is the pipeline on the hardware.
 *
 * Run: ./gradlew :app:assembleDebugAndroidTest, install the test APK, then
 * `adb shell am instrument -w -e class com.samusynth.questcamera.io.TwoCameraRecordingTest
 * com.samusynth.questcamera.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class TwoCameraRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Eye(val name: String, val id: String, val recorder: VideoRecorderSurfaceProvider,
                      val session: CameraSessionManager, val mp4: File, val timestamps: File)

    @Test
    fun bothCamerasRecordFifteenSecondsAndFinalize() {
        val pkg = context.packageName
        for (permission in listOf(Manifest.permission.CAMERA, "horizonos.permission.HEADSET_CAMERA")) {
            instrumentation.uiAutomation.grantRuntimePermission(pkg, permission)
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, CameraGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val outDir = File(context.getExternalFilesDir(null), "camera_gate").apply { deleteRecursively(); mkdirs() }
        // OQC's order (CameraPermissionManager.cs): ask, then read the passthrough cameras the
        // granted callback finds.
        val permissions = CameraPermissionManager(activity)
        permissions.requestCameraPermissionIfNeeded()
        val foundBy = System.nanoTime() + 10_000_000_000L
        while ((permissions.getCameraManager() == null || permissions.getRightCameraId().isEmpty())
            && System.nanoTime() < foundBy) Thread.sleep(100)
        val cameraManager = permissions.getCameraManager()
        assertNotNull("no CameraManager", cameraManager)
        cameraManager!!
        val eyes = listOf(
            "left" to permissions.getLeftCameraId(),
            "right" to permissions.getRightCameraId(),
        ).map { (name, id) ->
            assertTrue("no $name passthrough camera id (HEADSET_CAMERA granted?)", id.isNotEmpty())
            val chars = cameraManager.getCameraCharacteristics(id)
            val size = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
            val mp4 = File(outDir, "${name}_camera.mp4")
            val timestamps = File(outDir, "${name}_camera_timestamps.csv")
            // OQC's recorder arguments (VideoRecorderSurfaceProvider.cs): sensor pixel array in,
            // 720x720 out, 30 fps, 4 Mbps, 1 s GOP, no audio.
            val recorder = VideoRecorderSurfaceProvider(
                size.width, size.height, 720, 720, mp4.path, timestamps.path, 30, 4, 1, false, 0, 0,
            )
            val session = CameraSessionManager()
            session.registerSurfaceProvider(recorder)
            session.setCaptureTemplateFromString("VIDEO_SNAPSHOT") // OQC scene: useCase 3
            session.openCamera(activity, cameraManager, id)
            Eye(name, id, recorder, session, mp4, timestamps)
        }
        try {
            val openBy = System.nanoTime() + 10_000_000_000L
            while (eyes.any { !it.session.isOpen() } && System.nanoTime() < openBy) Thread.sleep(100)
            for (eye in eyes) assertTrue("${eye.name} camera ${eye.id} did not open in 10 s", eye.session.isOpen())
            Thread.sleep(1_000)
            for (eye in eyes) {
                eye.recorder.updateOutputFile(eye.mp4.path, eye.timestamps.path)
                eye.recorder.startRecording()
            }
            Thread.sleep(15_000)
            for (eye in eyes) assertEquals("${eye.name} failed while recording", "", eye.recorder.getCaptureError())
            for (eye in eyes) eye.recorder.requestStopRecording()
            val failures = eyes.mapNotNull { eye ->
                runCatching { eye.recorder.stopRecording() }.exceptionOrNull()?.let { "${eye.name}: $it" }
            }
            assertEquals("stopRecording threw", emptyList<String>(), failures)

            for (eye in eyes) {
                val error = eye.recorder.getCaptureError()
                assertEquals("${eye.name} capture_error", "", error)
                val selected = eye.recorder.getSelectedFrameCount()
                val rows = eye.timestamps.readLines().drop(1).count { it.isNotBlank() }
                val boxes = topLevelBoxes(eye.mp4)
                Log.i(TAG, "gate ${eye.name}: selected=$selected rows=$rows mp4=${eye.mp4.length()} B boxes=${boxes.groupingBy { it }.eachCount()} report=${eye.recorder.getCaptureReportJson()}")
                assertTrue("${eye.name}: $selected frames in 15 s (expected ~450)", selected in 400..470)
                assertEquals("${eye.name}: timestamp rows vs encoded frames", selected, rows.toLong())
                assertEquals("${eye.name}: MP4 does not start with ftyp, moov", listOf("ftyp", "moov"), boxes.take(2))
                assertTrue("${eye.name}: MP4 holds no fragment", "moof" in boxes && "mdat" in boxes)
            }
            Log.i(TAG, "CAMERA GATE PASS: both eyes finalized")
        } finally {
            for (eye in eyes) {
                runCatching { eye.session.close() }
                runCatching { eye.recorder.close() }
            }
            activity.finish()
        }
    }

    /** Top-level box types; fails unless the boxes tile the file exactly (a finished MP4). */
    private fun topLevelBoxes(file: File): List<String> {
        assertTrue("${file.name} missing or empty", file.length() > 0)
        val types = mutableListOf<String>()
        RandomAccessFile(file, "r").use { f ->
            var at = 0L
            while (at < f.length()) {
                f.seek(at)
                var size = f.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                if (size == 1L) size = f.readLong()
                assertTrue("${file.name}: box '$type' at $at has size $size", size >= 8 && at + size <= f.length())
                types += type
                at += size
            }
            assertEquals("${file.name}: boxes end past the file", f.length(), at)
        }
        return types
    }

    private companion object {
        const val TAG = "CameraGate"
    }
}
