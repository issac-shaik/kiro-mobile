package dev.kiromobile.design

import android.graphics.*
import android.graphics.drawable.Drawable

/** Shared Kiro Dark tokens. Keep visual branding independent of the connection and chat. */
object KiroTheme {
    val background = Color.rgb(33, 29, 37)
    val chrome = Color.rgb(25, 22, 29)
    val surface = Color.rgb(40, 36, 46)
    val hover = Color.rgb(53, 47, 61)
    val border = Color.rgb(74, 70, 79)
    val accent = Color.rgb(176, 128, 255)
    val primary = Color.rgb(113, 56, 204)
    val foreground = Color.rgb(242, 241, 244)
    val secondary = Color.rgb(193, 190, 198)
    val muted = Color.rgb(147, 143, 155)
    val success = Color.rgb(128, 255, 181)
    val warning = Color.rgb(255, 207, 153)
}

enum class Glyph { HISTORY, PLUS, MORE, ATTACH, SEND, STOP, FOLDER }

/** Resolution-independent toolbar glyphs, with consistent stroke and no font-glyph dependencies. */
class ToolbarIcon(private val glyph: Glyph, private val color: Int = KiroTheme.secondary) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=color
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=canvas.drawLine(x1,y1,x2,y2,paint)
        when(glyph) {
            Glyph.PLUS -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
            Glyph.HISTORY -> { canvas.drawArc(4f,4f,20f,20f,210f,300f,false,paint);line(3f,4f,3f,9f);line(3f,9f,8f,9f);line(12f,8f,12f,12f);line(12f,12f,15f,14f) }
            Glyph.MORE -> { paint.style=Paint.Style.FILL;for(y in listOf(5f,12f,19f))canvas.drawCircle(12f,y,1.5f,paint);paint.style=Paint.Style.STROKE }
            Glyph.SEND -> { line(12f,19f,12f,5f);line(6f,11f,12f,5f);line(12f,5f,18f,11f) }
            Glyph.STOP -> canvas.drawRoundRect(6f,6f,18f,18f,2f,2f,paint)
            Glyph.FOLDER -> { val path=Path();path.moveTo(3f,7f);path.lineTo(3f,19f);path.lineTo(21f,19f);path.lineTo(21f,7f);path.lineTo(12f,7f);path.lineTo(10f,4f);path.lineTo(3f,4f);path.close();canvas.drawPath(path,paint) }
            Glyph.ATTACH -> { val path=Path();path.moveTo(9f,16f);path.lineTo(16f,9f);path.cubicTo(19f,6f,15f,2f,12f,5f);path.lineTo(5f,12f);path.cubicTo(0f,17f,7f,24f,12f,19f);path.lineTo(20f,11f);canvas.drawPath(path,paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
