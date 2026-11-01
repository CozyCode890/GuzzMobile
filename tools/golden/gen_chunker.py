"""
Golden cho chunker.py: find_split_points  →  testdata/golden/chunker/

Tín hiệu tổng hợp có seed cố định, 1000 Hz (một khối 50 ms = 50 mẫu) và tham số nhỏ để WAV gọn. Script ghi WAV rồi
ĐỌC LẠI ra float32 mới gọi find_split_points, để bản Python và bản Kotlin nhận đúng một mảng đầu vào.

Python tính năng lượng khối bằng float32, Kotlin bằng double. Để hai bản chọn cùng một khối, mỗi cửa sổ tìm phải có
giá trị nhỏ nhất và nhì của năng lượng đã làm mượt cách nhau tương đối > MIN_MARGIN; không đủ thì script dừng và báo
đổi seed.
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402

_common.use_desktop_modules()
import chunker  # noqa: E402

SR = 1000
MIN_MARGIN = 1e-4


# ---------------------------------------------------------------- tín hiệu

def voice(rng: np.random.Generator, n: int, level: float = 0.1) -> np.ndarray:
    """Giả tiếng nói: nhiễu Gauss, to nhỏ thay đổi theo từng 100 ms như âm tiết."""
    env = np.repeat(0.3 + 0.7 * rng.random(n // 100 + 1), 100)[:n]
    return rng.standard_normal(n) * level * env


def hush(rng: np.random.Generator, n: int, level: float = 0.003) -> np.ndarray:
    """Chỗ lặng: nhiễu nền rất nhỏ (không để số 0 tuyệt đối, tránh các khối năng lượng bằng nhau)."""
    return rng.standard_normal(n) * level


def build(rng: np.random.Generator, n: int, silences: list[tuple[int, int, float]]) -> np.ndarray:
    """Tiếng nói dài n mẫu, chèn các chỗ lặng (bắt_đầu, dài, mức)."""
    y = voice(rng, n)
    for start, length, level in silences:
        y[start:start + length] = hush(rng, length, level)
    return y


# ---------------------------------------------------------------- biên an toàn

def window_margins(y: np.ndarray, sr: int, chunk_minutes: float, search_seconds: float,
                   min_last_chunk_seconds: float) -> list[float]:
    """Chạy lại vòng lặp của find_split_points, trả về biên tương đối (nhì - nhất) / nhất của từng cửa sổ tìm."""
    n = len(y)
    length = int(round(chunk_minutes * 60 * sr))
    min_last = int(max(0.0, min_last_chunk_seconds) * sr)
    search = int(max(0.0, search_seconds) * sr)
    if length <= 0 or n <= length + min_last:
        return []
    block = max(1, sr * chunker._BLOCK_MS // 1000)
    smoothed = chunker._moving_average(chunker._block_energy(y, block),
                                       max(1, chunker._SILENCE_WINDOW_MS // chunker._BLOCK_MS))
    margins = []
    points = [0]
    while n - points[-1] > length + min_last:
        prev = points[-1]
        target = prev + length
        a = max(prev + length // 2, target - search)
        b = min(target + search, n - min_last)
        ka, kb = a // block, min(len(smoothed), b // block)
        if search == 0 or ka >= kb:
            cut = target
        else:
            seg = np.sort(smoothed[ka:kb])
            if len(seg) >= 2:
                margins.append(float((seg[1] - seg[0]) / max(seg[0], 1e-30)))
            k = ka + int(np.argmin(smoothed[ka:kb]))
            cut = k * block + block // 2
        points.append(min(max(cut, prev + 1), n - 1))
    return margins


# ---------------------------------------------------------------- các ca

def cases() -> list[dict]:
    """Mỗi ca: tên, tệp WAV, tín hiệu (dựng một lần cho mỗi tệp), tham số."""
    rng = np.random.default_rng(20261031)
    multi = build(rng, 25_000, [(6_100, 600, 0.003), (11_900, 600, 0.002), (18_300, 600, 0.004)])
    merge = build(rng, 14_000, [(5_700, 600, 0.003)])
    outside = build(rng, 10_000, [(3_800, 600, 0.0005), (6_300, 600, 0.004), (7_700, 600, 0.0005)])
    noise = voice(rng, 20_000)
    short = voice(rng, 8_000)

    return [
        # (a) tệp không dài hơn length + min_last: một đoạn duy nhất, kể cả đúng bằng.
        dict(name="a_shorter_than_length_plus_min_last", wav="short.wav", y=short[:7_000],
             chunk_minutes=0.1, search_seconds=2.0, min_last_chunk_seconds=2.0),
        dict(name="a_exactly_length_plus_min_last", wav="exact.wav", y=short,
             chunk_minutes=0.1, search_seconds=2.0, min_last_chunk_seconds=2.0),
        # (b) nhiều đoạn, mỗi cửa sổ tìm có một chỗ lặng rõ.
        dict(name="b_silence_in_each_window", wav="multi.wav", y=multi,
             chunk_minutes=0.1, search_seconds=2.0, min_last_chunk_seconds=2.0),
        # (c) search_seconds = 0: cắt đúng mốc, bỏ qua chỗ lặng.
        dict(name="c_zero_search_cuts_at_target", wav="multi.wav", y=multi,
             chunk_minutes=0.1, search_seconds=0.0, min_last_chunk_seconds=2.0),
        # (d) phần còn lại ngắn hơn min_last_chunk_seconds: gộp vào đoạn trước.
        dict(name="d_short_last_chunk_merged", wav="merge.wav", y=merge,
             chunk_minutes=0.1, search_seconds=2.0, min_last_chunk_seconds=3.0),
        # (e) chỗ lặng sâu hơn nằm ngoài cửa sổ tìm: không được chọn.
        dict(name="e_deeper_silence_outside_window_ignored", wav="outside.wav", y=outside,
             chunk_minutes=0.1, search_seconds=1.0, min_last_chunk_seconds=2.0),
        # (f) ồn liên tục, không có chỗ lặng: vẫn cắt ở khối nhỏ nhất trong cửa sổ.
        dict(name="f_continuous_noise", wav="noise.wav", y=noise,
             chunk_minutes=0.1, search_seconds=2.0, min_last_chunk_seconds=2.0),
    ]


def main() -> None:
    out = _common.output_dir("chunker")
    results = []
    written: dict[str, np.ndarray] = {}
    for case in cases():
        wav = case["wav"]
        if wav not in written:
            _common.write_wav16(out / wav, case["y"], SR)
            written[wav] = case["y"]
        y, sr = _common.read_wav16(out / wav)
        params = dict(chunk_minutes=case["chunk_minutes"], search_seconds=case["search_seconds"],
                      min_last_chunk_seconds=case["min_last_chunk_seconds"])
        expected = [int(p) for p in chunker.find_split_points(y, sr, **params)]

        margins = window_margins(y, sr, **params)
        smallest = min(margins) if margins else None
        if smallest is not None and smallest <= MIN_MARGIN:
            raise SystemExit(f"{case['name']}: biên an toàn {smallest:.2e} <= {MIN_MARGIN:g}, đổi seed.")
        shown = f"{smallest:.3e}" if smallest is not None else "không có cửa sổ tìm"
        print(f"{case['name']}: {expected}  biên nhỏ nhất = {shown}")

        results.append(dict(name=case["name"], wav=wav, sr=sr, expected=expected, **params))

    _common.write_json(out / "find_split_points.json", {"cases": results}, os.path.basename(__file__))
    total = sum(p.stat().st_size for p in out.iterdir())
    print(f"{out}: {total} byte")


if __name__ == "__main__":
    main()
