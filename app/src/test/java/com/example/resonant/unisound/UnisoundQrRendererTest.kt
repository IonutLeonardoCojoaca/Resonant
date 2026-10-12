package com.example.resonant.unisound

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class UnisoundQrRendererTest {
    @Test
    fun `qr encodes exactly the join url`() {
        val joinUrl = "https://resonantapp.ddns.net/u/K7M2QX"
        val matrix = UnisoundQrRenderer.encode(joinUrl, 300)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))

        assertEquals(joinUrl, QRCodeReader().decode(bitmap).text)
    }
}
