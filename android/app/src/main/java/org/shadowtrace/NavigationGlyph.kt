package org.shadowtrace

import android.graphics.*
import android.graphics.drawable.Drawable

/** Native vector navigation icons, with visible text labels supplied by their buttons. */
class NavigationGlyph(private val index: Int, color: Int, size: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    init { setBounds(0,0,size,size) }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        when(index) {
            0 -> canvas.drawPath(Path().apply { moveTo(3f,11f); lineTo(12f,3f); lineTo(21f,11f)
                moveTo(5f,10f); lineTo(5f,21f); lineTo(10f,21f); lineTo(10f,15f); lineTo(14f,15f)
                lineTo(14f,21f); lineTo(19f,21f); lineTo(19f,10f) },pen)
            1 -> {
                canvas.drawArc(2f,4f,22f,24f,225f,90f,false,pen)
                canvas.drawArc(6f,10f,18f,22f,225f,90f,false,pen)
                canvas.drawCircle(12f,19f,1f,pen)
            }
            2 -> {
                canvas.drawPath(Path().apply { moveTo(12f,6f); quadTo(7f,2f,3f,5f); lineTo(3f,20f)
                    quadTo(7f,17f,12f,21f); quadTo(17f,17f,21f,20f); lineTo(21f,5f)
                    quadTo(17f,2f,12f,6f); lineTo(12f,21f) },pen)
            }
            else -> {
                for (x in listOf(5f,12f,19f)) canvas.drawLine(x,3f,x,21f,pen)
                pen.style=Paint.Style.FILL
                canvas.drawCircle(5f,8f,3f,pen); canvas.drawCircle(12f,16f,3f,pen); canvas.drawCircle(19f,9f,3f,pen)
                pen.style=Paint.Style.STROKE
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { pen.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
