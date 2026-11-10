"""
Golden trên bản ghi thật cho Stage 2 (D46, bước B9). KHÔNG đặt tên gen_*: regenerate.ps1 không được chạy nó, và kết quả
nằm NGOÀI repo (bản ghi thật không commit).

Với mỗi bài B1–B5 của Stage 1: đọc `$GUZZ_SPIKE_DATA\\<mã>\\goc16k.wav` (giải mã bằng ffmpeg của desktop, 16 kHz mono
16-bit), chạy các hàm desktop theo thứ tự của clean_audio / prepare_chunks với cài đặt mặc định của config.default.txt,
bỏ các bước Stage 7 (D7): boost_and_normalize → trim_silence → find_split_points. Ghi
`$GUZZ_SPIKE_DATA\\golden-stage-02\\<mã>.json`. Test Kotlin `RealRecordingGoldenTest` đọc lại và so theo dung sai B9.

Chạy:
    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\golden\\real_golden.py            (cả 5 bài)
    ... real_golden.py B2 B4                                                               (một số bài)
    ... real_golden.py --pcm <goc.pcm lấy từ điện thoại> --name L1_120m_may             (bước D3)
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import audio_processing  # noqa: E402
import chunker  # noqa: E402
import config  # noqa: E402

CODES = ["B1", "B2", "B3", "B4", "B5"]
SR = 16000


def data_dir() -> Path:
    return Path(os.environ.get("GUZZ_SPIKE_DATA") or r"D:\GuzzMobileData\stage-01")


def exact_gain(y: np.ndarray, target: float) -> float | None:
    total = 0.0
    for i in range(0, y.size, 1 << 20):
        b = y[i:i + (1 << 20)].astype(np.float64)
        total += math.fsum(b * b)
    rms = (total / y.size) ** 0.5
    if rms <= 0:
        return None
    return float(np.float32(10.0 ** ((target - 20.0 * math.log10(rms)) / 20.0)))


def run(y: np.ndarray, source: str) -> dict:
    cfg = config.Config()  # mặc định của desktop (khớp config.default.txt ở các mục Stage 2 dùng)
    t0 = time.monotonic()
    d = audio_processing.measure_dbfs(y)
    gain = float(np.float32(10.0 ** ((cfg.target_dbfs - d) / 20.0))) if d != float("-inf") else None
    gain_exact = exact_gain(y, cfg.target_dbfs)
    boosted = audio_processing.boost_and_normalize(y.copy(), target_dBFS=cfg.target_dbfs)
    clean, kept = audio_processing.trim_silence(boosted, SR, silence_thresh_offset=cfg.silence_thresh_offset,
                                                min_silence_len=cfg.min_silence_len, keep_silence=cfg.keep_silence)
    tm = audio_processing.TimeMap([(a / SR, b / SR) for a, b in kept], len(boosted) / SR)
    points = chunker.find_split_points(clean, SR, cfg.chunk_minutes, cfg.chunk_search_seconds,
                                       cfg.min_last_chunk_seconds)
    elapsed = time.monotonic() - t0
    return dict(
        source=source, sr=SR, samples=int(len(y)),
        settings=dict(target_dbfs=cfg.target_dbfs, trim_silence=True, min_silence_len=cfg.min_silence_len,
                      silence_thresh_offset=cfg.silence_thresh_offset, keep_silence=cfg.keep_silence,
                      chunk_minutes=cfg.chunk_minutes, search_seconds=cfg.chunk_search_seconds,
                      min_last_chunk_seconds=cfg.min_last_chunk_seconds),
        dbfs=d, gain=gain, gain_exact=gain_exact,
        kept=[[int(a), int(b)] for a, b in kept],
        time_map=tm.to_json(), orig_duration=round(tm.orig_duration, 3),
        clean_samples=int(len(clean)), points=[int(p) for p in points],
        python_seconds=round(elapsed, 1),
    )


def write(name: str, data: dict) -> Path:
    out = data_dir() / "golden-stage-02"
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"{name}.json"
    payload = {"meta": _common.meta(os.path.basename(__file__)), **data}
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("codes", nargs="*", help="mã bài (mặc định B1–B5)")
    ap.add_argument("--pcm", help="tệp PCM 16-bit LE mono 16 kHz không header (goc.pcm lấy từ điện thoại)")
    ap.add_argument("--name", help="tên JSON ra khi dùng --pcm")
    args = ap.parse_args()

    if args.pcm:
        if not args.name:
            raise SystemExit("--pcm cần --name")
        y = (np.fromfile(args.pcm, dtype="<i2").astype(np.float32) / np.float32(32768.0))
        data = run(y, os.path.basename(args.pcm))
        path = write(args.name, data)
        print(f"{args.name}: {len(data['kept'])} khoảng giữ lại, {len(data['points']) - 1} đoạn → {path}")
        return

    for code in args.codes or CODES:
        wav = data_dir() / code / "goc16k.wav"
        y, sr = _common.read_wav16(wav)
        if sr != SR:
            raise SystemExit(f"{wav}: {sr} Hz, cần {SR}")
        data = run(y, f"{code}/goc16k.wav")
        data["wav"] = f"{code}/goc16k.wav"
        path = write(code, data)
        print(f"{code}: {len(y) / SR / 60:.1f} phút, hệ số {data['gain']} (chính xác {data['gain_exact']}), "
              f"{len(data['kept'])} khoảng giữ lại, {len(data['points']) - 1} đoạn, Python {data['python_seconds']} s "
              f"→ {path}")


if __name__ == "__main__":
    main()
