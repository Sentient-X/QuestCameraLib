package com.samusynth.questcamera.pod

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs the embodiment meshes the on-headset seal checks: the APK carries them under
 * `assets/sx-embodiments/` (Unity StreamingAssets, stored uncompressed) with `manifest.json`
 * `[{logical_path, sha256, byte_size}]`; libsx_rig reads them from a real directory,
 * `<filesDir>/sx-embodiments/<logical_path>`, passed as `embodiment_assets_dir`.
 *
 * Installation runs when the installed manifest differs from the APK's (first launch, an APK
 * with new meshes) or a listed file is missing or the wrong size. Each file is copied to a
 * temporary name, its SHA-256 checked against the manifest while copying, fsynced, and
 * renamed; the manifest is written last, so an interrupted install is redone next launch.
 */
object EmbodimentAssets {
    private const val TAG = "EmbodimentAssets"
    const val ASSET_DIR = "sx-embodiments"
    private const val MANIFEST = "manifest.json"

    @Volatile
    private var stateJson: String = state("pending")
    private var started = false

    /** `<filesDir>/sx-embodiments`, where the rig reads the meshes. */
    @JvmStatic
    fun installDir(context: Context): String = File(context.filesDir, ASSET_DIR).absolutePath

    /** Starts the install once, on a background thread. */
    @JvmStatic
    fun installAsync(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        stateJson = state("installing")
        Thread({
            stateJson = try {
                install({ path -> app.assets.open("$ASSET_DIR/$path") }, File(installDir(app)))
            } catch (failure: Throwable) {
                Log.e(TAG, "Embodiment asset install failed", failure)
                state("failed", failure.toString())
            }
        }, "EmbodimentAssets").start()
    }

    /**
     * `{"state": "pending" | "installing" | "ready" | "absent" | "failed", "assets"?,
     * "copied"?, "error"?}`; `absent` means this APK carries no meshes.
     */
    @JvmStatic
    fun stateJson(): String = stateJson

    /**
     * Installs from [open] (paths relative to the packaged directory) into [target].
     * Returns the final state JSON; throws on an I/O or digest failure.
     */
    internal fun install(open: (String) -> InputStream, target: File): String {
        val manifestBytes = try {
            open(MANIFEST).use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return state("absent")
        }
        val entries = (Json.parseToJsonElement(manifestBytes.decodeToString()) as? JsonArray)
            ?.map { entry ->
                val fields = entry.jsonObject
                Entry(
                    logicalPath = safePath(fields.getValue("logical_path").jsonPrimitive.content),
                    sha256 = fields.getValue("sha256").jsonPrimitive.content.lowercase(),
                    byteSize = fields.getValue("byte_size").jsonPrimitive.long,
                )
            } ?: throw IOException("$MANIFEST is not a JSON array")

        val installedManifest = File(target, MANIFEST)
        val current = installedManifest.isFile &&
            installedManifest.readBytes().contentEquals(manifestBytes) &&
            entries.all { File(target, it.logicalPath).let { f -> f.isFile && f.length() == it.byteSize } }
        if (current) return state("ready", assets = entries.size, copied = 0)

        target.mkdirs()
        installedManifest.delete() // an interrupted install is never mistaken for a finished one
        for (entry in entries) {
            val destination = File(target, entry.logicalPath)
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            open(entry.logicalPath).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        size += read
                    }
                    output.fd.sync()
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != entry.sha256 || size != entry.byteSize) {
                temporary.delete()
                throw IOException(
                    "${entry.logicalPath}: packaged bytes are $size B sha256 $hex, manifest says " +
                        "${entry.byteSize} B sha256 ${entry.sha256}",
                )
            }
            if (!temporary.renameTo(destination)) throw IOException("cannot rename into ${destination.path}")
        }
        val temporaryManifest = File(target, "$MANIFEST.tmp")
        FileOutputStream(temporaryManifest).use { it.write(manifestBytes); it.fd.sync() }
        if (!temporaryManifest.renameTo(installedManifest)) throw IOException("cannot rename $MANIFEST")
        return state("ready", assets = entries.size, copied = entries.size)
    }

    private data class Entry(val logicalPath: String, val sha256: String, val byteSize: Long)

    /** A relative path inside the install directory; never absolute, never `..`. */
    private fun safePath(path: String): String {
        val parts = path.split('/')
        if (path.isEmpty() || path.startsWith("/") || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("unsafe logical_path in $MANIFEST: $path")
        }
        return path
    }

    private fun state(name: String, error: String? = null, assets: Int? = null, copied: Int? = null): String =
        buildJsonObject {
            put("state", name)
            assets?.let { put("assets", it) }
            copied?.let { put("copied", it) }
            error?.let { put("error", it) }
        }.toString()
}
