package com.samusynth.questcamera.io

import com.samusynth.questcamera.usb.RigUsbBridge
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JNI descriptors OpenQuestCapture's C# calls (its JniSignatures tables; OQC's
 * scripts/check_jni_abi.py holds the same tables to the shipped AAR).
 *
 * pod5 2026-11-16, OQC build 75: a6e6bd3 changed stopRecording's body, Kotlin inferred `Unit?`
 * for the expression-bodied `fun stopRecording() = pipeline.stopRecording()`, and the method
 * compiled to ()Lkotlin/Unit;. OQC asked for ()V, so both eyes threw NoSuchMethodError at the
 * first stop and no MP4 was finalized; every host test passed, since Kotlin callers do not see
 * the return type.
 */
class UnityJniAbiTest {
    private fun descriptor(m: Method): String {
        fun d(c: Class<*>): String = when {
            c == Void.TYPE -> "V"
            c == java.lang.Boolean.TYPE -> "Z"
            c == java.lang.Integer.TYPE -> "I"
            c == java.lang.Long.TYPE -> "J"
            c == java.lang.Float.TYPE -> "F"
            c == java.lang.Double.TYPE -> "D"
            c == java.lang.Byte.TYPE -> "B"
            c.isArray -> "[" + d(c.componentType!!)
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        return "(" + m.parameterTypes.joinToString("") { d(it) } + ")" + d(m.returnType)
    }

    private fun publicDescriptors(c: Class<*>): Set<String> =
        c.methods.filter { it.declaringClass == c }.map { it.name + descriptor(it) }.toSet()

    @Test
    fun recorderMethodsOqcCallsKeepTheirDescriptors() {
        val have = publicDescriptors(VideoRecorderSurfaceProvider::class.java)
        val oqcCalls = listOf(
            "updateOutputFile(Ljava/lang/String;Ljava/lang/String;)V",
            "startRecording()V",
            "requestStopRecording()V",
            "stopRecording()V",
            "startQrScan()V",
            "stopQrScan()V",
            "getQrResult()Ljava/lang/String;",
            "getSourceFrameCount()J",
            "getSourceDroppedFrameCount()J",
            "getSelectedFrameCount()J",
            "getCaptureError()Ljava/lang/String;",
            "getCaptureReportJson()Ljava/lang/String;",
            "getStreamGeometryJson()Ljava/lang/String;",
            "close()V",
        )
        assertEquals("missing from VideoRecorderSurfaceProvider", emptyList<String>(), oqcCalls.filterNot { it in have })
    }

    @Test
    fun usbBridgeMethodsOqcCallsKeepTheirDescriptors() {
        val have = publicDescriptors(RigUsbBridge::class.java)
        val oqcCalls = listOf("refresh()Ljava/lang/String;", "release(Ljava/lang/String;)V", "close()V")
        assertEquals("missing from RigUsbBridge", emptyList<String>(), oqcCalls.filterNot { it in have })
    }

    @Test
    fun noUnityFacingMethodReturnsKotlinUnit() {
        for (c in listOf(VideoRecorderSurfaceProvider::class.java, RigUsbBridge::class.java)) {
            val boxed = c.methods.filter { it.returnType == Unit::class.java }.map { it.name }
            assertTrue("${c.simpleName} returns kotlin.Unit (JNI ()V will not find it): $boxed", boxed.isEmpty())
        }
    }
}
