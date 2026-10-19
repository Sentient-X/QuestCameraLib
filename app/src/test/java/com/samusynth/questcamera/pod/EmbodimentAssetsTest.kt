package com.samusynth.questcamera.pod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class EmbodimentAssetsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val mesh = "solid mesh".toByteArray()
    private val urdf = "<robot/>".toByteArray()

    private fun packaged(meshDigest: String = sha(mesh), path: String = "yubi_description/meshes/a.stl"): Map<String, ByteArray> {
        val manifest = """[
            {"logical_path":"$path","sha256":"$meshDigest","byte_size":${mesh.size}},
            {"logical_path":"yubi_description/urdf/yubi.urdf","sha256":"${sha(urdf)}","byte_size":${urdf.size}}
        ]""".toByteArray()
        return mapOf("manifest.json" to manifest, path to mesh, "yubi_description/urdf/yubi.urdf" to urdf)
    }

    private fun opener(files: Map<String, ByteArray>): (String) -> java.io.InputStream =
        { path -> files[path]?.inputStream() ?: throw FileNotFoundException(path) }

    @Test
    fun installsOnceThenIsCurrent() {
        val target = tmp.newFolder("sx-embodiments")
        val first = EmbodimentAssets.install(opener(packaged()), target)
        assertTrue(first, first.contains("\"state\":\"ready\"") && first.contains("\"copied\":2"))
        assertTrue(File(target, "yubi_description/meshes/a.stl").readBytes().contentEquals(mesh))
        assertTrue(File(target, "manifest.json").isFile)
        val second = EmbodimentAssets.install(opener(packaged()), target)
        assertTrue(second, second.contains("\"copied\":0"))
    }

    @Test
    fun aDamagedFileIsReinstalled() {
        val target = tmp.newFolder("sx-embodiments")
        EmbodimentAssets.install(opener(packaged()), target)
        File(target, "yubi_description/urdf/yubi.urdf").writeText("x")
        val again = EmbodimentAssets.install(opener(packaged()), target)
        assertTrue(again, again.contains("\"copied\":2"))
        assertTrue(File(target, "yubi_description/urdf/yubi.urdf").readBytes().contentEquals(urdf))
    }

    @Test
    fun aDigestMismatchFailsAndWritesNoManifest() {
        val target = tmp.newFolder("sx-embodiments")
        try {
            EmbodimentAssets.install(opener(packaged(meshDigest = "0".repeat(64))), target)
            fail("expected a digest failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("a.stl"))
        }
        assertFalse(File(target, "manifest.json").exists())
        assertFalse(File(target, "yubi_description/meshes/a.stl").exists())
    }

    @Test
    fun aPathOutsideTheDirectoryIsRefused() {
        val target = tmp.newFolder("sx-embodiments")
        try {
            EmbodimentAssets.install(opener(packaged(path = "../escape.stl")), target)
            fail("expected an unsafe path failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("unsafe"))
        }
    }

    @Test
    fun anApkWithoutMeshesIsAbsent() {
        assertEquals("{\"state\":\"absent\"}", EmbodimentAssets.install(opener(emptyMap()), tmp.newFolder("x")))
    }
}
