package com.samusynth.questcamera.pod

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * The facts a headset pod reports about itself to libsx_rig: where its machine credential
 * lives, its tailnet address, and the content identity of the installed capture app. Not its
 * serial: apps cannot read ro.serialno on the Quest 3S (measured), so provisioning writes it
 * into pod_config.json over adb. Every getter answers "" when the fact is unavailable; the caller decides whether that
 * refuses anything.
 */
object PodIdentity {
    private const val TAG = "PodIdentity"

    @Volatile
    private var apkBuildId: String = ""
    @Volatile
    private var apkBuildIdError: String = ""
    private var apkBuildIdStarted = false

    /** The app-private files directory (`/data/user/0/<package>/files`), never shared storage. */
    @JvmStatic
    fun privateFilesDir(context: Context): String = context.filesDir.absolutePath

    /** The IPv4 address in Tailscale's 100.64.0.0/10 range on any up interface, or "". */
    @JvmStatic
    fun tailscaleIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.address }
            .firstOrNull { (it[0].toInt() and 0xFF) == 100 && (it[1].toInt() and 0xC0) == 64 }
            ?.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }.getOrNull().orEmpty()

    /**
     * `sha256:<hex>` of the installed APK set, the identity the pod computes over adb (pod
     * `capture.rs` `apk_digest`, sx_embodiments `QuestApkBuildId`): one APK is its SHA-256;
     * a split set is the SHA-256 of the per-file hex digests concatenated, base.apk first,
     * then by path. Hashing takes a second, so the first call starts it on a thread and
     * answers ""; later calls answer the result.
     */
    @JvmStatic
    fun apkBuildId(context: Context): String {
        synchronized(this) {
            if (!apkBuildIdStarted) {
                apkBuildIdStarted = true
                val info = context.applicationInfo
                val paths = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
                Thread({
                    runCatching { apkBuildId = buildIdOf(paths.map(::File)) }
                        .onFailure {
                            apkBuildIdError = it.toString()
                            Log.e(TAG, "Unable to hash the installed APK", it)
                        }
                }, "PodIdentity-apk").start()
            }
        }
        return apkBuildId
    }

    /** Why [apkBuildId] has no answer, or "". */
    @JvmStatic
    fun apkBuildIdError(): String = apkBuildIdError

    internal fun buildIdOf(files: List<File>): String {
        val ordered = files.sortedWith(compareBy({ it.name != "base.apk" }, { it.path }))
        require(ordered.isNotEmpty()) { "no APK path" }
        val digests = ordered.map { sha256Hex(it) }
        val digest = if (digests.size == 1) digests[0] else sha256Hex(digests.joinToString("").toByteArray())
        return "sha256:$digest"
    }

    private fun sha256Hex(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
