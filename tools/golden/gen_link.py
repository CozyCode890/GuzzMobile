"""
Golden cho nối người nói giữa các phần (spikes/diarization/linking.py: piece_bounds, link_pieces, merge_to_max)
→ testdata/golden/speaker/link.json

Đây là thuật toán của mobile (desktop chạy pyannote nguyên bài, không chia phần), nên bản Python tham chiếu là
linking.py của spike Stage 1 (chạy bằng Python của desktop: chỉ cần numpy + scipy). Phần tổng hợp, seed cố định: nhiều
phần chồng lấn, phần cuối ngắn, phần không ai nói, một phần, nhãn không có embedding, luật max gộp người. Bản ghi thật:
tools/golden/real_speaker.py.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import _common  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "spikes" / "diarization"))
import linking  # noqa: E402

SEED = 5072
DIM = 8


def unit(v: np.ndarray) -> np.ndarray:
    return v / np.linalg.norm(v)


def make_case(rng: np.random.Generator, name: str, duration: float, piece: float, overlap: float,
              n_speakers: int, empty_pieces=(), no_emb_prob: float = 0.0) -> dict:
    """Bài tổng hợp: mỗi người một "giọng" (vector gốc); mỗi phần thấy giọng + nhiễu, nhãn cục bộ xáo trộn."""
    voices = [unit(rng.normal(size=DIM)) for _ in range(n_speakers)]
    # Lượt nói toàn bài (giây), không chồng lấn.
    turns, t = [], 0.0
    while t < duration:
        s = int(rng.integers(n_speakers)) if rng.random() > 0.5 else 0
        d = float(rng.uniform(1, 25))
        turns.append((t, min(duration, t + d), s))
        t += d + float(rng.uniform(0, 2))
    sr = 1000  # mẫu/giây cho piece_bounds, để số nguyên
    bounds = linking.piece_bounds(int(duration * sr), int(piece * sr), int(overlap * sr))
    pieces = []
    for k, (a, b) in enumerate(bounds):
        a_s, b_s = a / sr, b / sr
        local = list(range(n_speakers))
        rng.shuffle(local)
        pt = []
        if k not in empty_pieces:
            for ta, tb, s in turns:
                x, y = max(ta, a_s), min(tb, b_s)
                if y - x > 0.05:
                    # Mốc mỗi phần lệch chút ít, như hai lần tách giọng khác nhau trên vùng chồng lấn.
                    pt.append((round(x + float(rng.uniform(-0.2, 0.2)), 3) if x > a_s else x,
                               round(y, 3), int(local[s])))
            pt.sort()
        emb = {}
        for l in sorted({l for _, _, l in pt}):
            s = local.index(l)
            emb[l] = None if rng.random() < no_emb_prob else unit(voices[s] + rng.normal(scale=0.35, size=DIM))
        pieces.append({"a": a_s, "b": b_s, "turns": pt, "emb": emb})
    return {"name": name, "bounds": [list(x) for x in bounds], "duration_samples": int(duration * sr),
            "piece_samples": int(piece * sr), "overlap_samples": int(overlap * sr), "pieces": pieces}


def to_json(p: dict) -> dict:
    return {"a": p["a"], "b": p["b"], "turns": [[a, b, float(l)] for a, b, l in p["turns"]],
            "emb": {str(k): (None if v is None else [float(x) for x in v]) for k, v in p["emb"].items()}}


def main() -> None:
    rng = np.random.default_rng(SEED)
    cases = [
        make_case(rng, "ba_nguoi_nhieu_phan", 3600, 600, 60, 3),
        make_case(rng, "phan_cuoi_ngan", 1250, 600, 60, 2),
        make_case(rng, "mot_phan", 400, 600, 60, 2),
        make_case(rng, "phan_khong_ai_noi", 2400, 600, 60, 3, empty_pieces=(1,)),
        make_case(rng, "thieu_embedding", 3000, 600, 60, 4, no_emb_prob=0.3),
        make_case(rng, "nhieu_nguoi", 4000, 500, 30, 7),
        make_case(rng, "chong_lan_dai", 2000, 600, 200, 3),
    ]
    out = []
    inputs = {}
    for c in cases:
        inputs[c["name"]] = {
            "duration_samples": c["duration_samples"], "piece_samples": c["piece_samples"],
            "overlap_samples": c["overlap_samples"], "bounds": c["bounds"],
            "pieces": [to_json(p) for p in c["pieces"]],
        }
        for threshold in (0.3, 0.6, 1.1):
            segs, log, gemb = linking.link_pieces(c["pieces"], threshold)
            row = {
                "name": f"{c['name']}/t{threshold}",
                "input": c["name"],
                "threshold": threshold,
                "linked": [[a, b, g] for a, b, g in segs],
                "log": log,
                "max": {},
            }
            for m in (1, 2, 3, 6):
                row["max"][str(m)] = [[a, b, g] for a, b, g in linking.merge_to_max(segs, gemb, m)]
            out.append(row)
    excl = []
    for i in range(12):
        n = int(rng.integers(0, 40))
        segs = []
        for _ in range(n):
            a = round(float(rng.uniform(0, 120)), 3)
            segs.append((a, round(a + float(rng.uniform(0.01, 15)), 3), int(rng.integers(0, 4))))
        if i == 0:
            # Hai lượt dài bằng nhau phủ cùng khoảng: nhãn nhỏ hơn thắng.
            segs = [(0.0, 5.0, 2), (0.0, 5.0, 1), (4.0, 9.0, 0)]
        ex = linking.to_exclusive(segs)
        ov, sp = linking.overlap_seconds(segs)
        turns = [(a, b) for a, b, _ in ex]
        excl.append({"segs": [[a, b, float(l)] for a, b, l in segs], "exclusive": [[a, b, l] for a, b, l in ex],
                     "overlap": ov, "speech": sp,
                     "ranges": [list(r) for r in linking.embedding_ranges(turns, 16000, int(60 * 16000))]})
    _common.write_json(_common.output_dir("speaker") / "link.json",
                       {"inputs": inputs, "cases": out, "exclusive": excl}, os.path.basename(__file__))
    print(f"link.json: {len(out)} ca")


if __name__ == "__main__":
    main()
