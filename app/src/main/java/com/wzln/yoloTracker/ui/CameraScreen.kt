package com.wzln.yoloTracker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wzln.yoloTracker.MainViewModel
import com.wzln.yoloTracker.model.TrackedDetection
import com.wzln.yoloTracker.ui.theme.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

// ─── 主入口 ──────────────────────────────────────────────────

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val confThreshold by viewModel.confThreshold.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { viewModel.loadBuiltIn() }
    }

    // 权限
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    // 对话框
    var showModelDialog by remember { mutableStateOf(false) }
    var labelSelectInfo by remember { mutableStateOf<LabelSelectInfo?>(null) }
    var unsupportedFormatInfo by remember { mutableStateOf<UnsupportedFormatInfo?>(null) }
    var pendingModelFile by remember { mutableStateOf<File?>(null) }

    // 摄像头状态
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var isFrontCamera by remember { mutableStateOf(false) }

    // 拍照回调（由 CameraPreview 设置）
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val scope = rememberCoroutineScope()

    fun loadModelWithLabel(modelFile: File, labelFile: File?) {
        scope.launch(Dispatchers.IO) {
            viewModel.loadFromFile(modelFile, labelFile)
        }
    }

    val labelFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelFile = pendingModelFile ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val labelFile = copyUriToInternal(context, uri, "labels_${System.currentTimeMillis()}.txt")
                withContext(Dispatchers.Main) { loadModelWithLabel(modelFile, labelFile) }
            }
        } else { loadModelWithLabel(modelFile, null) }
    }

    val modelFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                // 端侧只能加载 ONNX；选到 .pt/.torchscript 等先友好提示，不进入加载流程
                val displayName = queryDisplayName(context, uri)
                val lower = displayName?.lowercase() ?: ""
                if (UNSUPPORTED_MODEL_EXTS.any { lower.endsWith(it) }) {
                    withContext(Dispatchers.Main) { unsupportedFormatInfo = UnsupportedFormatInfo(displayName ?: "所选文件") }
                    return@launch
                }
                val modelFile = copyUriToInternal(context, uri, "model_${System.currentTimeMillis()}.onnx") ?: return@launch
                viewModel.loadFromFile(modelFile)
                withContext(Dispatchers.Main) {
                    pendingModelFile = modelFile
                    labelSelectInfo = LabelSelectInfo(modelFile.name)
                }
            }
        }
    }

    // 拍照函数
    fun takePhoto() {
        val capture = imageCapture ?: return
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "YOLO_${timeStamp}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YOLOTracker")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()
        capture.takePicture(outputOptions, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(context, "拍照失败: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 切换镜头
    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                         else CameraSelector.DEFAULT_BACK_CAMERA
        zoomRatio = 1f
    }

    // ── UI ──
    Box(modifier = modifier.fillMaxSize()) {
        when {
            !hasCameraPermission -> PermissionScreen(onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            !uiState.isModelLoaded -> LoadingScreen(error = uiState.error)
            else -> {
                // 摄像头预览
                CameraPreview(
                    cameraSelector = cameraSelector,
                    viewModel = viewModel,
                    onCameraReady = { cam, ic, mz ->
                        camera = cam
                        imageCapture = ic
                        maxZoom = mz
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 检测框叠加
                DetectionOverlayView(
                    detections = uiState.detections,
                    previewWidth = uiState.previewWidth,
                    previewHeight = uiState.previewHeight,
                    modifier = Modifier.fillMaxSize()
                )

                // ── 顶部信息栏（合并） ──
                TopBar(
                    trackedCount = uiState.trackedCount,
                    activeCount = uiState.activeCount,
                    fps = uiState.fps,
                    modelName = uiState.modelName,
                    onModelClick = { showModelDialog = true },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 12.dp, end = 12.dp)
                )

                // ── 底部控制栏 ──
                BottomBar(
                    confThreshold = confThreshold,
                    onConfChange = { viewModel.updateConfThreshold(it) },
                    zoomRatio = zoomRatio,
                    maxZoom = maxZoom,
                    onZoomChange = { newZoom ->
                        zoomRatio = newZoom.coerceIn(1f, maxZoom)
                        camera?.cameraControl?.setZoomRatio(zoomRatio)
                    },
                    isFrontCamera = isFrontCamera,
                    onCapture = { takePhoto() },
                    onSwitchCamera = { switchCamera() },
                    onModelClick = { showModelDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                )

                // 错误提示（持久显示，可复制）
                uiState.error?.let { error ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[${uiState.errorTime}] $error", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    viewModel.dismissError()
                                }, modifier = Modifier.height(28.dp).padding(0.dp)) {
                                    Text("关闭", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                                }
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("error", "[${uiState.errorTime}] $error"))
                                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.height(28.dp).padding(0.dp)) {
                                    Text("复制", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 对话框
        if (showModelDialog) {
            ModelSwitchDialog(
                onSelectFile = { showModelDialog = false; modelFilePicker.launch(arrayOf("application/octet-stream", "*/*")) },
                onUseBuiltIn = { showModelDialog = false; scope.launch(Dispatchers.IO) { viewModel.loadBuiltIn() } },
                currentModelName = uiState.modelName,
                onDismiss = { showModelDialog = false }
            )
        }
        labelSelectInfo?.let { info ->
            LabelSelectDialog(
                modelName = info.modelName,
                onSelectFile = { labelSelectInfo = null; labelFilePicker.launch(arrayOf("text/plain", "*/*")) },
                onUseDefault = {
                    pendingModelFile = null; labelSelectInfo = null
                }
            )
        }
        unsupportedFormatInfo?.let { info ->
            UnsupportedFormatDialog(
                fileName = info.fileName,
                onDismiss = { unsupportedFormatInfo = null }
            )
        }
    }
}

// ─── 摄像头预览（支持切换镜头 + 变焦 + 拍照） ────────────────

@Composable
private fun CameraPreview(
    cameraSelector: CameraSelector,
    viewModel: MainViewModel,
    onCameraReady: (Camera, ImageCapture, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    // 当 cameraSelector 变化时重新绑定
    var currentSelector by remember { mutableStateOf(cameraSelector) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val aspectRatio = androidx.camera.core.AspectRatio.RATIO_4_3

                fun bindCamera(selector: CameraSelector) {
                    val preview = Preview.Builder().setTargetAspectRatio(aspectRatio).build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val capture = ImageCapture.Builder()
                        .setTargetAspectRatio(aspectRatio)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(aspectRatio)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor) { proxy -> processImage(proxy, viewModel, selector == CameraSelector.DEFAULT_FRONT_CAMERA) } }

                    try {
                        cameraProvider.unbindAll()
                        val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
                        val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                        onCameraReady(cam, capture, maxZ)

                        // 双指缩放手势
                        val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                val newRatio = (cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * detector.scaleFactor
                                cam.cameraControl.setZoomRatio(newRatio.coerceIn(1f, maxZ))
                                return true
                            }
                        })
                        previewView.setOnTouchListener { _, event ->
                            scaleDetector.onTouchEvent(event)
                            true
                        }
                    } catch (_: Exception) {}
                }

                bindCamera(cameraSelector)
                currentSelector = cameraSelector

                // 监听 selector 变化（通过 recomposition）
                // Compose 的 AndroidView factory 只执行一次，
                // 所以用 update 回调处理 selector 变化
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        update = { previewView ->
            // selector 变化时重新绑定
            if (cameraSelector != currentSelector) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val lifecycleOwner2 = (previewView.context as? androidx.lifecycle.LifecycleOwner)
                        ?: return@addListener
                    val aspectRatio = androidx.camera.core.AspectRatio.RATIO_4_3

                    val preview = Preview.Builder().setTargetAspectRatio(aspectRatio).build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setTargetAspectRatio(aspectRatio)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(aspectRatio)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor) { proxy -> processImage(proxy, viewModel, cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) } }

                    try {
                        cameraProvider.unbindAll()
                        val cam = cameraProvider.bindToLifecycle(lifecycleOwner2, cameraSelector, preview, capture, analysis)
                        val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                        onCameraReady(cam, capture, maxZ)
                        currentSelector = cameraSelector

                        val scaleDetector = ScaleGestureDetector(previewView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                val newRatio = (cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * detector.scaleFactor
                                cam.cameraControl.setZoomRatio(newRatio.coerceIn(1f, maxZ))
                                return true
                            }
                        })
                        previewView.setOnTouchListener { _, event ->
                            scaleDetector.onTouchEvent(event)
                            true
                        }
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(previewView.context))
            }
        },
        modifier = modifier
    )
}

private var processCount = 0

private fun processImage(imageProxy: ImageProxy, viewModel: MainViewModel, isFrontCamera: Boolean) {
    try {
        // 节流前置：未到推理时刻就直接跳过，省去昂贵的 YUV→Bitmap→旋转
        if (!viewModel.shouldRunInference()) return
        // 按 CameraX 给出的旋转角把图像转正，使其与 PreviewView 的显示方向一致；
        // 前摄再做水平镜像，与 PreviewView 的镜像预览对齐。
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxyToBitmap(imageProxy, rotation, isFrontCamera)
        if (bitmap != null) {
            processCount++
            if (processCount % 30 == 1) {
                android.util.Log.d("CameraScreen", "processImage: bitmap=${bitmap.width}x${bitmap.height}, rot=$rotation, front=$isFrontCamera, 尝试推理...")
            }
            // 传旋转后 bitmap 的实际尺寸（方向已与屏幕一致），供叠加层 FIT_CENTER 映射
            viewModel.processFrame(bitmap, bitmap.width, bitmap.height)
            bitmap.recycle()
        } else {
            android.util.Log.w("CameraScreen", "imageProxyToBitmap 返回 null")
        }
    } catch (e: Exception) {
        android.util.Log.e("CameraScreen", "processImage 异常", e)
    } finally { imageProxy.close() }
}

// ─── YUV → Bitmap ─────────────────────────────────────────

/**
 * YUV → Bitmap（JPEG 方案，逐行安全读取 UV buffer）。
 *
 * 选择 U buffer（limit 更大）读取交错 UV 数据，保证 NV21 格式正确。
 * 逐行读取防止 BufferUnderflowException。
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy, rotationDegrees: Int, mirror: Boolean): Bitmap? {
    val width = imageProxy.width; val height = imageProxy.height
    if (width <= 0 || height <= 0) return null
    try {
        val planes = imageProxy.planes; if (planes.size < 3) return null
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride; val uvPixelStride = uPlane.pixelStride; val uvRowStride = uPlane.rowStride

        val nv21Size = width * height * 3 / 2; val nv21 = ByteArray(nv21Size)
        val uvHeight = height / 2; val uvWidth = width / 2; val uvOffset = width * height
        val uvBytesPerRow = uvWidth * uvPixelStride

        // Y 平面
        yBuffer.rewind()
        if (yRowStride == width) yBuffer.get(nv21, 0, width * height)
        else for (row in 0 until height) { yBuffer.position(row * yRowStride); yBuffer.get(nv21, row * width, width) }

        // UV 平面
        if (uvPixelStride == 2) {
            // 选择 limit 更大的 buffer（包含完整交错 UV 数据）
            // U buffer: [U0,V0,U1,V1,...] — NV21 需要 [V0,U0,V1,U1,...]
            // 如果 U buffer limit >= V buffer limit，说明 U buffer 包含完整数据
            // 但 NV21 需要 V 在前，所以用 V buffer（它以 V 开头）
            val uvBuf = if (vBuffer.limit() >= uBuffer.limit()) vBuffer else uBuffer
            uvBuf.rewind(); var dst = uvOffset
            for (row in 0 until uvHeight) {
                val srcPos = row * uvRowStride
                if (srcPos + uvBytesPerRow > uvBuf.limit()) break
                uvBuf.position(srcPos)
                uvBuf.get(nv21, dst, uvBytesPerRow)
                dst += uvBytesPerRow
            }
        } else {
            // 分离平面：手动交错 V,U
            uBuffer.rewind(); vBuffer.rewind(); val vrs = vPlane.rowStride; var dst = uvOffset
            for (row in 0 until uvHeight) {
                val ur = row * uvRowStride; val vr = row * vrs
                for (col in 0 until uvWidth) {
                    if (dst + 1 >= nv21Size) break
                    if (vr + col >= vBuffer.limit() || ur + col >= uBuffer.limit()) break
                    nv21[dst++] = vBuffer.get(vr + col); nv21[dst++] = uBuffer.get(ur + col)
                }
            }
        }

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 95, out)
        val jpegData = out.toByteArray(); out.close()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return null

        // 无需变换直接返回；否则先旋转转正，再（前摄）水平镜像
        if (rotationDegrees == 0 && !mirror) return decoded
        val matrix = android.graphics.Matrix()
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        val transformed = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed != decoded) decoded.recycle()
        return transformed
    } catch (e: Exception) { android.util.Log.e("CameraScreen", "imageProxyToBitmap 异常", e); return null }
}

// ─── 顶部信息栏 ─────────────────────────────────────────

@Composable
private fun TopBar(
    trackedCount: Int, activeCount: Int, fps: Float,
    modelName: String, onModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceVariant.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：统计
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniStat("追踪", "$trackedCount")
            MiniStat("活跃", "$activeCount")
            MiniStat("FPS", "${fps.toInt()}")
        }
        // 右侧：模型名 + 切换
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onModelClick() }) {
            Text(modelName, style = MaterialTheme.typography.labelSmall, color = OnSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
            Spacer(Modifier.width(4.dp))
            Text("▼", fontSize = 10.sp, color = OnSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = OnSurface, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurface.copy(alpha = 0.5f))
    }
}

// ─── 底部控制栏 ─────────────────────────────────────────

@Composable
private fun BottomBar(
    confThreshold: Float, onConfChange: (Float) -> Unit,
    zoomRatio: Float, maxZoom: Float, onZoomChange: (Float) -> Unit,
    isFrontCamera: Boolean,
    onCapture: () -> Unit, onSwitchCamera: () -> Unit, onModelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 第一行：缩放 + 置信度
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceVariant.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩放
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔍", fontSize = 14.sp)
                Text("${"%.1f".format(zoomRatio)}x", style = MaterialTheme.typography.labelMedium, color = OnSurface, fontFamily = FontFamily.Monospace)
                Slider(
                    value = zoomRatio, onValueChange = onZoomChange,
                    valueRange = 1f..maxZoom.coerceAtLeast(1f),
                    modifier = Modifier.width(100.dp),
                    colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                )
            }
            // 置信度
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("阈值", style = MaterialTheme.typography.labelSmall, color = OnSurface.copy(alpha = 0.6f))
                Text("${"%.2f".format(confThreshold)}", style = MaterialTheme.typography.labelMedium, color = OnSurface, fontFamily = FontFamily.Monospace)
                Slider(
                    value = confThreshold, onValueChange = onConfChange,
                    valueRange = 0.1f..0.9f, steps = 7,
                    modifier = Modifier.width(100.dp),
                    colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 第二行：按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 切换镜头
            ControlButton(
                icon = "🔄",
                label = if (isFrontCamera) "前摄" else "后摄",
                onClick = onSwitchCamera
            )
            // 拍照（大按钮）
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(Primary).clickable { onCapture() },
                contentAlignment = Alignment.Center
            ) {
                Text("📸", fontSize = 28.sp)
            }
            // 模型切换
            ControlButton(icon = "⚙", label = "模型", onClick = onModelClick)
        }
    }
}

@Composable
private fun ControlButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceVariant.copy(alpha = 0.85f)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(icon, fontSize = 20.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurface.copy(alpha = 0.6f))
    }
}

// ─── 检测叠加层 ─────────────────────────────────────────

@Composable
private fun DetectionOverlayView(detections: List<TrackedDetection>, previewWidth: Int, previewHeight: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> DetectionOverlay(ctx).apply { setWillNotDraw(false) } },
        update = { it.updateDetections(detections, previewWidth, previewHeight) },
        modifier = modifier
    )
}

// ─── 对话框 ─────────────────────────────────────────────

@Composable
private fun ModelSwitchDialog(onSelectFile: () -> Unit, onUseBuiltIn: () -> Unit, currentModelName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换模型", color = OnSurface) },
        text = { Column { Text("当前: $currentModelName", style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f)); Spacer(Modifier.height(12.dp)); Text("选择 ONNX 模型文件，加载后可选择自定义标签文件", style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.8f)) } },
        confirmButton = { TextButton(onClick = onSelectFile) { Text("选择文件") } },
        dismissButton = { Row { TextButton(onClick = onUseBuiltIn) { Text("恢复内置") }; TextButton(onClick = onDismiss) { Text("取消") } } },
        containerColor = Surface, titleContentColor = OnSurface, textContentColor = OnSurface.copy(alpha = 0.8f)
    )
}

@Composable
private fun LabelSelectDialog(modelName: String, onSelectFile: () -> Unit, onUseDefault: () -> Unit) {
    AlertDialog(
        onDismissRequest = onUseDefault,
        title = { Text("选择标签来源", color = OnSurface) },
        text = { Column { Text("模型 \"$modelName\" 已加载。", style = MaterialTheme.typography.bodyMedium, color = OnSurface.copy(alpha = 0.8f)); Spacer(Modifier.height(8.dp)); Text("当前使用默认 COCO80 标签。如需自定义标签，请选择标签文件（.txt），每行一个类别名称。", style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f)) } },
        confirmButton = { TextButton(onClick = onSelectFile) { Text("选择标签文件", color = Primary) } },
        dismissButton = { TextButton(onClick = onUseDefault) { Text("使用默认COCO80") } },
        containerColor = Surface, titleContentColor = OnSurface, textContentColor = OnSurface.copy(alpha = 0.8f)
    )
}

@Composable
private fun UnsupportedFormatDialog(fileName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("不支持的模型格式", color = OnSurface) },
        text = { Column {
            Text("\"$fileName\" 无法在安卓端直接加载。", style = MaterialTheme.typography.bodyMedium, color = OnSurface.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
            Text("安卓端只支持 ONNX 模型。请在电脑上用项目里的 export_model.py 把 .pt 转成 .onnx：\n\n    python export_model.py 你的模型.pt\n\n再导入生成的 .onnx 文件。", style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了", color = Primary) } },
        containerColor = Surface, titleContentColor = OnSurface, textContentColor = OnSurface.copy(alpha = 0.8f)
    )
}

// ─── 占位页面 ────────────────────────────────────────────

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📷", style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(16.dp))
            Text("需要摄像头权限", style = MaterialTheme.typography.titleLarge, color = OnSurface); Spacer(Modifier.height(8.dp))
            Text("用于实时目标检测与跟踪", style = MaterialTheme.typography.bodyMedium, color = OnSurface.copy(alpha = 0.6f)); Spacer(Modifier.height(32.dp))
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("授予权限") }
        }
    }
}

@Composable
private fun LoadingScreen(error: String? = null) {
    Box(modifier = Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) { Text("❌", style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(16.dp)); Text(error, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
            else { CircularProgressIndicator(color = Primary); Spacer(Modifier.height(16.dp)); Text("加载模型中...", style = MaterialTheme.typography.bodyLarge, color = OnSurface.copy(alpha = 0.6f)) }
        }
    }
}

// ─── 工具 ────────────────────────────────────────────────

private data class LabelSelectInfo(val modelName: String)
private data class UnsupportedFormatInfo(val fileName: String)
private val UNSUPPORTED_MODEL_EXTS = listOf(".pt", ".pth", ".pkl", ".torchscript", ".ptl")

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) it.getString(ni) else null
            } else null
        }
    } catch (_: Exception) { null }
}

private fun copyUriToInternal(context: android.content.Context, uri: Uri, fileName: String): File? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val output = File(context.filesDir, fileName)
        input.use { it.copyTo(output.outputStream()) }
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use { if (it.moveToFirst()) { val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) { val on = it.getString(ni); if (on != null && on != fileName) { val r = File(context.filesDir, on); if (r.exists()) r.delete(); if (output.renameTo(r)) return r } } } }
        output
    } catch (e: Exception) { android.util.Log.e("CameraScreen", "copyUriToInternal 失败", e); null }
}
