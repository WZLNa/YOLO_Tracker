package com.example.yolotracker.model

import android.graphics.RectF
import kotlin.math.max

/**
 * 基于 IOU 的轻量跟踪器。
 *
 * 对应 Python track_webcam.py 中 ByteTrack tracker + idlist 去重逻辑。
 *
 * 工作流程：
 * 1. 对当前帧的每个 Detection，计算与所有活跃 Track 的 IOU
 * 2. 用贪心匹配（最高 IOU 优先）将 Detection 分配给已有 Track
 * 3. 未匹配到的 Detection → 创建新 Track
 * 4. 超过 [maxLostFrames] 未更新的 Track → 移除
 * 5. 每个 Track 保留最近 [trailLength] 个中心点用于画轨迹
 *
 * 注意：所有可变状态通过 @Volatile + synchronized 保证线程安全，
 *       因为 update() 在 CameraX 分析线程中调用，而 UI 线程读取统计数据。
 */
class IOUTracker(
    private val iouThreshold: Float = 0.3f,
    private val maxLostFrames: Int = 30,       // 增大：适应低 FPS 和遮挡场景
    private val trailLength: Int = 30          // 增长轨迹线
) {
    private data class Track(
        val id: Int,
        var bbox: RectF,
        var label: String,
        var score: Float,
        var classId: Int,
        var lostFrames: Int = 0,
        val trail: ArrayDeque<Pair<Float, Float>> = ArrayDeque()
    )

    @Volatile
    private var tracks: List<Track> = emptyList()

    @Volatile
    private var nextId = 0

    /** 已出现过的唯一 ID 集合（用于去重计数，对应 Python 版 idlist） */
    private val seenIds = mutableSetOf<Int>()

    /** 线程锁 */
    private val lock = Any()

    /**
     * 更新跟踪器，输入当前帧检测，返回带 trackId 的结果。
     *
     * @param detections 归一化坐标 [0, 1] 的检测列表
     * @return 带跟踪ID的检测列表（含轨迹）
     */
    fun update(detections: List<Detection>): List<TrackedDetection> {
        synchronized(lock) {
            val currentTracks = tracks.toMutableList()

            if (detections.isEmpty()) {
                // 没有检测：所有 track 失联帧 +1
                currentTracks.forEach { it.lostFrames++ }
                pruneLostTracks(currentTracks)
                tracks = currentTracks
                return emptyList()
            }

            val matchedTrackIndices = mutableSetOf<Int>()
            val matchedDetIndices = mutableSetOf<Int>()

            // 计算所有 (track, detection) 的 IOU，按 IOU 降序贪心匹配
            val pairs = mutableListOf<Triple<Int, Int, Float>>()
            for ((ti, track) in currentTracks.withIndex()) {
                for ((di, det) in detections.withIndex()) {
                    val iou = computeIOU(track.bbox, det.bbox)
                    if (iou >= iouThreshold) {
                        pairs.add(Triple(ti, di, iou))
                    }
                }
            }
            pairs.sortByDescending { it.third }

            for ((ti, di, _) in pairs) {
                if (ti !in matchedTrackIndices && di !in matchedDetIndices) {
                    // 匹配成功：更新 bbox、置信度、类别
                    val track = currentTracks[ti]
                    track.bbox = detections[di].bbox
                    track.label = detections[di].label
                    track.score = detections[di].score
                    track.classId = detections[di].classId
                    track.lostFrames = 0
                    // 记录中心点
                    val cx = track.bbox.centerX()
                    val cy = track.bbox.centerY()
                    track.trail.addLast(cx to cy)
                    if (track.trail.size > trailLength) {
                        track.trail.removeFirst()
                    }
                    matchedTrackIndices.add(ti)
                    matchedDetIndices.add(di)
                }
            }

            // 未匹配的 track：失联帧 +1
            for ((ti, track) in currentTracks.withIndex()) {
                if (ti !in matchedTrackIndices) {
                    track.lostFrames++
                }
            }

            // 未匹配的 detection：创建新 track
            for ((di, det) in detections.withIndex()) {
                if (di !in matchedDetIndices) {
                    val trail = ArrayDeque<Pair<Float, Float>>()
                    val cx = det.bbox.centerX()
                    val cy = det.bbox.centerY()
                    trail.addLast(cx to cy)

                    val newId = nextId++
                    currentTracks.add(
                        Track(
                            id = newId,
                            bbox = det.bbox,
                            label = det.label,
                            score = det.score,
                            classId = det.classId,
                            trail = trail
                        )
                    )
                    seenIds.add(newId)
                }
            }

            pruneLostTracks(currentTracks)
            tracks = currentTracks

            // 只返回本帧匹配上检测的 track（lostFrames == 0）。
            // 失联 track 仍保留在 tracks 中用于后续重关联，但不返回绘制，
            // 避免失联框停在原地残留最多 maxLostFrames 帧、造成"框越用越多"。
            return currentTracks
                .filter { it.lostFrames == 0 }
                .map { track ->
                    TrackedDetection(
                        trackId = track.id,
                        detection = Detection(
                            bbox = RectF(track.bbox),
                            score = track.score,
                            classId = track.classId,
                            label = track.label
                        ),
                        trail = track.trail.toList()
                    )
                }
        }
    }

    /** 移除长期失联的 track */
    private fun pruneLostTracks(trackList: MutableList<Track>) {
        trackList.removeAll { it.lostFrames > maxLostFrames }
    }

    /** 获取去重后的总追踪数量（线程安全） */
    fun totalTrackedCount(): Int = synchronized(lock) { seenIds.size }

    /** 当前活跃 track 数量（线程安全） */
    fun activeTrackCount(): Int = synchronized(lock) { tracks.size }

    /** 重置跟踪器 */
    fun reset() {
        synchronized(lock) {
            tracks = emptyList()
            seenIds.clear()
            nextId = 0
        }
    }

    companion object {
        /** 计算两个归一化 bbox 的 IOU */
        fun computeIOU(a: RectF, b: RectF): Float {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = kotlin.math.min(a.right, b.right)
            val bottom = kotlin.math.min(a.bottom, b.bottom)

            val interW = max(0f, right - left)
            val interH = max(0f, bottom - top)
            val interArea = interW * interH

            if (interArea <= 0f) return 0f

            val areaA = a.width() * a.height()
            val areaB = b.width() * b.height()
            val unionArea = areaA + areaB - interArea

            return if (unionArea <= 0f) 0f else interArea / unionArea
        }
    }
}
