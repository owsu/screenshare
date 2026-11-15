package org.shadowtrace

import android.content.Context
import android.graphics.*
import android.view.View

/** Original vector teaching illustrations. Positions are examples, never observed locations. */
class GuideGraphic(context: Context, initialStep: Int, private val dark: Boolean) : View(context) {
    var step = initialStep
        set(value) { field = value; invalidate() }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val navy = ModernizeTheme.ink
    private val blue = ModernizeTheme.action
    private val pale = ModernizeTheme.primaryLight
    private val gold = Color.rgb(242,191,91)
    private fun fill(canvas: Canvas, color: Int, x: Float,y: Float,w: Float,h: Float,r: Float = 12f) {
        pen.color=color; pen.style=Paint.Style.FILL
        canvas.drawRoundRect(x,y,x+w,y+h,r,r,pen)
    }
    private fun stroke(canvas: Canvas, color: Int, x: Float,y: Float,x2: Float,y2: Float) {
        pen.color=color; pen.strokeWidth=3f; pen.strokeCap=Paint.Cap.ROUND
        canvas.drawLine(x,y,x2,y2,pen)
    }
    private fun dot(canvas: Canvas,color: Int,x: Float,y: Float,r: Float) {
        pen.color=color; pen.style=Paint.Style.FILL; canvas.drawCircle(x,y,r,pen)
    }
    private fun wifi(canvas: Canvas,x: Float,y: Float,color: Int) {
        pen.style=Paint.Style.STROKE; pen.color=color; pen.strokeWidth=4f; pen.strokeCap=Paint.Cap.ROUND
        canvas.drawArc(x-20,y-10,x+20,y+30,220f,100f,false,pen)
        canvas.drawArc(x-11,y,x+11,y+22,220f,100f,false,pen)
        dot(canvas,color,x,y+18,3f)
    }
    private fun phone(canvas: Canvas,x: Float,y: Float) {
        fill(canvas,navy,x,y,44f,74f,10f); fill(canvas,pale,x+5,y+7,34f,53f,5f)
        dot(canvas,Color.WHITE,x+22,y+67,2f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width/320f,height/170f)
        canvas.save(); canvas.translate((width-320*scale)/2,(height-170*scale)/2); canvas.scale(scale,scale)
        if (!dark) fill(canvas,ModernizeTheme.primaryLight,8f,10f,304f,150f,18f)
        when(step) {
            0 -> {
                val shield = Path().apply { moveTo(160f,27f); lineTo(204f,44f); lineTo(199f,92f)
                    quadTo(192f,119f,160f,139f); quadTo(128f,119f,121f,92f); lineTo(116f,44f); close() }
                pen.color=if(dark) pale else blue; pen.style=Paint.Style.FILL; canvas.drawPath(shield,pen)
                dot(canvas,if(dark) navy else Color.WHITE,146f,75f,4f)
                dot(canvas,if(dark) navy else Color.WHITE,174f,75f,4f)
                pen.style=Paint.Style.STROKE; pen.color=if(dark) navy else Color.WHITE; pen.strokeWidth=3f
                canvas.drawArc(145f,77f,175f,101f,20f,140f,false,pen)
                wifi(canvas,72f,61f,if(dark) pale else blue)
                wifi(canvas,248f,61f,if(dark) pale else blue)
                dot(canvas,gold,245f,123f,6f); dot(canvas,gold,76f,123f,4f)
            }
            1 -> {
                wifi(canvas,91f,51f,blue); wifi(canvas,229f,51f,blue)
                fill(canvas,Color.WHITE,46f,92f,90f,35f); fill(canvas,Color.WHITE,184f,92f,90f,35f)
                for(i in 0..3) { dot(canvas,blue,62f+i*18,109f,3f); dot(canvas,if(i%2==0) gold else blue,200f+i*18,109f,3f) }
                stroke(canvas,navy,151f,82f,170f,102f); stroke(canvas,navy,170f,82f,151f,102f)
            }
            2 -> {
                fill(canvas,Color.WHITE,53f,49f,214f,95f)
                for(i in 0..5) fill(canvas,if(i%2==0) blue else gold,72f+i*29,66f,18f,38f,3f)
                stroke(canvas,navy,67f,108f,253f,108f)
                phone(canvas,138f,61f); wifi(canvas,240f,21f,blue)
            }
            3 -> {
                pen.color=blue; pen.strokeWidth=3f; pen.style=Paint.Style.STROKE
                pen.pathEffect=DashPathEffect(floatArrayOf(5f,7f),0f)
                canvas.drawPath(Path().apply { moveTo(67f,117f); cubicTo(115f,138f,184f,50f,257f,70f) },pen)
                pen.pathEffect=null
                dot(canvas,blue,67f,117f,7f); dot(canvas,gold,257f,70f,7f)
                phone(canvas,43f,29f); phone(canvas,233f,80f)
                wifi(canvas,150f,27f,blue)
            }
            4 -> {
                fill(canvas,blue,58f,47f,204f,83f,16f)
                for(i in 0..3) fill(canvas,pale,73f+i*47,58f,34f,32f,5f)
                dot(canvas,navy,97f,132f,12f); dot(canvas,navy,223f,132f,12f)
                wifi(canvas,160f,12f,blue)
                for(i in 0..2) dot(canvas,gold,119f+i*41,107f,4f)
            }
            else -> {
                stroke(canvas,navy,58f,38f,58f,133f); stroke(canvas,navy,58f,133f,264f,133f)
                pen.style=Paint.Style.STROKE; pen.color=blue; pen.strokeWidth=5f
                canvas.drawPath(Path().apply { moveTo(70f,49f); cubicTo(127f,51f,155f,113f,249f,117f) },pen)
                dot(canvas,gold,70f,49f,6f); dot(canvas,blue,249f,117f,6f)
            }
        }
        canvas.restore()
    }
}
