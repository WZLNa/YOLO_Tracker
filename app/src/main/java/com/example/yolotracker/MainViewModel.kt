package com.example.yolotracker

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.example.yolotracker.model.IOUTracker
import com.example.yolotracker.model.LoadResult
import com.example.yolotracker.model.TrackedDetection
import com.example.yolotracker.model.YoloDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI 状态快照 — 每帧更新一次。
 */
data class UiState(
    val trackedCount: Int = 0,
    val activeCount: Int = 0,
    val fps: Float = 0f,
    val detections: List<TrackedDetection> = emptyList(),
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val isModelLoaded: Boolean = false,
    val modelName: String = "内置模型",
    val labels: List<String> = emptyList(),
    val error: String? = null,
    val errorTime: String = ""
)

/**
 * 主 ViewModel — 管理检测器、跟踪器、帧处理和 UI 状态。
 *
 * 职责：
 * - 模型加载/切换
 * - 帧推理 + 跟踪（带节流）
 * - FPS 计算
 * - 通过 StateFlow 发射 UI 状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = YoloDetector(application)
    private val tracker = IOUTracker()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _confThreshold = MutableStateFlow(0.35f)
    val confThreshold: StateFlow<Float> = _confThreshold.asStateFlow()

    // ─── 帧节流 ───
    private var lastInferenceTime = 0L
    private companion object {
        const val MIN_INFERENCE_INTERVAL_MS = 100L  // ~10 FPS 上限
    }

    // ─── FPS 计数 ───
    private var frameCount = 0
    private var lastFpsTime = SystemClock.elapsedRealtime()

    /**
     * 加载内置模型。
     */
    fun loadBuiltIn(): Boolean {
        val ok = detector.load()
        updateState {
            copy(
                isModelLoaded = ok,
                modelName = if (ok) "内置模型" else modelName,
                labels = if (ok) detector.getLabels() else labels,
                error = if (!ok) detector.lastError else null,
                errorTime = if (!ok) nowStr() else errorTime
            )
        }
        return ok
    }

    /**
     * 从文件加载模型。
     */
    fun loadFromFile(modelFile: File, labelFile: File? = null): LoadResult {
        val result = detector.loadFromFile(modelFile, labelFile)
        if (result.success) {
            tracker.reset()
            updateState {
                copy(
                    isModelLoaded = true,
                    modelName = modelFile.name,
                    labels = detector.getLabels(),
                    error = null
                )
            }
        } else {
            updateState { copy(isModelLoaded = false, error = detector.lastError, errorTime = nowStr()) }
        }
        return result
    }

    /**
     * 更新置信度阈值。
     */
    fun updateConfThreshold(value: Float) {
        _confThreshold.value = value
        detector.confThreshold = value
    }

    /**
     * 是否到了该跑推理的时刻（10fps 上限节流）。
     *
     * 仅在单一分析线程串行调用，无需额外同步。放在图像解码之前判断，
     * 避免每帧都白做昂贵的 YUV→JPEG→Bitmap→旋转后再被丢弃。
     */
    fun shouldRunInference(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferenceTime < MIN_INFERENCE_INTERVAL_MS) return false
        lastInferenceTime = now
        return true
    }

    /**
     * 处理一帧 — 在分析线程调用。
     *
     * @param bitmap 摄像头帧（会被缩放，调用方可 recycle 原始 bitmap）
     * @param frameWidth 帧宽度
     * @param frameHeight 帧高度
     */
    fun processFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        // 推理（节流已由 shouldRunInference 在解码前完成）
        val detections = detector.detect(bitmap)

        val inferenceError = detector.lastError

        // 跟踪
        val tracked = tracker.update(detections)

        // FPS
        frameCount++
        val fpsNow = SystemClock.elapsedRealtime()
        val elapsed = fpsNow - lastFpsTime
        val fps = if (elapsed >= 1000) {
            val f = frameCount * 1000f / elapsed
            frameCount = 0
            lastFpsTime = fpsNow
            f
        } else {
            _uiState.value.fps  // 保持上一次的 FPS
        }

        // 更新状态
        updateState {
            copy(
                trackedCount = tracker.totalTrackedCount(),
                activeCount = tracked.size,
                fps = fps,
                detections = tracked,
                previewWidth = frameWidth,
                previewHeight = frameHeight,
                error = inferenceError ?: error,
                errorTime = if (inferenceError != null) nowStr() else errorTime
            )
        }
    }

    /**
     * 关闭错误提示。
     */
    fun dismissError() {
        updateState { copy(error = null, errorTime = "") }
    }

    fun getDetector(): YoloDetector = detector
    fun getTracker(): IOUTracker = tracker

    private fun nowStr() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private inline fun updateState(transform: UiState.() -> UiState) {
        _uiState.value = _uiState.value.transform()
    }

    override fun onCleared() {
        super.onCleared()
        detector.close()
    }
}
