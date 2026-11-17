"""
Golden trên bản ghi thật cho Stage 2 (D46, bước B9). KHÔNG đặt tên gen_*: regenerate.ps1 không được chạy nó, và kết quả
nằm NGOÀI repo (bản ghi thật không commit).

Với mỗi bài B1–B5 của Stage 1: đọc `$GUZZ_SPIKE_DATA\\<mã>\\goc16k.wav` (giải mã bằng ffmpeg của desktop, 16 kHz mono
16-bit), chạy các hàm desktop theo thứ tự của clean_audio / prepare_chunks với cài đặt mặc định của config.default.txt,
bỏ các bước Stage 7 (D7): boost_and_normalize → trim_silence → find_split_points. Ghi
`$GUZZ_SPIKE_DATA\\golden-stage-02\\<mã>.json`. Test Kotlin `RealRecordingGoldenTest` đọc lại và so theo dung sai B9.

Stage 7 (C5, `--stage7`): chuỗi clean_audio ĐỦ với config.default.txt (preset Giảng viên đi lại: lọc dải tần, khử ồn,
cân bằng âm lượng), ghi `$GUZZ_SPIKE_DATA\\golden-stage-07\\<mã>.json` (bản đồ thời gian, điểm cắt) và
`<mã>.boosted.pcm`: tín hiệu đã làm sạch + chuẩn hoá, CHƯA cắt lặng, PCM 16-bit như `_to_pcm16` (≈ 220 MB một bài 2 giờ),
để test Kotlin đo SNR. Chạy: `... real_golden.py --stage7` (cả 5 bài) hoặc `--stage7 B4`.

Chạy:
    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\golden\\real_golden.py            (cả 5 bài)
    ... real_golden.py B2 B4                                                               (một số bài)
    ... real_golden.py --pcm <goc.pcm lấy từ điện thoại> --name L1_120m_may             (bước D3)
"""

from __future__ import annotations

import argparse
import dataclasses
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
    # Mặc định của config.default.txt (cũng là mặc định của app). KHÔNG dùng config.Config(): dataclass đó có mặc định
    # cũ (min_silence_len 700, silence_thresh_offset 16, keep_silence 300), khác config.default.txt (1000, 20, 400).
    cfg = config.load_config(str(_common.desktop_dir() / "config.default.txt"))
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


def run_stage7(y: np.ndarray, source: str, code: str) -> dict:
    """Chuỗi đủ của clean_audio (config.default.txt), tách bước cắt lặng ra để lưu được tín hiệu trước khi cắt."""
    from unittest import mock

    from audio_io import _to_pcm16

    cfg = config.load_config(str(_common.desktop_dir() / "config.default.txt"))
    t0 = time.monotonic()
    logs = []
    with mock.patch.object(audio_processing, "read_audio", return_value=y):
        boosted, sr, _ = audio_processing.clean_audio_from_config(
            dataclasses.replace(cfg, silence_trimming=False), source, progress_callback=logs.append)
    clean_seconds = time.monotonic() - t0
    clean, kept = audio_processing.trim_silence(boosted, SR, silence_thresh_offset=cfg.silence_thresh_offset,
                                                min_silence_len=cfg.min_silence_len, keep_silence=cfg.keep_silence)
    tm = audio_processing.TimeMap([(a / SR, b / SR) for a, b in kept], len(boosted) / SR)
    points = chunker.find_split_points(clean, SR, cfg.chunk_minutes, cfg.chunk_search_seconds,
                                       cfg.min_last_chunk_seconds)
    elapsed = time.monotonic() - t0
    out = data_dir() / "golden-stage-07"
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{code}.boosted.pcm").write_bytes(_to_pcm16(boosted))
    return dict(
        source=source, sr=SR, samples=int(len(y)),
        settings=dict(target_dbfs=cfg.target_dbfs, trim_silence=True, min_silence_len=cfg.min_silence_len,
                      silence_thresh_offset=cfg.silence_thresh_offset, keep_silence=cfg.keep_silence,
                      chunk_minutes=cfg.chunk_minutes, search_seconds=cfg.chunk_search_seconds,
                      min_last_chunk_seconds=cfg.min_last_chunk_seconds, denoise=cfg.denoise,
                      noise_sample_sec=cfg.noise_sample_sec, bandpass_low=cfg.bandpass_low,
                      bandpass_high=cfg.bandpass_high, prop_decrease=cfg.prop_decrease,
                      level_window_sec=cfg.level_window_sec, transient_clamp_db=cfg.transient_clamp_db),
        kept=[[int(a), int(b)] for a, b in kept],
        time_map=tm.to_json(), orig_duration=round(tm.orig_duration, 3),
        clean_samples=int(len(clean)), points=[int(p) for p in points], boosted=f"{code}.boosted.pcm",
        logs=logs, python_seconds=round(elapsed, 1), python_clean_seconds=round(clean_seconds, 1),
    )


def compare(desktop: dict, phone: dict) -> None:
    """So danh_sach_doan.json của điện thoại với desktop theo dung sai của bước B9 (như RealRecordingGoldenTest)."""
    tm_d = audio_processing.TimeMap([tuple(s) for s in desktop["time_map"]], desktop["orig_duration"])
    tm_p = audio_processing.TimeMap([tuple(s) for s in phone["ban_do_thoi_gian"]], phone["thoi_luong_goc"])
    kept_diff = abs(tm_d.clean_duration - tm_p.clean_duration)
    edges_d = [t for s in desktop["time_map"] for t in s]
    edges_p = [t for s in phone["ban_do_thoi_gian"] for t in s]
    arr_d, arr_p = np.array(edges_d), np.array(edges_p)
    unmatched = sum(1 for e in edges_d if np.min(np.abs(arr_p - e)) > 0.010) + \
        sum(1 for e in edges_p if np.min(np.abs(arr_d - e)) > 0.010)
    ratio = 1 - unmatched / (len(edges_d) + len(edges_p))
    cuts_d = [tm_d.to_original(p / SR) for p in desktop["points"]]
    cuts_p = [tm_p.to_original(t) for t in [0.0] + [c["ket_thuc_giay"] for c in phone["doan"]]]
    same_count = len(cuts_d) == len(cuts_p)
    max_cut = max(abs(a - b) for a, b in zip(cuts_d, cuts_p)) if same_count else float("nan")
    ok = phone["thoi_luong_goc"] == desktop["orig_duration"] and kept_diff <= 0.1 and ratio >= 0.999 \
        and same_count and max_cut <= 0.050
    print(f"so với điện thoại: thời lượng gốc {phone['thoi_luong_goc']} / {desktop['orig_duration']}; giữ lại lệch "
          f"{kept_diff:.3f} s; mốc TimeMap khớp {ratio * 100:.4f}% ({unmatched} lệch / {len(edges_d)} + {len(edges_p)}); "
          f"{len(cuts_p) - 1} / {len(cuts_d) - 1} đoạn; điểm cắt lệch tối đa {max_cut:.3f} s → {'ĐẠT' if ok else 'TRƯỢT'}")


def write(name: str, data: dict, folder: str = "golden-stage-02") -> Path:
    out = data_dir() / folder
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
    ap.add_argument("--compare", help="danh_sach_doan.json của điện thoại: so với kết quả desktop theo dung sai B9")
    ap.add_argument("--stage7", action="store_true", help="chuỗi làm sạch đủ của Stage 7 (golden-stage-07)")
    args = ap.parse_args()

    if args.stage7:
        for code in args.codes or CODES:
            wav = data_dir() / code / "goc16k.wav"
            y, sr = _common.read_wav16(wav)
            data = run_stage7(y, f"{code}/goc16k.wav", code)
            data["wav"] = f"{code}/goc16k.wav"
            path = write(code, data, "golden-stage-07")
            print(f"{code}: {len(y) / SR / 60:.1f} phút, {len(data['kept'])} khoảng giữ lại, "
                  f"{len(data['points']) - 1} đoạn, Python {data['python_seconds']} s "
                  f"(làm sạch {data['python_clean_seconds']} s) → {path}", flush=True)
        return

    if args.pcm:
        if not args.name:
            raise SystemExit("--pcm cần --name")
        y = (np.fromfile(args.pcm, dtype="<i2").astype(np.float32) / np.float32(32768.0))
        data = run(y, os.path.basename(args.pcm))
        path = write(args.name, data)
        print(f"{args.name}: {len(data['kept'])} khoảng giữ lại, {len(data['points']) - 1} đoạn → {path}")
        if args.compare:
            compare(data, json.loads(Path(args.compare).read_text(encoding="utf-8")))
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
