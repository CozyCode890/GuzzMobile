"""
Bước E4 của Stage 1: chất lượng resampler trên điện thoại (D38).

Chạy bằng Python của desktop (numpy + scipy):
    D:\\Guzz\\runtime\\python\\python.exe -B -s spikes\\media\\compare_pcm.py [--phone-dir $D\\out\\phone\\decode]

Đọc các tệp f32 16 kHz mà bench `decode` ghi ra (`<tệp>_<resampler>.f32`, đã `adb pull` về --phone-dir) và so:
  - sweep48k: với scipy.signal.resample_poly(x, 1, 3) của cùng đầu vào 16-bit; SNR trong dải 0–7 kHz. Suy giảm ở 10 kHz
    và 15 kHz đo trên chính đoạn quét: quét tuyến tính nên mỗi thời điểm ứng với một tần số, lấy RMS đầu ra quanh lúc
    quét qua 10 / 15 kHz so với RMS của sin 0,5 (−9 dBFS). Không đo bằng tones48k được, vì alias của 10 kHz và 15 kHz khi
    ra 16 kHz rơi đúng vào 6 kHz và 1 kHz;
  - tones48k: biên độ của sin 1 kHz và 6 kHz sau resample (bình phương tối thiểu), so với 0,2;
  - tệp thật L1.m4a: với `ffmpeg -ac 1 -ar 16000 -f f32le`; căn độ trễ bằng tương quan chéo trên 30 s đầu rồi tính SNR
    trên cả bài; ghi cả lệch thời lượng so với dòng "Duration:" của ffmpeg (Kiểm của E3).
In bảng markdown.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import wave
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "diarization"))
import _common as c  # noqa: E402

import numpy as np  # noqa: E402
from scipy import signal  # noqa: E402

SR = 16000
SWEEP = (50.0, 23000.0, 60.0)  # f0, f1, giây: phải khớp prepare_data.make_sweep
SWEEP_AMP = 0.5


def read_f32(p: Path) -> np.ndarray:
    return np.fromfile(p, dtype="<f4").astype(np.float64)


def read_wav16(p: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(p), "rb") as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return x, sr


def best_lag(ref: np.ndarray, out: np.ndarray, max_lag: int) -> int:
    """Độ trễ (mẫu) để out[n + lag] khớp ref[n] nhất, tìm trong ±max_lag bằng tương quan chéo."""
    n = min(len(ref), len(out))
    corr = signal.correlate(out[:n], ref[:n], mode="full", method="fft")
    mid = n - 1
    lo, hi = mid - max_lag, mid + max_lag + 1
    return int(np.argmax(corr[lo:hi])) - max_lag


def aligned(ref: np.ndarray, out: np.ndarray, lag: int) -> tuple[np.ndarray, np.ndarray]:
    if lag >= 0:
        out = out[lag:]
    else:
        ref = ref[-lag:]
    n = min(len(ref), len(out))
    return ref[:n], out[:n]


def snr_db(ref: np.ndarray, out: np.ndarray) -> float:
    err = np.sum((ref - out) ** 2)
    return float("inf") if err == 0 else 10 * np.log10(np.sum(ref ** 2) / err)


def lowpass_fft(x: np.ndarray, cutoff: float) -> np.ndarray:
    X = np.fft.rfft(x)
    X[np.fft.rfftfreq(len(x), 1 / SR) > cutoff] = 0
    return np.fft.irfft(X, len(x))


def sweep_metrics(out: np.ndarray, src: Path) -> dict:
    x, sr = read_wav16(src)
    ref = signal.resample_poly(x, SR, sr)
    lag = best_lag(ref[: 30 * SR], out[: 30 * SR + 4000], 2000)
    r, o = aligned(ref, out, lag)
    # 0–7 kHz: chỉ phần quét trong dải đó (giây 0 tới lúc quét qua 7 kHz), lọc thêm để bỏ phần đuôi của quá độ.
    f0, f1, dur = SWEEP
    t7 = (7000 - f0) / (f1 - f0) * dur
    n7 = int(t7 * SR)
    snr = snr_db(lowpass_fft(r[:n7], 7000), lowpass_fft(o[:n7], 7000))
    att = {}
    for f in (10000.0, 15000.0):
        t = (f - f0) / (f1 - f0) * dur
        a, b = int((t - 0.5) * SR), int((t + 0.5) * SR)
        rms = np.sqrt(np.mean(out[a:b] ** 2))
        att[f] = 20 * np.log10(max(rms, 1e-12) / (SWEEP_AMP / np.sqrt(2)))
    return {"lag": lag, "snr": snr, "att10": att[10000.0], "att15": att[15000.0]}


def tones_metrics(out: np.ndarray) -> dict:
    n = np.arange(len(out))
    mid = slice(SR, len(out) - SR)
    cols = []
    for f in (1000.0, 6000.0):
        cols += [np.sin(2 * np.pi * f * n / SR), np.cos(2 * np.pi * f * n / SR)]
    A = np.stack(cols, axis=1)[mid]
    coef, *_ = np.linalg.lstsq(A, out[mid], rcond=None)
    amp1 = np.hypot(coef[0], coef[1])
    amp6 = np.hypot(coef[2], coef[3])
    resid = out[mid] - A @ coef
    return {"amp1_db": 20 * np.log10(amp1 / 0.2), "amp6_db": 20 * np.log10(amp6 / 0.2),
            "du_db": 20 * np.log10(max(np.sqrt(np.mean(resid ** 2)), 1e-12) / 0.2)}


def real_metrics(out: np.ndarray, src: Path, cache: Path) -> dict:
    if not cache.is_file():
        c.run_ffmpeg(["-i", str(src), "-vn", "-ac", "1", "-ar", str(SR), "-f", "f32le", str(cache)])
    ref = read_f32(cache)
    lag = best_lag(ref[: 30 * SR], out[: 30 * SR + 8000], 4000)
    r, o = aligned(ref, out, lag)
    gain = float(np.dot(r, o) / np.dot(o, o))
    dur = c.ffmpeg_duration(src) or 0.0
    return {"lag": lag, "snr": snr_db(r, o), "snr_gain": snr_db(r, gain * o), "gain": gain,
            "out_s": len(out) / SR, "ffmpeg_s": dur, "ffmpeg_pcm_s": len(ref) / SR}


def main() -> None:
    c.utf8_console()
    D = c.data_dir()
    ap = argparse.ArgumentParser()
    ap.add_argument("--phone-dir", default=str(D / "out" / "phone" / "decode"))
    args = ap.parse_args()
    pdir = Path(args.phone_dir)
    media = D / "media"

    print("| Resampler | SNR sweep 0–7 kHz (dB) | Suy giảm 10 kHz / 15 kHz (dB) | Biên độ 1 kHz / 6 kHz (dB) "
          "| SNR L1.m4a so với ffmpeg (dB, có chỉnh gain) | Trễ (mẫu) sweep / L1 |")
    print("| --- | --- | --- | --- | --- | --- |")
    durations = []
    for rs in ("sinc", "sonic"):
        cells = [rs]
        sw = pdir / f"sweep48k_{rs}.f32"
        to = pdir / f"tones48k_{rs}.f32"
        l1 = pdir / f"L1_{rs}.f32"
        s = sweep_metrics(read_f32(sw), media / "sweep48k.wav") if sw.is_file() else None
        t = tones_metrics(read_f32(to)) if to.is_file() else None
        r = real_metrics(read_f32(l1), media / "L1.m4a", D / "out" / "L1_ffmpeg16k.f32") if l1.is_file() else None
        cells.append(f"{s['snr']:.1f}" if s else "—")
        cells.append(f"{s['att10']:.1f} / {s['att15']:.1f}" if s else "—")
        cells.append(f"{t['amp1_db']:+.3f} / {t['amp6_db']:+.3f} (dư {t['du_db']:.0f})" if t else "—")
        cells.append(f"{r['snr']:.1f} ({r['snr_gain']:.1f}, gain {r['gain']:.4f})" if r else "—")
        cells.append(f"{s['lag'] if s else '—'} / {r['lag'] if r else '—'}")
        print("| " + " | ".join(cells) + " |")
        if r:
            durations.append((rs, r))
    for rs, r in durations:
        print(f"\nL1.m4a {rs}: PCM ra {r['out_s']:.3f} s; ffmpeg Duration {r['ffmpeg_s']:.3f} s; "
              f"ffmpeg giải mã {r['ffmpeg_pcm_s']:.3f} s; lệch {r['out_s'] - r['ffmpeg_s']:+.3f} s")


if __name__ == "__main__":
    main()
