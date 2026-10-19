package com.samusynth.questcamera.io

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code from an RGBA frame (ZXing core, Apache-2.0). Pure JVM, unit-tested on the
 * host. Used by the pod setup panel's Scan QR, from the passthrough camera's frames.
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The QR text in [rgba] (width × height, 4 bytes per pixel, rows bottom-up as glReadPixels
     * returns them when [bottomUp]), or null when none decodes.
     */
    fun decode(rgba: ByteArray, width: Int, height: Int, bottomUp: Boolean = true): String? {
        require(rgba.size >= width * height * 4) { "frame is ${rgba.size} bytes, need ${width * height * 4}" }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sourceRow = if (bottomUp) height - 1 - y else y
            var i = sourceRow * width * 4
            val o = y * width
            for (x in 0 until width) {
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                pixels[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return try {
            QRCodeReader().decode(bitmap, hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        }
    }
}
