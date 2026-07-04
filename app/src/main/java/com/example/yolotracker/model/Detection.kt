package com.example.yolotracker.model

import android.graphics.RectF

/**
 * 单次检测结果。
 *
 * @param bbox    归一化坐标 [left, top, right, bottom]，范围 0..1
 * @param score   置信度 0..1
 * @param classId 类别索引
 * @param label   类别名称
 */
data class Detection(
    val bbox: RectF,
    val score: Float,
    val classId: Int,
    val label: String
)

/**
 * 带跟踪 ID 的检测结果，用于绘制到画布。
 */
data class TrackedDetection(
    val trackId: Int,
    val detection: Detection,
    /** 跟踪轨迹历史点位（归一化中心点），用于画轨迹线 */
    val trail: List<Pair<Float, Float>> = emptyList()
)
