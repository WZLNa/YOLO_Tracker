package com.example.yolotracker.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer

data class LoadResult(
    val success: Boolean,
    val labelsFound: Boolean = true,
    val labelFileSearched: String? = null,
    val error: String? = null
)

/**
 * YOLO ONNX 检测器。
 *
 * 输入:  (1, 3, 640, 640) float32 NCHW RGB [0, 1]
 * 输出:  (1, 300, 6) float — [x1, y1, x2, y2, score, class_id]（像素坐标）
 */
class YoloDetector(private val context: Context) {

    companion object {
        /** COCO 80 类标签 — Ultralytics YOLO 预训练模型的默认类别。无 labels.txt 时自动使用。 */
        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    // ─── ONNX 后端 ───
    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null

    private var labels: List<String> = emptyList()
    private var inputName: String = "images"
    private var inputSize = 640

    /** 当前后端类型 */
    @Volatile
    private var backend = Backend.NONE

    var currentModelPath: String? = null; private set
    var currentModelName: String = "内置模型"; private set

    private val labelsLock = Any()

    /**
     * 推理/模型切换互斥锁。
     *
     * detect() 和 close()/load()/loadFromFile() 可能在不同线程并发执行：
     * - detect() 在摄像头分析线程（单线程 Executor）
     * - close/load 在 Dispatchers.IO
     *
     * 防止 session.run() 进行中时另一个线程调用 session.close() 导致 native crash。
     */
    private val inferenceLock = Any()

    // 预分配缓冲区（ONNX 用）
    private var pixelBuffer: IntArray? = null
    private var floatBuffer: FloatArray? = null
    private var scaledBitmap: Bitmap? = null

    // letterbox 参数（ONNX 用）
    private var lbScale = 1f
    private var lbPadX = 0f
    private var lbPadY = 0f

    var iouThreshold: Float = 0.45f

    @Volatile
    var confThreshold: Float = 0.35f

    /** 最近一次推理/加载错误（供 UI 展示和复制） */
    @Volatile
    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = backend != Backend.NONE
    fun getLabels(): List<String> = labels

    private enum class Backend { NONE, ONNX }

    // ─── 加载 ─────────────────────────────────────────────

    /** 加载内置模型（ONNX） */
    fun load(): Boolean {
        synchronized(inferenceLock) {
            try {
                try { session?.close() } catch (_: Exception) {}
                session = null; backend = Backend.NONE
                scaledBitmap?.recycle(); scaledBitmap = null; pixelBuffer = null; floatBuffer = null

                val modelBytes = context.assets.open("yolo_model.onnx").use { it.readBytes() }
                initOnnxSession(modelBytes)
                backend = Backend.ONNX
                labels = loadLabelsFromAssets()
                currentModelPath = null
                currentModelName = "内置模型 (ONNX)"
                lastError = null
                android.util.Log.i("YoloDetector", "ONNX 模型加载成功")
                return true
            } catch (e: Exception) {
                lastError = "内置模型加载失败: ${e.message}"
                android.util.Log.e("YoloDetector", "所有模型加载失败", e)
                return false
            }
        }
    }

    /** 从文件加载模型（ONNX） */
    fun loadFromFile(modelFile: File, labelFile: File? = null): LoadResult {
        return try {
            close()

            val modelBytes = modelFile.readBytes()
            initOnnxSession(modelBytes)
            backend = Backend.ONNX

            var labelsFound = false
            var labelFileSearched: String? = null

            labels = when {
                labelFile != null && labelFile.exists() -> { labelsFound = true; loadLabelsFromFile(labelFile) }
                else -> {
                    val auto = File(modelFile.parent, modelFile.nameWithoutExtension + ".txt")
                    labelFileSearched = auto.absolutePath
                    if (auto.exists()) { labelsFound = true; loadLabelsFromFile(auto) }
                    else {
                        val parent = File(modelFile.parent, "labels.txt")
                        labelFileSearched = parent.absolutePath
                        if (parent.exists()) { labelsFound = true; loadLabelsFromFile(parent) }
                        else {
                            labelsFound = true
                            COCO_LABELS
                        }
                    }
                }
            }

            currentModelPath = modelFile.absolutePath
            currentModelName = modelFile.name
            lastError = null
            LoadResult(success = true, labelsFound = labelsFound, labelFileSearched = labelFileSearched)
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            lastError = "加载 ${modelFile.name} 失败: $msg"
            android.util.Log.e("YoloDetector", "模型加载失败", e)
            LoadResult(success = false, error = msg)
        }
    }

    // ─── 检测 ─────────────────────────────────────────────

    fun detect(bitmap: Bitmap): List<Detection> {
        synchronized(inferenceLock) {
            if (backend != Backend.ONNX) return emptyList()
            return detectOnnx(bitmap)
        }
    }

    /** ONNX 推理 — 直接 resize + 归一化 */
    private fun detectOnnx(bitmap: Bitmap): List<Detection> {
        val session = session ?: return emptyList()
        val env = env ?: return emptyList()
        try {
            val srcW = bitmap.width; val srcH = bitmap.height
            val n = inputSize * inputSize

            // Letterbox 预处理（保持比例 + 灰边填充）
            val scale = minOf(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
            val newW = (srcW * scale).toInt(); val newH = (srcH * scale).toInt()
            val padX = (inputSize - newW) / 2; val padY = (inputSize - newH) / 2
            lbScale = scale; lbPadX = padX.toFloat(); lbPadY = padY.toFloat()

            val sb = scaledBitmap
            scaledBitmap = if (sb == null || sb.width != inputSize || sb.height != inputSize) {
                sb?.recycle(); Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            } else sb
            val canvas = android.graphics.Canvas(scaledBitmap!!)
            canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
            canvas.drawBitmap(bitmap, android.graphics.Rect(0, 0, srcW, srcH),
                android.graphics.Rect(padX, padY, padX + newW, padY + newH), null)

            if (pixelBuffer == null || pixelBuffer!!.size != n) pixelBuffer = IntArray(n)
            scaledBitmap!!.getPixels(pixelBuffer!!, 0, inputSize, 0, 0, inputSize, inputSize)

            val totalFloats = 3 * n
            if (floatBuffer == null || floatBuffer!!.size != totalFloats) floatBuffer = FloatArray(totalFloats)
            val inputData = floatBuffer!!; val ch = n
            val px = pixelBuffer!!
            for (i in 0 until n) { val p = px[i]; inputData[i] = ((p shr 16) and 0xFF) / 255f; inputData[i + ch] = ((p shr 8) and 0xFF) / 255f; inputData[i + 2 * ch] = (p and 0xFF) / 255f }

            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
            val outputs = session.run(mapOf(inputName to tensor))
            try {
                val raw = flattenOutput(outputs.iterator().next().value.value ?: return emptyList())
                if (raw.isEmpty()) return emptyList()
                return parseOutput(raw, srcW, srcH)
            } finally { tensor.close(); closeOutputs(outputs) }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            lastError = "推理异常 [$currentModelName]: $msg"
            android.util.Log.e("YoloDetector", "ONNX 推理异常", e)
            return emptyList()
        }
    }

    // ─── 解析输出 ─────────────────────────────────────────

    private fun parseOutput(raw: FloatArray, srcW: Int, srcH: Int): List<Detection> {
        val fs = 6; if (raw.size % fs != 0) return emptyList()
        val num = raw.size / fs

        // 自动检测坐标空间：检查所有候选的最大坐标
        var maxCoord = 0f
        for (i in 0 until num) {
            val off = i * fs
            for (j in 0..3) { val v = raw[off + j]; if (v > maxCoord) maxCoord = v }
        }
        val isPixelSpace = maxCoord > 2f

        val candidates = mutableListOf<Detection>()
        for (i in 0 until num) {
            val off = i * fs
            val x1 = raw[off]; val y1 = raw[off + 1]; val x2 = raw[off + 2]; val y2 = raw[off + 3]
            val score = raw[off + 4]; val clsId = raw[off + 5].toInt()
            if (score < confThreshold || clsId < 0) continue
            if (x2 <= x1 || y2 <= y1) continue

            // 归一化到原始图像 [0, 1]
            val nx1: Float; val ny1: Float; val nx2: Float; val ny2: Float
            if (isPixelSpace) {
                // 像素坐标 → 反 letterbox → 归一化
                val invW = 1f / (lbScale * srcW); val invH = 1f / (lbScale * srcH)
                nx1 = ((x1 - lbPadX) * invW).coerceIn(0f, 1f)
                ny1 = ((y1 - lbPadY) * invH).coerceIn(0f, 1f)
                nx2 = ((x2 - lbPadX) * invW).coerceIn(0f, 1f)
                ny2 = ((y2 - lbPadY) * invH).coerceIn(0f, 1f)
            } else {
                // 归一化坐标 → 反 letterbox → 归一化到原图
                // 模型输出 [0,1] 相对于 letterboxed 640x640
                // 需要转换为相对于原始图像的 [0,1]
                val invW = 1f / (lbScale * srcW); val invH = 1f / (lbScale * srcH)
                nx1 = ((x1 * inputSize - lbPadX) * invW).coerceIn(0f, 1f)
                ny1 = ((y1 * inputSize - lbPadY) * invH).coerceIn(0f, 1f)
                nx2 = ((x2 * inputSize - lbPadX) * invW).coerceIn(0f, 1f)
                ny2 = ((y2 * inputSize - lbPadY) * invH).coerceIn(0f, 1f)
            }
            if (nx2 - nx1 < 0.001f || ny2 - ny1 < 0.001f) continue

            candidates.add(Detection(RectF(nx1, ny1, nx2, ny2), score, clsId,
                synchronized(labelsLock) { getOrExpandLabel(clsId) }))
        }

        candidates.sortByDescending { it.score }
        var result = nonMaxSuppression(candidates, iouThreshold)
        if (result.size > 5) result = result.take(5)

        if (result.isNotEmpty()) {
            val s = result[0]
            android.util.Log.d("YoloDetector", "像素空间=$isPixelSpace, maxCoord=${"%.1f".format(maxCoord)}, " +
                "检测=${result.size}, 最佳=[${"%.3f".format(s.bbox.left)},${"%.3f".format(s.bbox.top)}," +
                "${"%.3f".format(s.bbox.right)},${"%.3f".format(s.bbox.bottom)}] conf=${"%.2f".format(s.score)}")
        }

        return result
    }

    // ─── 内部方法 ─────────────────────────────────────────

    private fun initOnnxSession(modelBytes: ByteArray) {
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        // 不启用 NNAPI — CPU 推理在各设备上最稳定。
        // NNAPI 在 session 创建时可能不报错，但在实际推理时 native crash 无法被 Kotlin 捕获。
        session = env!!.createSession(modelBytes, opts)
        inputName = session!!.inputNames.firstOrNull() ?: "images"

        try {
            val shape = (session!!.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            if (shape != null && shape.size >= 4) inputSize = shape[2].toInt()
            android.util.Log.i("YoloDetector", "模型输入: ${if (shape != null) shape.joinToString("x") else "?"}")
        } catch (_: Exception) { inputSize = 640 }

        try {
            val outputName = session!!.outputNames.firstOrNull() ?: "?"
            val outShape = (session!!.outputInfo[outputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            val outLog = if (outShape != null) outShape.joinToString("x") else "?"
            android.util.Log.i("YoloDetector", "模型输出: $outLog")
        } catch (_: Exception) { }
    }

    private fun loadLabelsFromAssets(): List<String> = try {
        context.assets.open("labels.txt").use { BufferedReader(InputStreamReader(it)).readLines().filter { l -> l.isNotBlank() } }
    } catch (_: Exception) { COCO_LABELS }

    private fun loadLabelsFromFile(file: File): List<String> = try { file.readLines().filter { it.isNotBlank() } } catch (_: Exception) { listOf("object") }

    private fun getOrExpandLabel(clsId: Int): String {
        return if (clsId < labels.size) labels[clsId]
        else { if (clsId >= labels.size) { labels = labels.toMutableList().apply { while (size <= clsId) add("cls_$size") } }; labels[clsId] }
    }

    private fun flattenOutput(output: Any): FloatArray {
        return try {
            when (output) {
                is ai.onnxruntime.OnnxTensor -> { val v = output.value; if (v === output) FloatArray(0) else flattenOutput(v) }
                is Array<*> -> {
                    val first = output[0]
                    when (first) {
                        is Array<*> -> { val rows = first.size; val cols = if (rows > 0 && first[0] is FloatArray) (first[0] as FloatArray).size else 0; if (rows == 0 || cols == 0) FloatArray(0) else { val f = FloatArray(rows * cols); for (i in 0 until rows) { val r = first[i]; if (r is FloatArray) System.arraycopy(r, 0, f, i * cols, cols) }; f } }
                        is FloatArray -> { val rows = output.size; val cols = first.size; if (rows == 0 || cols == 0) FloatArray(0) else { val f = FloatArray(rows * cols); for (i in 0 until rows) { val r = output[i]; if (r is FloatArray) System.arraycopy(r, 0, f, i * cols, cols) }; f } }
                        else -> FloatArray(0)
                    }
                }
                is FloatArray -> output
                else -> FloatArray(0)
            }
        } catch (_: Exception) { FloatArray(0) }
    }

    private fun nonMaxSuppression(dets: List<Detection>, thresh: Float): List<Detection> {
        if (dets.isEmpty()) return dets
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) { val best = sorted.removeAt(0); keep.add(best); sorted.removeAll { IOUTracker.computeIOU(best.bbox, it.bbox) >= thresh } }
        return keep
    }

    private fun closeOutputs(outputs: Iterable<*>) {
        for (v in outputs) { try { when (v) { is Map.Entry<*, *> -> (v.value as? AutoCloseable)?.close(); is AutoCloseable -> v.close() } } catch (_: Exception) {} }
    }

    fun close() {
        synchronized(inferenceLock) {
            try { session?.close() } catch (_: Exception) {}
            session = null; backend = Backend.NONE
            scaledBitmap?.recycle(); scaledBitmap = null; pixelBuffer = null; floatBuffer = null
        }
    }
}
