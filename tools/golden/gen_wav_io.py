"""
Golden cho chính công cụ đọc WAV của test Kotlin (Golden.readWav)  →  testdata/golden/wav/

Ghi một WAV nhỏ bằng _common.write_wav16 với các mẫu đã biết (gồm cả hai đầu thang int16), kèm JSON liệt kê giá trị
int16. Test Kotlin đọc WAV và so: định dạng tệp Python ghi và cách Kotlin đọc phải khớp nhau.
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402

SR = 8000
SAMPLES = [0, 1, -1, 32767, -32768, 16384, -16384, 12345, -12345, 2, 0]


def main() -> None:
    out = _common.output_dir("wav")
    y = np.array(SAMPLES, dtype=np.float64) / 32768.0
    _common.write_wav16(out / "known_samples.wav", y, SR)
    back, sr = _common.read_wav16(out / "known_samples.wav")
    if sr != SR or [int(round(v * 32768)) for v in back] != SAMPLES:
        raise SystemExit("đọc lại WAV không khớp")
    _common.write_json(out / "known_samples.json", {"sr": SR, "samples_int16": SAMPLES},
                       os.path.basename(__file__))
    print(f"known_samples.wav: {len(SAMPLES)} mẫu, {SR} Hz")


if __name__ == "__main__":
    main()
