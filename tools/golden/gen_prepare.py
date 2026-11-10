"""
Golden cho cả chuỗi làm sạch tối giản + cắt đoạn của Stage 2  →  testdata/golden/prepare/

Chạy đúng các hàm của desktop theo thứ tự của clean_audio / prepare_chunks, bỏ các bước Stage 7 (D7):
boost_and_normalize → trim_silence → TimeMap → find_split_points → write_chunks (WAV, cùng cách lượng tử hoá _to_pcm16
như FLAC). Ghi điểm cắt, danh sách đoạn, bản đồ thời gian và SHA-256 của mẫu int16 từng đoạn; các tệp đoạn không commit.

Tín hiệu tổng hợp 1 kHz (1 ms = 1 mẫu, khối 50 ms = 50 mẫu) dài 90 giây, tham số cắt đoạn thu nhỏ để có nhiều đoạn.
Biên an toàn (xem gen_audio_processing.py và gen_chunker.py): hệ số khuếch đại float32 của desktop phải trùng hệ số tính
từ tổng chính xác; ngưỡng im lặng theo biên độ cách số nguyên > 0,001; mỗi cửa sổ tìm điểm cắt có năng lượng nhỏ nhất và
nhì cách nhau tương đối > 1e-4. Script tự thử seed tiếp theo cho tới khi đạt.
"""

from __future__ import annotations

import hashlib
import math
import os
import sys
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402
import gen_audio_processing as gap  # noqa: E402
import gen_chunker  # noqa: E402

_common.use_desktop_modules()
import audio_processing  # noqa: E402
import chunker  # noqa: E402

SR = 1000
SETTINGS = dict(target_dbfs=-16.0, trim_silence=True, min_silence_len=700, silence_thresh_offset=16,
                keep_silence=300, chunk_minutes=0.25, search_seconds=3.0, min_last_chunk_seconds=4.0)


def signal(seed: int) -> np.ndarray:
    """90 giây "bài giảng": nói xen lặng dài, lặng ngắn, lặng đầu và cuối."""
    rng = np.random.default_rng(seed)
    silences = [(0.0, 1.5), (6.0, 2.0), (11.0, 0.4), (14.5, 1.0), (23.0, 3.0), (31.0, 0.9), (38.0, 1.2),
                (47.0, 2.5), (55.0, 0.8), (63.0, 1.6), (71.0, 1.0), (80.0, 2.2), (88.0, 2.0)]
    return gap.build(rng, 90.0, silences, level=0.1, sr=SR)


def pcm_sha(path: Path) -> tuple[str, int]:
    with wave.open(str(path), "rb") as w:
        frames = w.readframes(w.getnframes())
        return hashlib.sha256(frames).hexdigest(), w.getnframes()


def run(y: np.ndarray, name: str) -> dict:
    s = SETTINGS
    gain, gain_exact = gap.gains(y, s["target_dbfs"])
    if gain != gain_exact:
        raise ValueError(f"hệ số không ổn định {gain} / {gain_exact}")
    boosted = audio_processing.boost_and_normalize(y.copy(), target_dBFS=s["target_dbfs"])
    gap.check_threshold(name, boosted, s["silence_thresh_offset"])
    clean, kept = audio_processing.trim_silence(boosted, SR, silence_thresh_offset=s["silence_thresh_offset"],
                                                min_silence_len=s["min_silence_len"], keep_silence=s["keep_silence"])
    tm = audio_processing.TimeMap([(a / SR, b / SR) for a, b in kept], len(boosted) / SR)

    params = dict(chunk_minutes=s["chunk_minutes"], search_seconds=s["search_seconds"],
                  min_last_chunk_seconds=s["min_last_chunk_seconds"])
    margins = gen_chunker.window_margins(clean, SR, **params)
    if not margins or min(margins) <= gen_chunker.MIN_MARGIN:
        raise ValueError(f"biên an toàn của chunker {min(margins) if margins else None}")
    points = chunker.find_split_points(clean, SR, **params)

    with tempfile.TemporaryDirectory() as d:
        chunks = chunker.write_chunks(clean, SR, points, d, "wav")
        hashes = []
        for c in chunks:
            sha, frames = pcm_sha(Path(d) / c["file"])
            hashes.append(dict(sha256=sha, samples=frames))
    return dict(points=[int(p) for p in points], kept=[[int(a), int(b)] for a, b in kept],
                time_map=tm.to_json(), orig_duration=round(tm.orig_duration, 3),
                chunks=[dict(file=c["file"].replace(".wav", ".flac"), start=c["bat_dau_giay"], end=c["ket_thuc_giay"])
                        for c in chunks],
                chunk_pcm=hashes)


def main() -> None:
    out = _common.output_dir("prepare")
    for seed in range(20261110, 20261110 + 200):
        y_raw = signal(seed)
        _common.write_wav16(out / "lecture.wav", y_raw, SR)
        y, sr = _common.read_wav16(out / "lecture.wav")
        try:
            result = run(y, "lecture")
        except (ValueError, SystemExit) as e:
            print(f"seed {seed}: {e}, thử seed sau")
            continue
        break
    else:
        raise SystemExit("không tìm được seed đạt biên an toàn")

    data = dict(seed=seed, wav="lecture.wav", sr=SR, settings=SETTINGS, **result)
    _common.write_json(out / "prepare.json", data, os.path.basename(__file__))
    print(f"seed {seed}: {len(result['kept'])} khoảng giữ lại, {len(result['chunks'])} đoạn, điểm cắt {result['points']}")


if __name__ == "__main__":
    main()
