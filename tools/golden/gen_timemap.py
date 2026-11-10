"""
Golden cho audio_processing.py: TimeMap  →  testdata/golden/timemap/timemap.json

Mỗi ca là một bản đồ (các đoạn giữ lại, thời lượng gốc) cùng một loạt mốc thử: đầu, cuối, giữa đoạn, trong khoảng đã
cắt, ngoài cùng hai phía. Ghi kết quả to_original / to_clean, clean_duration, orig_duration và to_json để test Kotlin
(TimeMapGoldenTest) so khớp từng bit.
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

_common.use_desktop_modules()
from audio_processing import TimeMap  # noqa: E402


def probes(segments: list[tuple[float, float]], duration: float) -> list[float]:
    """Mốc thử cho cả hai chiều: mọi mép đoạn, giữa đoạn, giữa hai đoạn, và ra ngoài hai phía."""
    points = {-1.0, 0.0, duration, duration + 5.0}
    for i, (a, b) in enumerate(segments):
        points.update({a, b, (a + b) / 2, a + 1e-6, b - 1e-6})
        if i + 1 < len(segments):
            points.add((b + segments[i + 1][0]) / 2)
    # Mốc trên trục đã cắt cũng phải thử: nằm trong [0, clean_duration].
    clean = sum(b - a for a, b in segments)
    points.update({clean / 3, clean / 2, clean, clean * 0.999})
    return sorted(points)


def cases() -> list[dict]:
    return [
        dict(name="rong", segments=[], orig_duration=None),
        dict(name="khong_cat", segments=[(0.0, 125.5)], orig_duration=None),
        dict(name="mot_doan_giua", segments=[(3.25, 40.0)], orig_duration=60.0),
        dict(name="nhieu_doan", segments=[(0.0, 1.7), (2.9, 10.05), (12.0, 12.4), (30.123, 59.999)],
             orig_duration=61.0),
        # Đoạn rỗng / ngược bị bỏ ngay từ hàm tạo.
        dict(name="co_doan_rong", segments=[(1.0, 1.0), (2.0, 5.0), (7.0, 6.0), (8.0, 9.5)], orig_duration=None),
        # Mốc lẻ kiểu chia cho tần số lấy mẫu, để to_json phải làm tròn thật.
        dict(name="moc_le", segments=[(a / 16000, b / 16000) for a, b in
                                      [(0, 33_337), (52_001, 160_123), (170_000, 1_000_003)]],
             orig_duration=1_000_003 / 16000),
    ]


def main() -> None:
    out = _common.output_dir("timemap")
    results = []
    for case in cases():
        tm = TimeMap(case["segments"], case["orig_duration"])
        duration = tm.orig_duration
        pts = probes(tm.segments, duration)
        results.append(dict(
            name=case["name"],
            segments=[list(s) for s in case["segments"]],
            orig_duration=case["orig_duration"],
            expected_orig_duration=tm.orig_duration,
            expected_clean_duration=tm.clean_duration,
            expected_json=tm.to_json(),
            probes=pts,
            to_original=[tm.to_original(t) for t in pts],
            to_clean=[tm.to_clean(t) for t in pts],
        ))
        print(f"{case['name']}: {len(tm.segments)} đoạn, {len(pts)} mốc")
    _common.write_json(out / "timemap.json", {"cases": results}, os.path.basename(__file__))


if __name__ == "__main__":
    main()
