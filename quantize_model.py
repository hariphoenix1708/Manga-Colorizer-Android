import numpy as np
from PIL import Image
import os
import sys
import onnx
from onnxruntime.quantization import QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import (
    get_qnn_qdq_config
)
from onnxruntime.quantization.calibrate import CalibrationDataReader

# --- CONFIGURATION ---
CALIB_DIR = r"G:\Manga"
INPUT_MODEL = r"C:\Users\hariv\Documents\old_model\alacgan.onnx"
OUTPUT_MODEL = os.path.abspath("alacgan_qdq.onnx")
MODEL_INPUT_SIZE = 576

def resize_and_pad_to_5ch(img: Image.Image) -> np.ndarray:
    w, h = img.size
    if h < w:
        ratio = h / (MODEL_INPUT_SIZE * 1.5)
        new_w = int(np.ceil(w / ratio))
        new_h = int(MODEL_INPUT_SIZE * 1.5)
    else:
        ratio = w / MODEL_INPUT_SIZE
        new_w = MODEL_INPUT_SIZE
        new_h = int(np.ceil(h / ratio))
    img = img.convert("L").resize((new_w, new_h), Image.BILINEAR)
    pad_w = (32 - new_w % 32) % 32
    pad_h = (32 - new_h % 32) % 32
    canvas = Image.new("L", (new_w + pad_w, new_h + pad_h), color=255)
    canvas.paste(img, (0, 0))
    arr = np.asarray(canvas, dtype=np.float32) / 255.0
    gray = arr[None, None, :, :]
    hints = np.zeros((1, 4, arr.shape[0], arr.shape[1]), dtype=np.float32)
    return np.concatenate([gray, hints], axis=1)

class MangaCalibReader(CalibrationDataReader):
    def __init__(self, folder, count=5):
        self.files = []
        for root, _, files in os.walk(folder):
            for f in files:
                if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                    self.files.append(os.path.join(root, f))
        if len(self.files) > count:
            import random
            random.seed(42)
            self.files = random.sample(self.files, count)
        print(f"Found {len(self.files)} images for calibration.")
        sys.stdout.flush()
        self.idx = 0

    def get_next(self):
        if self.idx >= len(self.files):
            return None
        try:
            print(f"Feeding image {self.idx + 1}/{len(self.files)}: {self.files[self.idx]}")
            sys.stdout.flush()
            arr = resize_and_pad_to_5ch(Image.open(self.files[self.idx]))
            self.idx += 1
            return {"input": arr}
        except Exception as e:
            print(f"Error processing {self.files[self.idx]}: {e}")
            sys.stdout.flush()
            self.idx += 1
            return self.get_next()

if __name__ == "__main__":
    print(f"Starting quantization of {INPUT_MODEL}...")
    sys.stdout.flush()
    
    # 1. Calibration Config
    print("Step 1: Generating calibration config...")
    sys.stdout.flush()
    qnn_config = get_qnn_qdq_config(
        INPUT_MODEL,
        MangaCalibReader(CALIB_DIR, count=50),
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QUInt8,
    )
    
    # 2. Quantize
    print("Step 2: Running quantization...")
    sys.stdout.flush()
    quantize(INPUT_MODEL, OUTPUT_MODEL, qnn_config)
    print(f"Done! Created {OUTPUT_MODEL}")
    sys.stdout.flush()
