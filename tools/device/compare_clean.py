"""
So âm thanh đã làm sạch của điện thoại với desktop trên CÙNG PCM gốc (Stage 7, D2 / D3 / F1).

Đầu vào là thư mục do `audio_debug.ps1 -PullClean <thư mục>` kéo về: goc.pcm (PCM giải mã trên máy), sach.pcm (kết quả
làm sạch + cắt lặng của máy), ket_qua.json (cài đặt máy đã dùng: cai_dat + lam_sach), danh_sach_doan.json. Script chạy
clean_audio của desktop trên đúng goc.pcm với đúng cài đặt đó, rồi so:
- SNR của sach.pcm (int16) so với desktop (`_to_pcm16`), khi số mẫu bằng nhau (D85: ≥ 60 dB);
- bản đồ thời gian và điểm cắt đoạn theo dung sai B9 (như real_golden.py --compare).
Ghi thêm desktop.sach.pcm vào cùng thư mục (dùng cho tệp nghe thử F1).

Chạy:
    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\device\\compare_clean.py <thư mục>
"""

from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "golden"))

import numpy as np  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import audio_processing  # noqa: E402
import chunker  # noqa: E402
from audio_io import _to_pcm16  # noqa: E402

SR = 16000


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    folder = Path(sys.argv[1])
    r = json.loads((folder / "ket_qua.json").read_text(encoding="utf-8"))
    phone_list = json.loads((folder / "danh_sach_doan.json").read_text(encoding="utf-8"))
    c = r["cai_dat"]
    cl = c["lam_sach"]
    y = np.fromfile(folder / "goc.pcm", dtype="<i2").astype(np.float32) / np.float32(32768.0)
    params = dict(
        denoise=cl["denoise"], target_dbfs=c["target_dbfs"], silence_trimming=c["trim_silence"],
        min_silence_len=c["min_silence_len"], silence_thresh_offset=c["silence_thresh_offset"],
        keep_silence=c["keep_silence"], noise_sample_sec=cl["noise_sample_sec"], bandpass_low=cl["bandpass_low"],
        bandpass_high=cl["bandpass_high"], prop_decrease=cl["prop_decrease"], level_window_sec=cl["level_window_sec"],
        transient_clamp_db=cl["transient_clamp_db"],
    )
    logs = []
    with mock.patch.object(audio_processing, "read_audio", return_value=y):
        clean, sr, tm = audio_processing.clean_audio("goc.pcm", resample_hz=SR, progress_callback=logs.append, **params)
    desk = np.frombuffer(_to_pcm16(clean), dtype="<i2")
    (folder / "desktop.sach.pcm").write_bytes(desk.tobytes())
    phone = np.fromfile(folder / "sach.pcm", dtype="<i2")
    points = chunker.find_split_points(clean, SR, c["chunk_minutes"], c["search_seconds"], c["min_last_chunk_seconds"])

    print("desktop:", " | ".join(s.strip() for s in logs if "ghim" in s or "Thoi luong" in s))
    print(f"máy: {len(phone)} mẫu, desktop: {len(desk)} mẫu")
    n = min(len(phone), len(desk))
    if len(phone) == len(desk):
        a, b = desk.astype(np.float64), phone.astype(np.float64)
    else:
        # Lệch vài mẫu ở một khoảng giữ lại thì các mẫu sau trượt: chỉ so phần đầu trước chỗ lệch đầu tiên.
        print("số mẫu khác nhau: chỉ so nửa đầu")
        a, b = desk[: n // 2].astype(np.float64), phone[: n // 2].astype(np.float64)
    err = float(np.sum((a - b) ** 2))
    snr = math.inf if err == 0 else 10 * math.log10(float(np.sum(a * a)) / err)
    print(f"SNR sach.pcm máy so với desktop: {snr:.1f} dB → {'ĐẠT' if snr >= 60 else 'TRƯỢT'} (≥ 60 dB)")

    d = dict(time_map=tm.to_json(), orig_duration=round(tm.orig_duration, 3), points=[int(p) for p in points])
    import real_golden  # noqa: E402  (dùng lại phép so B9)
    real_golden.compare(d, phone_list)


if __name__ == "__main__":
    main()
