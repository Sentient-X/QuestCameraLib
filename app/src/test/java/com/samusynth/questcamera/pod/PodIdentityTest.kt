package com.samusynth.questcamera.pod

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class PodIdentityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun oneApkIsItsSha256() {
        val base = tmp.newFile("base.apk").apply { writeBytes("capture app".toByteArray()) }
        assertEquals("sha256:" + hex("capture app".toByteArray()), PodIdentity.buildIdOf(listOf(base)))
    }

    @Test
    fun aSplitSetHashesTheHexDigestsBaseFirst() {
        val split = tmp.newFile("split_config.arm64_v8a.apk").apply { writeBytes("split".toByteArray()) }
        val base = tmp.newFile("base.apk").apply { writeBytes("base".toByteArray()) }
        val expected = hex((hex("base".toByteArray()) + hex("split".toByteArray())).toByteArray())
        assertEquals("sha256:$expected", PodIdentity.buildIdOf(listOf(split, base)))
    }
}
