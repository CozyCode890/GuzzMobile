"""
Golden cho audio_processing.py: measure_dbfs, boost_and_normalize, find_speech_segments, trim_silence
→  testdata/golden/audio_processing/

Tín hiệu tổng hợp có seed cố định, 2 kHz (1 ms = 2 mẫu) để WAV gọn; riêng một ca 16 kHz ngắn để kiểm tổng từng mili giây
khi mỗi ô có 16 mẫu (np.add.reduceat = phần tử đầu + pairwise_sum). Script ghi WAV rồi ĐỌC LẠI ra float32 mới gọi hàm
của desktop, để bản Python và bản Kotlin nhận đúng một mảng đầu vào.

Bản Kotlin cộng bình phương bằng double, desktop bằng np.dot float32 (BLAS): dBFS lệch cỡ 1e-7 tương đối, đủ để hệ số
khuếch đại float32 lệch 1–2 ulp (đã gặp ngay ở ca đầu). Vì vậy:
- JSON ghi hệ số của desktop; test Kotlin so hệ số của mình với nó theo dung sai, còn các bước sau (tìm khoảng có tiếng,
  cắt lặng) chạy trên tín hiệu khuếch đại bằng ĐÚNG hệ số của desktop, để so khớp tuyệt đối;
- ngưỡng im lặng theo biên độ (10^(dB/20) × 32768) phải cách số nguyên gần nhất > 0,001 với cả dBFS của desktop lẫn
  dBFS tính bằng tổng chính xác (math.fsum), vì so sánh là floor(rms) <= ngưỡng: chỉ đổi kết quả khi ngưỡng vượt qua một
  số nguyên. Không đủ thì script dừng và báo đổi seed.
"""

from __future__ import annotations

import hashlib
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import audio_processing  # noqa: E402

SR = 2000
MIN_FRACTION_MARGIN = 1e-3


# ---------------------------------------------------------------- tín hiệu

def voice(rng: np.random.Generator, n: int, level: float, sr: int) -> np.ndarray:
    """Giả tiếng nói: nhiễu Gauss, to nhỏ thay đổi theo từng 100 ms như âm tiết."""
    step = sr // 10
    env = np.repeat(0.3 + 0.7 * rng.random(n // step + 1), step)[:n]
    return rng.standard_normal(n) * level * env


def hush(rng: np.random.Generator, n: int, level: float = 0.0005) -> np.ndarray:
    """Chỗ lặng: nhiễu nền rất nhỏ (không để số 0 tuyệt đối)."""
    return rng.standard_normal(n) * level


def build(rng: np.random.Generator, seconds: float, silences: list[tuple[float, float]], level: float = 0.1,
          sr: int = SR) -> np.ndarray:
    """Tiếng nói dài `seconds` giây, chèn các chỗ lặng (bắt đầu, dài) tính bằng giây."""
    n = int(seconds * sr)
    y = voice(rng, n, level, sr)
    for start, length in silences:
        a, b = int(start * sr), int((start + length) * sr)
        y[a:b] = hush(rng, b - a)
    return y


# ---------------------------------------------------------------- biên an toàn

def exact_dbfs(y: np.ndarray) -> float:
    if y.size == 0:
        return float("-inf")
    total = math.fsum(float(v) * float(v) for v in y.astype(np.float64))
    rms = (total / y.size) ** 0.5
    return 20.0 * math.log10(rms) if rms > 0 else float("-inf")


def gains(y: np.ndarray, target: float) -> tuple[float | None, float | None]:
    """Hệ số float32 của desktop và hệ số tính từ tổng chính xác (gần với bản Kotlin)."""
    d = audio_processing.measure_dbfs(y)
    if d == float("-inf"):
        return None, None
    gain = np.float32(10.0 ** ((target - d) / 20.0))
    gain_exact = np.float32(10.0 ** ((target - exact_dbfs(y)) / 20.0))
    return float(gain), float(gain_exact)


def check_threshold(name: str, y: np.ndarray, offset: float) -> float:
    """Ngưỡng dB như trim_silence (dBFS của tín hiệu - offset); kiểm biên an toàn của ngưỡng theo biên độ."""
    thr_db = audio_processing.measure_dbfs(y) - offset
    for d in (thr_db, exact_dbfs(y) - offset):
        if d == float("-inf"):
            continue
        amp = 10.0 ** (d / 20.0) * 32768.0
        frac = amp - math.floor(amp)
        if min(frac, 1 - frac) <= MIN_FRACTION_MARGIN:
            raise SystemExit(f"{name}: ngưỡng {amp:.6f} sát số nguyên, đổi seed.")
    return thr_db


def f32_hash(y: np.ndarray) -> str:
    return hashlib.sha256(np.asarray(y, dtype="<f4").tobytes()).hexdigest()


# ---------------------------------------------------------------- các ca

def cases(rng: np.random.Generator) -> list[dict]:
    lecture = build(rng, 20.0, [(0.0, 1.0), (4.0, 1.5), (8.0, 0.5), (12.3, 0.8), (13.9, 0.8), (18.0, 2.0)])
    loud = build(rng, 6.0, [(2.0, 1.2)], level=0.6)
    quiet = hush(rng, 6 * SR)
    steady = voice(rng, 8 * SR, 0.1, SR)
    short = build(rng, 0.6, [])
    odd = build(rng, 10.0005, [(3.0, 1.1), (7.0, 0.9)])
    zeros = np.zeros(3 * SR)
    sixteen = build(rng, 3.0, [(1.0, 0.9)], sr=16000)

    common = dict(target_dbfs=-16.0)
    return [
        # Bài giảng thu nhỏ: lặng ở đầu, cuối, lặng dài, lặng ngắn hơn min_silence_len, hai chỗ lặng sát nhau mà
        # keep_silence làm dính lại. Chạy với mặc định của hàm và mặc định của config.default.txt.
        dict(name="bai_giang_mac_dinh_ham", wav="lecture.wav", y=lecture, offset=16, min_silence=700, keep=300,
             **common),
        dict(name="bai_giang_mac_dinh_config", wav="lecture.wav", y=lecture, offset=20, min_silence=1000, keep=400,
             **common),
        dict(name="keep_silence_dai_gop_doan", wav="lecture.wav", y=lecture, offset=16, min_silence=700, keep=900,
             **common),
        # Khuếch đại tới -3 dBFS làm tín hiệu to bị kẹp.
        dict(name="bi_kep_khi_khuech_dai", wav="loud.wav", y=loud, offset=16, min_silence=700, keep=300,
             target_dbfs=-3.0),
        # Toàn nhiễu nền nhỏ đều nhau: ngưỡng tính theo dBFS của chính nó nên không chỗ nào thấp hơn ngưỡng → giữ nguyên.
        dict(name="toan_lang", wav="quiet.wav", y=quiet, offset=16, min_silence=700, keep=300, **common),
        # Nói liên tục: không có chỗ lặng nào → giữ nguyên.
        dict(name="khong_co_cho_lang", wav="steady.wav", y=steady, offset=16, min_silence=700, keep=300, **common),
        # Ngắn hơn cửa sổ min_silence_len.
        dict(name="ngan_hon_cua_so", wav="short.wav", y=short, offset=16, min_silence=700, keep=300, **common),
        # Số mẫu lẻ: không chia hết cho 1 ms.
        dict(name="so_mau_le", wav="odd.wav", y=odd, offset=16, min_silence=700, keep=300, **common),
        # Toàn số 0: dBFS = -inf, không khuếch đại.
        dict(name="toan_so_khong", wav="zeros.wav", y=zeros, offset=16, min_silence=700, keep=300, **common),
        # 16 kHz: mỗi ô 1 ms có 16 mẫu.
        dict(name="tan_so_16k", wav="sixteen.wav", y=sixteen, offset=16, min_silence=700, keep=300, sr=16000,
             with_sums=True, **common),
    ]


def main() -> None:
    rng = np.random.default_rng(20261110)
    out = _common.output_dir("audio_processing")
    results = []
    written: set[str] = set()
    for case in cases(rng):
        sr = case.get("sr", SR)
        wav = case["wav"]
        if wav not in written:
            _common.write_wav16(out / wav, case["y"], sr)
            written.add(wav)
        y, sr_read = _common.read_wav16(out / wav)
        assert sr_read == sr

        name = case["name"]
        target = case["target_dbfs"]
        dbfs = audio_processing.measure_dbfs(y)
        gain, gain_exact = gains(y, target)
        boosted = audio_processing.boost_and_normalize(y.copy(), target_dBFS=target)
        clipped = int(np.count_nonzero(np.abs(boosted) >= 1.0))

        thr_db = check_threshold(name, boosted, case["offset"])
        segments = audio_processing.find_speech_segments(boosted, sr, case["min_silence"], thr_db)
        _, kept = audio_processing.trim_silence(boosted, sr, silence_thresh_offset=case["offset"],
                                                min_silence_len=case["min_silence"], keep_silence=case["keep"])
        tm = audio_processing.TimeMap([(a / sr, b / sr) for a, b in kept], len(boosted) / sr)

        result = dict(
            name=name, wav=wav, sr=sr, target_dbfs=target,
            silence_thresh_offset=case["offset"], min_silence_len=case["min_silence"], keep_silence=case["keep"],
            dbfs=dbfs if dbfs != float("-inf") else None,
            gain=gain,
            gain_exact=gain_exact,
            boosted_sha256=f32_hash(boosted),
            threshold_db=thr_db if thr_db != float("-inf") else None,
            speech_segments=[[int(a), int(b)] for a, b in segments],
            kept=[[int(a), int(b)] for a, b in kept],
            time_map=tm.to_json(),
        )
        if case.get("with_sums"):
            n_ms = int(len(boosted) / sr * 1000)
            ts = (np.arange(n_ms + 1, dtype=np.int64) * sr) // 1000
            ts[-1] = min(ts[-1], len(boosted))
            sums = audio_processing._sum_squares_between(boosted, ts)
            result["first_ms_sums"] = [float(v) for v in sums[:300]]
        results.append(result)
        print(f"{name}: dBFS {dbfs:.3f}, hệ số {gain}, kẹp {clipped} mẫu, {len(segments)} khoảng có tiếng, "
              f"giữ {len(kept)} khoảng")

    _common.write_json(out / "audio_processing.json", {"cases": results}, os.path.basename(__file__))
    total = sum(p.stat().st_size for p in out.iterdir())
    print(f"{out}: {total} byte")


if __name__ == "__main__":
    main()
