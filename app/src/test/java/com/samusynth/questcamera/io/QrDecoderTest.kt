package com.samusynth.questcamera.io

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    /** A QR code drawn the way glReadPixels returns the frame: RGBA, rows bottom-up, on grey. */
    private fun frame(text: String, size: Int, codeSize: Int, bottomUp: Boolean): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, codeSize, codeSize)
        val rgba = ByteArray(size * size * 4) { 0x80.toByte() }
        val offset = (size - codeSize) / 2
        for (y in 0 until codeSize) for (x in 0 until codeSize) {
            val row = if (bottomUp) size - 1 - (y + offset) else y + offset
            val i = (row * size + x + offset) * 4
            val v = if (matrix[x, y]) 0 else 0xFF
            rgba[i] = v.toByte(); rgba[i + 1] = v.toByte(); rgba[i + 2] = v.toByte(); rgba[i + 3] = 0xFF.toByte()
        }
        return rgba
    }

    private val payload = """{"site":"https://app.preview.sentientx.io","join_code":"sxj_0123456789abcdefghijklmn"}"""

    @Test
    fun decodesAJoinCodeFromABottomUpFrame() {
        assertEquals(payload, QrDecoder.decode(frame(payload, 768, 400, bottomUp = true), 768, 768))
    }

    @Test
    fun decodesATopDownFrameWhenToldSo() {
        assertEquals(payload, QrDecoder.decode(frame(payload, 512, 300, bottomUp = false), 512, 512, bottomUp = false))
    }

    @Test
    fun aFrameWithoutACodeDecodesNothing() {
        assertNull(QrDecoder.decode(ByteArray(256 * 256 * 4) { 0x40 }, 256, 256))
    }
}
