package com.samusynth.questcamera.io

/**
 * One line naming a capture failure and every cause under it. Unity's AndroidJavaException
 * carries only the outermost Java message, so a wrapper whose message omits its cause hides
 * the real failure: pod5 2026-11-16's metadata said only "Failed to stop fixed-rate recording"
 * for an eye that had ended 35 s earlier on "Encoded sample has no source exposure timestamp".
 */
internal object CaptureFailure {
    private const val MAX_CAUSES = 8

    fun describe(failure: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = failure
        while (current != null && parts.size < MAX_CAUSES) {
            parts.add("${current.javaClass.name}: ${current.message ?: "(no message)"}")
            current = current.cause?.takeIf { it !== current }
        }
        return parts.joinToString("; caused by ")
    }
}
