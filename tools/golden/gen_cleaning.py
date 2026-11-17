"""
Golden cho các bước làm sạch đầy đủ của Stage 7 (C1–C5)  →  testdata/golden/cleaning/

Tín hiệu tổng hợp 10 giây ở 16 kHz (lecture.wav): "giọng" hài âm có âm tiết, giảng viên đi xa rồi lại gần, ồn trắng +
ồn hồng + tiếng ù 50 Hz, ba tiếng gõ bàn, một tiếng ho, hai khoảng lặng. Script ghi WAV rồi ĐỌC LẠI ra float32 như
read_audio, để Python và Kotlin nhận đúng một mảng.

Từng bước chạy chính hàm của desktop trên kết quả Python của bước trước (test Kotlin cũng vậy, nên mỗi bước được so
riêng), lưu kết quả float32 thô (`*.f32`, little-endian):
- bandpass.f32   = bandpass_filter(y, 80, 7000)                         (preset Giảng viên đi lại)
- transient.f32  = suppress_transients(bandpass, 15 dB) + mức ghim từng khối (cut_low, cut_all) và các vùng
- denoise.f32    = noisereduce như reduce_noise(tc 1,5 s, prop 0,8) nhưng chunk_size 60 000 / padding 15 000 để có
                   biên khối trong 10 giây (desktop dùng 600 000 / 30 000; chuỗi đủ bên dưới chạy đúng mặc định, một khối)
- level.f32      = level_loudness(denoise, 3 s) + đường gain
Chuỗi clean_audio đủ (C5) cho 3 bộ cài đặt: kết quả là PCM 16-bit (`_to_pcm16`, như sach.pcm), các khoảng giữ lại,
bản đồ thời gian và điểm cắt đoạn (tham số cắt đoạn thu nhỏ).
"""

from __future__ import annotations

import os
import sys
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402
from scipy.signal import butter, lfilter  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import audio_processing as ap  # noqa: E402
import chunker  # noqa: E402
import noisereduce as nr  # noqa: E402
from audio_io import _to_pcm16  # noqa: E402

SR = 16000
SECONDS = 10.0
NR_CHUNK, NR_PAD = 60000, 15000

CHAINS = {
    # preset Giảng viên đi lại = config.default.txt (D87)
    "moving_lecturer": dict(denoise=True, target_dbfs=-16.0, silence_trimming=True, min_silence_len=1000,
                            silence_thresh_offset=20, keep_silence=400, noise_sample_sec=1.5, bandpass_low=80,
                            bandpass_high=7000, prop_decrease=0.8, level_window_sec=3.0, transient_clamp_db=0.0),
    # preset Loa nhiều hướng: ghìm tiếng động hai lượt quanh khử ồn
    "speakers_around": dict(denoise=True, target_dbfs=-16.0, silence_trimming=True, min_silence_len=700,
                            silence_thresh_offset=16, keep_silence=300, noise_sample_sec=3.0, bandpass_low=120,
                            bandpass_high=8000, prop_decrease=0.85, level_window_sec=0.0, transient_clamp_db=15.0),
    # tắt khử ồn nhưng bật ghìm + cân bằng: nhánh denoise=False của clean_audio (ghìm một lượt, sau "khử ồn")
    "no_denoise": dict(denoise=False, target_dbfs=-16.0, silence_trimming=True, min_silence_len=700,
                       silence_thresh_offset=16, keep_silence=300, noise_sample_sec=2.0, bandpass_low=80,
                       bandpass_high=8000, prop_decrease=0.85, level_window_sec=2.0, transient_clamp_db=12.0),
}
SPLIT = dict(chunk_minutes=0.05, search_seconds=1.0, min_last_chunk_seconds=1.0)


def signal(rng: np.random.Generator) -> np.ndarray:
    n = int(SECONDS * SR)
    t = np.arange(n) / SR
    # Giọng: hài âm của f0 trượt 120–220 Hz, âm tiết ~4 Hz.
    f0 = 170 + 50 * np.sin(2 * np.pi * 0.3 * t)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    voice = sum((0.6 / k) * np.sin(k * phase + rng.uniform(0, 2 * np.pi)) for k in range(1, 20))
    syll = np.clip(np.sin(2 * np.pi * 4.1 * t + 0.7 * np.sin(2 * np.pi * 0.9 * t)), 0, None) ** 0.7
    fric = lfilter(*butter(2, [3000 / (SR / 2), 6000 / (SR / 2)], btype="band"), rng.standard_normal(n)) * 0.15
    voice = (voice * syll + fric * (syll < 0.2)) * 0.035
    # Đi xa (gain 0,25 lúc 5 s) rồi lại gần.
    distance = 1.0 - 0.75 * np.exp(-((t - 5.0) / 1.3) ** 2)
    voice *= distance
    for a, b in ((3.0, 4.2), (7.0, 7.8)):          # khoảng lặng: chỉ còn ồn nền
        voice[int(a * SR):int(b * SR)] = 0.0
    # Ồn nền: trắng + hồng (lọc bậc một) + ù 50 Hz.
    white = rng.standard_normal(n) * 0.002
    pink = lfilter([1.0], [1.0, -0.98], rng.standard_normal(n)) * 0.0008
    hum = 0.003 * np.sin(2 * np.pi * 50 * t)
    y = voice + white + pink + hum
    # Tiếng gõ bàn: 150 Hz tắt nhanh + tiếng tách; tiếng ho: nhiễu dải rộng 200 ms.
    for at in (1.3, 5.6, 8.9):
        k = int(at * SR)
        m = int(0.08 * SR)
        tt = np.arange(m) / SR
        y[k:k + m] += 0.55 * np.exp(-tt / 0.012) * np.sin(2 * np.pi * 150 * tt)
        y[k:k + 40] += rng.standard_normal(40) * 0.08
    k, m = int(6.4 * SR), int(0.2 * SR)
    y[k:k + m] += rng.standard_normal(m) * 0.2 * np.hanning(m)
    return y


def write_f32(path, y) -> None:
    np.asarray(y, dtype="<f4").tofile(str(path))


def chain(y: np.ndarray, params: dict) -> dict:
    """clean_audio đủ, đọc tệp bằng read_audio giả (trả đúng mảng y), rồi cắt đoạn như prepare_chunks."""
    logs = []
    with mock.patch.object(ap, "read_audio", return_value=y.copy()):
        clean, sr, tm = ap.clean_audio("lecture.wav", resample_hz=SR, progress_callback=logs.append, **params)
    pcm = _to_pcm16(clean)
    points = chunker.find_split_points(clean, SR, **SPLIT)
    return dict(params=params, time_map=tm.to_json(), orig_duration=round(tm.orig_duration, 3),
                samples=int(len(clean)), points=[int(p) for p in points], logs=logs, pcm=pcm)


def main() -> None:
    out = _common.output_dir("cleaning")
    rng = np.random.default_rng(20261117)
    y_raw = signal(rng)
    peak = float(np.max(np.abs(y_raw)))
    assert peak < 0.95, peak
    _common.write_wav16(out / "lecture.wav", y_raw, SR)
    y, sr = _common.read_wav16(out / "lecture.wav")

    band = ap.bandpass_filter(y.copy(), SR, low=80, high=7000)
    assert band.dtype == np.float32
    write_f32(out / "bandpass.f32", band)

    # suppress_transients + mức ghim từng khối: chép đúng lượt 1 của hàm (để lấy cut) rồi gọi hàm thật.
    block = int(SR * ap._TRANSIENT_BLOCK_MS / 1000)
    num_blocks = len(band) // block
    num_cells = num_blocks // ap._TRANSIENT_CELL
    sos = butter(4, min(ap._TRANSIENT_SPLIT_HZ, 0.4 * SR) / (0.5 * SR), btype="low", output="sos")
    step = max(1, ap._FILTER_BLOCK // block) * block
    power_low, power_high = np.zeros(num_blocks), np.zeros(num_blocks)
    for i in range(0, num_blocks * block, step):
        j = min(num_blocks * block, i + step)
        low_part = ap._low_band(band, i, j, sos).reshape(-1, block)
        high_part = band[i:j].reshape(-1, block) - low_part
        power_low[i // block:j // block] = np.einsum("ij,ij->i", low_part, low_part) / block
        power_high[i // block:j // block] = np.einsum("ij,ij->i", high_part, high_part) / block
    cut_low = ap._clamp_levels(power_low, num_cells, 15.0)
    cut_all = ap._clamp_levels(power_low * 10.0 ** (-cut_low / 10.0) + power_high, num_cells, 15.0)
    trans, regions = ap.suppress_transients(band.copy(), SR, threshold_db=15.0)
    assert regions, "tín hiệu thử phải có tiếng động bị ghìm"
    write_f32(out / "transient.f32", trans)

    den = nr.reduce_noise(y=trans, sr=SR, stationary=False, time_constant_s=max(0.2, 1.5), prop_decrease=0.8,
                          chunk_size=NR_CHUNK, padding=NR_PAD)
    den = np.ascontiguousarray(den, dtype=np.float32)
    write_f32(out / "denoise.f32", den)

    # level_loudness + đường gain (chép phần tính gain của hàm).
    lev = ap.level_loudness(den.copy(), SR, window_sec=3.0)
    write_f32(out / "level.f32", lev)

    chains = {}
    for name, params in CHAINS.items():
        r = chain(y, params)
        pcm = r.pop("pcm")
        (out / f"chain_{name}.pcm").write_bytes(pcm)
        chains[name] = r

    data = dict(
        sr=SR, wav="lecture.wav", samples=int(len(y)),
        bandpass=dict(low=80, high=7000, file="bandpass.f32"),
        transient=dict(threshold_db=15.0, file="transient.f32", power_low_sum=float(power_low.sum()),
                       cut_low=cut_low.tolist(), cut_all=cut_all.tolist(),
                       regions=[list(map(float, r)) for r in regions],
                       merged=[list(map(float, r)) for r in ap._merge_regions(regions, regions[:1])]),
        denoise=dict(time_constant_s=1.5, prop_decrease=0.8, chunk_size=NR_CHUNK, padding=NR_PAD, file="denoise.f32"),
        level=dict(window_sec=3.0, file="level.f32"),
        split=SPLIT,
        chains=chains,
    )
    _common.write_json(out / "cleaning.json", data, os.path.basename(__file__))
    sizes = sum(p.stat().st_size for p in out.iterdir())
    print(f"cleaning: {len(regions)} vùng ghìm, {sizes / 1e6:.1f} MB; "
          + ", ".join(f"{k}: {len(v['time_map'])} khoảng, {len(v['points']) - 1} đoạn" for k, v in chains.items()))


if __name__ == "__main__":
    main()
