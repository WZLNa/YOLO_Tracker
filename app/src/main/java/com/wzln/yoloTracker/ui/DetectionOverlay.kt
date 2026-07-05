package com.wzln.yoloTracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wzln.yoloTracker.model.TrackedDetection
import com.wzln.yoloTracker.ui.theme.DetectionBoxInt
import com.wzln.yoloTracker.ui.theme.DetectionTrackLineInt

/**
 * 在摄像头预览上方绘制的检测框叠加层。
 *
 * 绘制内容：
 * - 检测框（青色描边，半透明填充）
 * - 跟踪 ID 标签
 * - 轨迹线（紫色）
 */
class DetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<TrackedDetection> = emptyList()
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0

    // 油漆笔
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = DetectionBoxInt // cyan-400
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(25, 34, 211, 238)
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DetectionBoxInt
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val labelTextBGPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 6f
        color = Color.argb(180, 0, 0, 0)
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = DetectionTrackLineInt // violet-400
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 更新检测结果 */
    fun updateDetections(detections: List<TrackedDetection>, previewW: Int, previewH: Int) {
        this.detections = detections
        this.previewWidth = previewW
        this.previewHeight = previewH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (previewWidth == 0 || previewHeight == 0) return

        // 与 PreviewView 的 ScaleType.FIT_CENTER 保持一致：
        // 等比缩放使图像完整显示（取较小 scale），居中，上下或左右留边。
        // previewWidth/Height 已是旋转后的方向（见 CameraScreen.imageProxyToBitmap）。
        val scale = minOf(width.toFloat() / previewWidth, height.toFloat() / previewHeight)
        val dispW = previewWidth * scale
        val dispH = previewHeight * scale
        val offsetX = (width - dispW) / 2f
        val offsetY = (height - dispH) / 2f

        for (td in detections) {
            val det = td.detection
            val bbox = det.bbox

            // 归一化坐标 [0,1] → 画布坐标（FIT_CENTER 映射）
            val left = bbox.left * dispW + offsetX
            val top = bbox.top * dispH + offsetY
            val right = bbox.right * dispW + offsetX
            val bottom = bbox.bottom * dispH + offsetY
            val rect = RectF(left, top, right, bottom)

            // 轨迹线
            val trail = td.trail
            if (trail.size >= 2) {
                val path = Path()
                val (firstX, firstY) = trail.first()
                path.moveTo(firstX * dispW + offsetX, firstY * dispH + offsetY)
                for (i in 1 until trail.size) {
                    val (px, py) = trail[i]
                    path.lineTo(px * dispW + offsetX, py * dispH + offsetY)
                }
                canvas.drawPath(path, trailPaint)
            }

            // 半透明填充
            canvas.drawRect(rect, boxFillPaint)

            // 检测框描边
            canvas.drawRect(rect, boxPaint)

            // 标签
            val label = "ID:${td.trackId} ${det.label}"
            val labelWidth = labelTextPaint.measureText(label)
            val labelHeight = labelTextPaint.textSize
            val labelRect = RectF(
                left,
                top - labelHeight - 6f,
                left + labelWidth + 12f,
                top
            )

            // 标签背景
            val bgExtra = 4f
            canvas.drawRect(
                labelRect.left - bgExtra,
                labelRect.top,
                labelRect.right + bgExtra,
                labelRect.bottom + bgExtra,
                labelBgPaint
            )

            // 标签文字（先画描边再画填充，保证可读性）
            canvas.drawText(label, left + 6f, top - 6f, labelTextBGPaint)
            canvas.drawText(label, left + 6f, top - 6f, labelTextPaint)
        }
    }
}
