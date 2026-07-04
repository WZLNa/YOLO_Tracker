"""把 YOLO .pt 模型导出为 Android 端可用的 ONNX。

用法：
    python export_model.py <模型.pt> [imgsz]      # 转换指定模型
    python export_model.py                          # 无参时用默认模型

导出 end2end ONNX，输出 (1, 300, 6) = [x1, y1, x2, y2, score, class_id]
（xyxy 角点、已归一化，见 android_app/CLAUDE.md 的「ONNX 模型格式」）。

⚠️ 安卓端只能跑 ONNX，无法直接加载 .pt —— .pt 是 PyTorch 训练权重，
   需要 PyTorch 运行时 + 模型定义代码，手机上没有。务必先用本脚本转换。
"""
import sys
from pathlib import Path

from ultralytics import YOLO

DEFAULT_MODEL = "best26n_146_batch64_lrf0.04.pt"


def main():
    model_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MODEL
    imgsz = int(sys.argv[2]) if len(sys.argv) > 2 else 640

    if not Path(model_path).exists():
        print(f"❌ 找不到模型文件: {model_path}")
        print("用法: python export_model.py <模型.pt> [imgsz]")
        sys.exit(1)

    print(f"加载模型: {model_path}  (imgsz={imgsz})")
    model = YOLO(model_path)
    out = model.export(format="onnx", imgsz=imgsz)

    onnx_path = out if isinstance(out, str) else Path(model_path).with_suffix(".onnx")
    print(f"\n✅ 导出完成: {onnx_path}")
    print("导入手机：把该 .onnx（和配套 labels.txt）拷到手机 → app 内「⚙ 模型」→ 选择文件。")


if __name__ == "__main__":
    main()
