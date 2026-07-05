# YOLO Tracker — Android 实时目标检测与追踪

基于 **YOLO + ONNX Runtime** 的 Android 端侧实时目标检测与多目标追踪应用。

已测试：YOLO_V8、YOLO_V26

无需联网，全部推理在手机 CPU 上完成。

<p align="center">
  <img src="screenshots/demo.gif" width="320" alt="演示"/>
</p>

## 功能

- 实时摄像头检测 + 多目标 IOU 追踪（ID 分配、轨迹线绘制）
- 内置 YOLOv26 COCO 预训练模型（80 类），开箱即用
- 应用内「选择文件」导入任意 ONNX 模型，无需重新打包
- 无标签文件时自动使用 COCO 80 类标签
- 置信度阈值滑块实时调节
- 数码变焦（双指缩放 + 滑块）
- 前 / 后摄像头切换
- 一键拍照保存
- 错误日志持久显示 + 一键复制到剪贴板

## 快速开始

### 环境

- JDK 17+
- Android SDK (API 26+)

### 构建

```bash
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

> 也可直接下载 [Releases](https://github.com/WZLNa/YOLO_Tracker/releases/) 中的预编译 APK。

### 安装

1. 安装 APK 到 Android 手机
2. 首次启动授予摄像头权限
3. 自动加载内置 COCO 模型，即可看到检测结果

## 导入自定义模型

点击「⚙ 模型」→「选择文件」导入任意 ONNX 模型（需为 YOLO end2end 格式）。

### 模型导出（Python）

```bash
python export_model_end2endONNX.py 你的模型.pt [imgsz]
```

导出格式：
- 输入：`(1, 3, 640, 640)` float32 NCHW RGB，归一化 [0,1]
- 输出：`(1, 300, 6)` — `[x1, y1, x2, y2, score, class_id]`（xyxy 角点、已归一化）

### 标签文件

模型同目录下的 `labels.txt`（每行一个类别名）会被自动加载。无标签文件时默认使用 COCO 80 类标签。

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 摄像头 | CameraX (ImageAnalysis) |
| 推理引擎 | ONNX Runtime (CPU) |
| 跟踪算法 | IOU 贪心匹配 |
| 语言 | Kotlin |

## 架构

```
CameraX YUV_420_888 → NV21 → JPEG(95%) → Bitmap
  → 旋转/镜像 → letterbox 缩放 → ARGB → float NCHW [0,1]
  → ONNX Runtime 推理
  → 输出 (1, 300, 6) [x1,y1,x2,y2,score,class_id]
  → IOU 跟踪 → Canvas 叠加绘制
```

## 项目结构

```
├── app/src/main/java/com/example/yolotracker/
│   ├── MainActivity.kt              # 入口
│   ├── MainViewModel.kt             # 状态管理 + 帧处理
│   ├── model/
│   │   ├── Detection.kt             # Detection / TrackedDetection
│   │   ├── YoloDetector.kt          # ONNX 推理引擎（核心）
│   │   └── IOUTracker.kt            # IOU 跟踪器
│   └── ui/
│       ├── CameraScreen.kt          # 主界面
│       ├── DetectionOverlay.kt      # Canvas 检测框绘制
│       └── theme/                   # Material 3 主题
├── app/src/main/assets/
│   ├── yolo_model.onnx              # 内置 YOLOv8 COCO 模型
│   └── labels.txt                   # 类别标签
├── export_model_end2endONNX.py      # 模型导出脚本
└── releases/
    ├── YoloTracker-v1.0.apk         # 预编译 APK
    └── yolo26n.onnx                 # 内置模型（备用）
```

## 跟踪参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `iouThreshold` | 0.3 | IOU 匹配阈值 |
| `maxLostFrames` | 30 | 失联帧数上限 |
| `trailLength` | 30 | 轨迹线长度 |

## License

MIT

## 致谢

- [Ultralytics YOLO](https://github.com/ultralytics/ultralytics)
- [ONNX Runtime](https://onnxruntime.ai/)
- [CameraX](https://developer.android.com/training/camerax)
