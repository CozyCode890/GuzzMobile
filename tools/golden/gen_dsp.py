"""
Golden cho các hàm số học nền của Stage 7 (B1–B4)  →  testdata/golden/dsp/dsp.json

So bản Kotlin (`:core:audio` gói dsp) với chính numpy / scipy / noisereduce trong runtime của Guzz desktop:
- rfft / irfft của numpy (độ dài luỹ thừa 2);
- scipy.signal.butter(..., output="sos"): 4 preset + 20 bộ ngẫu nhiên + bậc 1–8;
- sosfilt (có zi, lọc theo khối như bandpass_filter), sosfiltfilt (gồm ca ngắn hơn padlen: scipy báo lỗi),
  filtfilt bậc một của noisereduce (get_time_smoothed_representation);
- stft / istft với đúng tham số noisereduce dùng (hann_periodic 1024, hop 256, padded=False), cả khối dài
  600 000 + 2 × 30 000 mẫu (lưu vài khung và vài đoạn đầu ra, đầu vào là công thức sin tất định tính lại được bên Kotlin);
- fftconvolve(mode="same") với nhân _smoothing_filter của noisereduce.

Không có tệp WAV: mảng ghi vào JSON dạng base64 của float64 little-endian (đọc lại đúng từng bit).
"""

from __future__ import annotations

import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402
import scipy  # noqa: E402
from scipy.signal import butter, fftconvolve, filtfilt, istft, sosfilt, sosfiltfilt, stft  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import noisereduce  # noqa: E402
from noisereduce.spectralgate.base import _smoothing_filter  # noqa: E402
from noisereduce.spectralgate.nonstationary import get_time_smoothed_representation  # noqa: E402

SR = 16000
NFFT = 1024
HOP = 256


def f64(a) -> str:
    """Mảng float64 dạng base64 (little-endian): JSON thụt dòng ghi mỗi số một dòng, base64 gọn hơn ~4 lần."""
    return base64.b64encode(np.ascontiguousarray(np.asarray(a, dtype="<f8").ravel()).tobytes()).decode("ascii")


def cx(a: np.ndarray) -> dict:
    a = np.asarray(a)
    return {"re": f64(np.real(a)), "im": f64(np.imag(a))}


def long_signal(n: int) -> np.ndarray:
    """Tín hiệu tất định, tính lại được bên Kotlin (LongSignal trong DspGoldenTest), float64."""
    i = np.arange(n, dtype=np.float64)
    return 0.3 * np.sin(0.01 * i) + 0.2 * np.sin(0.37 * i + 0.5 * np.sin(0.001 * i)) + 0.05 * np.sin(2.9 * i)


def fft_cases(rng) -> dict:
    forward, inverse = [], []
    for n in (2, 4, 8, 16, 64, 256, 1024, 4096):
        x = rng.standard_normal(n)
        forward.append({"n": n, "x": f64(x), "y": cx(np.fft.rfft(x))})
        spec = rng.standard_normal(n // 2 + 1) + 1j * rng.standard_normal(n // 2 + 1)
        # Phần ảo của bin 0 và bin n/2 khác 0: pocketfft bỏ qua, bản Kotlin cũng phải bỏ qua.
        inverse.append({"n": n, "x": cx(spec), "y": f64(np.fft.irfft(spec, n=n))})
    return {"forward": forward, "inverse": inverse}


def butter_cases(rng) -> list:
    cases = []
    nyq = 0.5 * SR
    for low, high in ((80, 8000), (80, 6000), (80, 7000), (120, 8000)):
        high = min(high, nyq - 1)                           # như bandpass_filter
        cases.append({"order": 5, "btype": "band", "wn": [low / nyq, high / nyq]})
    cases.append({"order": 4, "btype": "low", "wn": [min(1000.0, 0.4 * SR) / nyq]})   # suppress_transients
    for _ in range(20):
        order = int(rng.integers(4, 6))
        if rng.random() < 0.5:
            a, b = sorted(rng.uniform(0.005, 0.995, 2))
            cases.append({"order": order, "btype": "band", "wn": [float(a), float(b)]})
        else:
            cases.append({"order": order, "btype": "low", "wn": [float(rng.uniform(0.01, 0.99))]})
    for order in range(1, 9):
        cases.append({"order": order, "btype": "band", "wn": [0.1, 0.6]})
        cases.append({"order": order, "btype": "low", "wn": [0.3]})
    for c in cases:
        wn = c["wn"] if c["btype"] == "band" else c["wn"][0]
        c["sos"] = butter(c["order"], wn, btype=c["btype"], output="sos").tolist()
    return cases


def filter_cases(rng) -> dict:
    nyq = 0.5 * SR
    band = butter(5, [80 / nyq, 7000 / nyq], btype="band", output="sos")
    low = butter(4, 1000 / nyq, btype="low", output="sos")

    # sosfilt theo khối có zi, đúng như bandpass_filter (đầu vào float32, kết quả gán lại float32).
    x = (rng.standard_normal(3000) * 0.1).astype(np.float32)
    y = x.copy()
    block = 700
    zi = np.zeros((band.shape[0], 2))
    for i in range(0, len(y), block):
        seg, zi = sosfilt(band, y[i:i + block], zi=zi)
        y[i:i + block] = seg
    sosfilt_case = {"sos": band.tolist(), "block": block, "x": f64(x), "y": f64(y), "y64": f64(sosfilt(band, x))}

    filtfilt_cases = []
    for n in (16, 17, 50, 1000):
        sig = rng.standard_normal(n)
        filtfilt_cases.append({"x": f64(sig), "y": f64(sosfiltfilt(low, sig))})
    sin = np.sin(np.arange(400) * 0.05)
    filtfilt_cases.append({"x": f64(sin), "y": f64(sosfiltfilt(low, sin))})
    try:
        sosfiltfilt(low, rng.standard_normal(15))
        short_raises = False
    except ValueError:
        short_raises = True

    # filtfilt bậc một trên từng hàng (trục thời gian) như get_time_smoothed_representation.
    spec = np.abs(rng.standard_normal((5, 300)))
    smooth = get_time_smoothed_representation(spec, SR, HOP, time_constant_s=1.5)
    t_frames = 1.5 * SR / float(HOP)
    b = (np.sqrt(1 + 4 * t_frames ** 2) - 1) / (2 * t_frames ** 2)
    return {
        "sosfilt": sosfilt_case,
        "sosfiltfilt": {"sos": low.tolist(), "cases": filtfilt_cases, "short_15_raises": short_raises},
        "one_pole": {"b": float(b), "rows": 5, "cols": 300, "x": f64(spec), "y": f64(smooth),
                     "lfilter_zi": scipy.signal.lfilter_zi([b], [1, b - 1]).tolist(),
                     "filtfilt_check": f64(filtfilt([b], [1, b - 1], spec[0], padtype=None))},
    }


def stft_cases(rng) -> dict:
    short = []
    # Ngắn hơn nperseg thì scipy tự thu nperseg lại; noisereduce không bao giờ gặp (mỗi khối có ≥ 60 000 mẫu đệm).
    for n in (3000, 3003, 1024):
        x = rng.standard_normal(n)
        _, _, z = stft(x, nfft=NFFT, noverlap=NFFT - HOP, nperseg=NFFT, padded=False)
        mask = rng.uniform(0, 1, z.shape)
        _, back = istft(z * mask, nfft=NFFT, noverlap=NFFT - HOP, nperseg=NFFT)
        short.append({"x": f64(x), "frames": z.shape[1], "z": cx(z.T), "mask": f64(mask.T),
                      "back": f64(back)})

    # Khối thật của noisereduce: 600 000 + 2 × 30 000 mẫu, float64.
    n = 600000 + 2 * 30000
    x = long_signal(n)
    _, _, z = stft(x, nfft=NFFT, noverlap=NFFT - HOP, nperseg=NFFT, padded=False)
    assert z.dtype == np.complex128, z.dtype
    frames = z.shape[1]
    pick = [0, 1, 2, frames // 2, frames - 3, frames - 2, frames - 1]
    _, back = istft(z, nfft=NFFT, noverlap=NFFT - HOP, nperseg=NFFT)
    assert back.dtype == np.float64, back.dtype
    ranges = [[0, 2000], [n // 2, n // 2 + 2000], [len(back) - 2000, len(back)]]
    return {
        "short": short,
        "long": {"n": n, "frames": frames, "picked": pick, "z": [cx(z[:, t]) for t in pick],
                 "back_len": len(back), "back_ranges": ranges,
                 "back": [f64(back[a:b]) for a, b in ranges],
                 "dtype_stft": str(z.dtype), "dtype_istft": str(back.dtype)},
    }


def convolve_case(rng) -> dict:
    n_grad_freq = int(500 / (SR / (NFFT / 2)))
    n_grad_time = int(50 / ((HOP / SR) * 1000))
    k = _smoothing_filter(n_grad_freq, n_grad_time)
    a = rng.uniform(0, 1, (40, 30))
    return {"n_grad_freq": n_grad_freq, "n_grad_time": n_grad_time, "kernel_shape": list(k.shape),
            "kernel": f64(k), "a_shape": [40, 30], "a": f64(a),
            "y": f64(fftconvolve(a, k, mode="same"))}


def main() -> None:
    rng = np.random.default_rng(20261117)
    data = {
        "versions": {"scipy": scipy.__version__, "noisereduce": getattr(noisereduce, "__version__", "3.0.3")},
        "fft": fft_cases(rng),
        "butter": butter_cases(rng),
        "filters": filter_cases(rng),
        "stft": stft_cases(rng),
        "convolve": convolve_case(rng),
    }
    out = _common.output_dir("dsp")
    _common.write_json(out / "dsp.json", data, os.path.basename(__file__))
    size = (out / "dsp.json").stat().st_size
    print(f"dsp.json: {size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
