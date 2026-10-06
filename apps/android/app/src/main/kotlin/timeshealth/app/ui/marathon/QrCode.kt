package timeshealth.app.ui.marathon

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR modules for [value] (ZXing), or null if it can't be encoded. Medium error correction, 1-module quiet zone. */
internal fun qrMatrix(value: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        value, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
    )
}.getOrNull()

/** A QR code drawn directly in Compose: crisp at any size, no bitmap. */
@Composable
fun QrCode(value: String, modifier: Modifier = Modifier, color: Color = Color(0xFF121417)) {
    val matrix = remember(value) { qrMatrix(value) }
    Canvas(modifier.semantics { contentDescription = "Race pass QR code" }) {
        val m = matrix ?: return@Canvas
        drawRect(Color.White)
        val cell = minOf(size.width / m.width, size.height / m.height)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
