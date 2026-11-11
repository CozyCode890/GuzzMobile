"""
So PCM giải mã trên điện thoại (goc.pcm của Stage 2: int16 LE mono 16 kHz) với ffmpeg của desktop (bước D2, D3).

Chạy bằng Python của desktop (numpy + scipy):
    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\device\\compare_ffmpeg.py --pcm <goc.pcm> --src <tệp gốc> [--track N]
    ... --src <tệp gốc> --duration-only --samples <số mẫu trên máy>

- Thời lượng: số mẫu của goc.pcm / 16000 so với dòng "Duration:" của `ffmpeg -i` và với độ dài ffmpeg tự giải mã.
- SNR: ffmpeg giải mã cùng luồng ra `-ac 1 -ar 16000 -f s16le` (cách desktop đọc âm thanh); căn độ trễ bằng tương quan
  chéo trên 30 giây đầu; SNR trên cả bài, kèm SNR sau khi chỉnh hệ số (để tách lệch biên độ khỏi lệch dạng sóng).
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from scipy import signal

SR = 16000
FFMPEG = Path(os.environ.get("GUZZ_DESKTOP_DIR") or r"D:\Guzz") / "runtime" / "ffmpeg" / "ffmpeg.exe"


def ffmpeg_duration(src: Path) -> float | None:
    out = subprocess.run([str(FFMPEG), "-hide_banner", "-nostdin", "-i", str(src)], capture_output=True)
    m = re.search(r"Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)", out.stderr.decode("utf-8", "replace"))
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None


def ffmpeg_pcm(src: Path, track: int | None) -> np.ndarray:
    with tempfile.TemporaryDirectory() as d:
        out = Path(d) / "ref.pcm"
        cmd = [str(FFMPEG), "-nostdin", "-hide_banner", "-loglevel", "error", "-i", str(src)]
        if track is not None:
            cmd += ["-map", f"0:a:{track}"]
        cmd += ["-vn", "-ac", "1", "-ar", str(SR), "-f", "s16le", "-acodec", "pcm_s16le", str(out)]
        subprocess.run(cmd, check=True)
        return np.fromfile(out, dtype="<i2").astype(np.float64) / 32768.0


def best_lag(ref: np.ndarray, out: np.ndarray, max_lag: int) -> int:
    n = min(len(ref), len(out))
    corr = signal.correlate(out[:n], ref[:n], mode="full", method="fft")
    mid = n - 1
    return int(np.argmax(corr[mid - max_lag: mid + max_lag + 1])) - max_lag


def snr_db(ref: np.ndarray, out: np.ndarray) -> float:
    err = float(np.sum((ref - out) ** 2))
    return float("inf") if err == 0 else 10 * np.log10(float(np.sum(ref ** 2)) / err)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--pcm")
    ap.add_argument("--track", type=int)
    ap.add_argument("--duration-only", action="store_true")
    ap.add_argument("--samples", type=int, help="số mẫu goc.pcm trên máy (khi không kéo goc.pcm về)")
    args = ap.parse_args()
    src = Path(args.src)

    declared = ffmpeg_duration(src)
    if args.duration_only:
        n = args.samples
        decoded = len(ffmpeg_pcm(src, args.track)) / SR
        print(f"{src.name}: máy {n / SR:.3f} s, ffmpeg giải mã {decoded:.3f} s (lệch {n / SR - decoded:+.3f} s), "
              f"ffmpeg Duration {declared:.3f} s (lệch {n / SR - declared:+.3f} s)")
        return

    out = np.fromfile(args.pcm, dtype="<i2").astype(np.float64) / 32768.0
    ref = ffmpeg_pcm(src, args.track)
    lag = best_lag(ref[: 30 * SR], out[: 30 * SR + 8000], 4000)
    r, o = (ref, out[lag:]) if lag >= 0 else (ref[-lag:], out)
    n = min(len(r), len(o))
    r, o = r[:n], o[:n]
    gain = float(np.dot(r, o) / np.dot(o, o))
    print(f"{src.name}: máy {len(out) / SR:.3f} s, ffmpeg giải mã {len(ref) / SR:.3f} s, ffmpeg Duration "
          f"{declared:.3f} s, lệch so với Duration {len(out) / SR - declared:+.3f} s; trễ {lag} mẫu; "
          f"SNR {snr_db(r, o):.1f} dB (chỉnh hệ số {gain:.4f}: {snr_db(r, gain * o):.1f} dB)")


if __name__ == "__main__":
    main()
