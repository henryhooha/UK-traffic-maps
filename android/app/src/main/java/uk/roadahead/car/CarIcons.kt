package uk.roadahead.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/** Car icons rendered from emoji/glyphs so we don't need a drawable per report type. */
object CarIcons {
    private val cache = HashMap<String, CarIcon>()
    fun glyph(text: String, color: Int = Color.WHITE, size: Int = 128): CarIcon = cache.getOrPut("$text:$color:$size") {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.78f; textAlign = Paint.Align.CENTER; this.color = color }
        val y = size / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(text, size / 2f, y, p)
        CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}
