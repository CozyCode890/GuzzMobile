"""
Tạo cặp tệp nghe thử A/B (Stage 7, F1, D90) từ một thư mục đã chạy compare_clean.py: sach.pcm (điện thoại) và
desktop.sach.pcm (desktop, cùng goc.pcm, cùng preset). Cắt 3 đoạn 30 giây giống nhau ở cả hai, đặt tên A / B ngẫu nhiên
cho từng đoạn, ghi WAV 16 kHz vào <ra>/<tên bài>/ và khoá giải `khoa_giai.json` (ngoài repo: bản ghi thật).

Chạy:
    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\device\\make_ab.py <thư mục bài> <thư mục ra> [giây bắt đầu ...]
Mặc định 3 đoạn ở 20%, 50%, 80% độ dài.
"""

from __future__ import annotations

import json
import secrets
import sys
import wave
from pathlib import Path

import numpy as np

SR = 16000
SECONDS = 30


def write_wav(path: Path, pcm: np.ndarray) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.astype("<i2").tobytes())


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    folder, out_root = Path(sys.argv[1]), Path(sys.argv[2])
    phone = np.fromfile(folder / "sach.pcm", dtype="<i2")
    desk = np.fromfile(folder / "desktop.sach.pcm", dtype="<i2")
    n = min(len(phone), len(desk))
    starts = [float(s) for s in sys.argv[3:]] or [n / SR * f for f in (0.2, 0.5, 0.8)]
    out = out_root / folder.name
    out.mkdir(parents=True, exist_ok=True)
    key = {"bai": folder.name, "doan": []}
    for i, st in enumerate(starts, 1):
        a = int(st * SR)
        b = min(n, a + SECONDS * SR)
        phone_is_a = secrets.randbelow(2) == 0
        first, second = (phone, desk) if phone_is_a else (desk, phone)
        write_wav(out / f"doan{i}_A.wav", first[a:b])
        write_wav(out / f"doan{i}_B.wav", second[a:b])
        key["doan"].append({"doan": i, "bat_dau_giay": round(a / SR, 1), "A": "dien_thoai" if phone_is_a else "desktop",
                            "B": "desktop" if phone_is_a else "dien_thoai"})
    (out / "khoa_giai.json").write_text(json.dumps(key, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{out}: {len(starts)} cặp A/B, khoá giải ở khoa_giai.json (đừng mở trước khi nghe)")


if __name__ == "__main__":
    main()
