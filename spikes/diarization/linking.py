"""
Nối người nói giữa các phần (Stage 1 B8, Stage 5 C3) và luật max của D72. Chỉ cần numpy + scipy, để chạy được bằng cả
venv sherpa-onnx (run_sherpa.py, relink.py) lẫn Python của desktop (tools/golden/gen_link.py). Bản Kotlin:
:core:speaker PieceLinker.

Hằng số dùng chung với run_sherpa.py.
"""

from __future__ import annotations

import numpy as np

# B8: cặp nhãn trùng nhau ít hơn ngần này giây trong vùng chồng lấn thì không coi là cùng người.
MIN_LINK_OVERLAP_SEC = 5.0


def overlap_seconds(segs: list[tuple[float, float, int]]) -> tuple[float, float]:
    """(thời gian có ≥ 2 người cùng nói, thời gian có ít nhất một người nói)."""
    events = sorted([(a, 1) for a, _, _ in segs] + [(b, -1) for _, b, _ in segs])
    active, prev, speech, overlap = 0, None, 0.0, 0.0
    for t, d in events:
        if prev is not None and active > 0:
            speech += t - prev
            if active > 1:
                overlap += t - prev
        active += d
        prev = t
    return overlap, speech


def to_exclusive(segs: list[tuple[float, float, int]]) -> list[tuple[float, float, int]]:
    """Bỏ chồng lấn: mỗi khoảng sơ cấp thuộc về đoạn dài nhất đang phủ nó; rồi nối các khoảng liền nhau cùng người."""
    bounds = sorted({t for a, b, _ in segs for t in (a, b)})
    by_start = sorted(segs, key=lambda s: s[0])
    out: list[list] = []
    active: list[tuple[float, float, int]] = []
    i = 0
    for lo, hi in zip(bounds, bounds[1:]):
        while i < len(by_start) and by_start[i][0] <= lo:
            active.append(by_start[i])
            i += 1
        active = [s for s in active if s[1] > lo]
        cover = [s for s in active if s[0] <= lo and s[1] >= hi]
        if not cover:
            continue
        spk = max(cover, key=lambda s: (s[1] - s[0], -s[2]))[2]
        if out and out[-1][2] == spk and abs(out[-1][1] - lo) < 1e-9:
            out[-1][1] = hi
        else:
            out.append([lo, hi, spk])
    return [(a, b, s) for a, b, s in out]


def embedding_ranges(turns: list[tuple[float, float]], sample_rate: int, size: int) -> list[tuple[int, int]]:
    """Phần chọn mẫu của run_sherpa.mean_embedding: tối đa 30 s, lượt dài trước; trả về [(từ, tới)] đã kẹp vào mảng."""
    picked, total = [], 0.0
    for a, b in sorted(turns, key=lambda t: t[1] - t[0], reverse=True):
        if b - a < 1.0 and picked:
            break
        take = min(b - a, 30.0 - total)
        lo = min(max(int(a * sample_rate), 0), size)
        hi = min(max(int((a + take) * sample_rate), lo), size)
        picked.append((lo, hi))
        total += take
        if total >= 30.0:
            break
    return picked


def piece_bounds(total: int, piece: int, overlap: int) -> list[tuple[int, int]]:
    """Các phần [a, b) dài `piece` mẫu, phần sau bắt đầu trước khi phần trước hết `overlap` mẫu."""
    if total <= piece:
        return [(0, total)]
    step = piece - overlap
    bounds, a = [], 0
    while True:
        b = min(a + piece, total)
        bounds.append((a, b))
        if b >= total:
            return bounds
        a += step


def link_pieces(pieces: list[dict], threshold: float):
    """
    pieces[k] = {a, b (giây, toàn bài), turns: [(start, end, nhãn cục bộ)] (giây toàn bài), emb: {nhãn: vector}}.
    Trả về (lượt toàn bài với nhãn chung, nhật ký nối từng phần, {nhãn chung: [embedding của từng phần]}).
    """
    from scipy.optimize import linear_sum_assignment

    global_emb: dict[int, list[np.ndarray]] = {}
    mapped: list[list[tuple[float, float, int]]] = []
    log: list[dict] = []
    next_id = 0
    for k, p in enumerate(pieces):
        labels = sorted({s for _, _, s in p["turns"]})
        mapping: dict[int, int] = {}
        how: dict[int, str] = {}
        if k > 0:
            prev = pieces[k - 1]
            lo, hi = p["a"], prev["b"]
            prev_labels = sorted({s for _, _, s in mapped[k - 1]})
            m = np.zeros((len(prev_labels), len(labels)))
            for a1, b1, g in mapped[k - 1]:
                if b1 <= lo or a1 >= hi:
                    continue
                for a2, b2, l in p["turns"]:
                    ov = min(b1, b2, hi) - max(a1, a2, lo)
                    if ov > 0:
                        m[prev_labels.index(g), labels.index(l)] += ov
            if m.size:
                rows, cols = linear_sum_assignment(-m)
                for r, col in zip(rows, cols):
                    if m[r, col] >= MIN_LINK_OVERLAP_SEC:
                        mapping[labels[col]] = prev_labels[r]
                        how[labels[col]] = f"chồng lấn {m[r, col]:.1f}s"
        for l in labels:
            if l in mapping:
                continue
            e = p["emb"].get(l)
            best, best_d = None, None
            if e is not None:
                used = set(mapping.values())
                for g, es in global_emb.items():
                    if g in used:
                        continue
                    ge = np.mean(es, axis=0)
                    ge /= np.linalg.norm(ge)
                    d = 1.0 - float(np.dot(e, ge))
                    if best_d is None or d < best_d:
                        best, best_d = g, d
            if best is not None and best_d <= threshold:
                mapping[l] = best
                how[l] = f"embedding d={best_d:.3f}"
            else:
                mapping[l] = next_id
                how[l] = "người mới" + (f" (gần nhất d={best_d:.3f})" if best_d is not None else "")
                next_id += 1
        for l, g in mapping.items():
            if p["emb"].get(l) is not None:
                global_emb.setdefault(g, []).append(p["emb"][l])
        mapped.append([(a, b, mapping[s]) for a, b, s in p["turns"]])
        log.append({"phan": k, "bat_dau": round(p["a"], 3), "ket_thuc": round(p["b"], 3),
                    "noi": {str(l): {"vao": mapping[l], "cach": how[l]} for l in labels}})

    # Vùng chồng lấn: phần trước tới giữa vùng, phần sau từ giữa vùng.
    out: list[tuple[float, float, int]] = []
    for k, turns in enumerate(mapped):
        lo = (pieces[k]["a"] + pieces[k - 1]["b"]) / 2 if k > 0 else float("-inf")
        hi = (pieces[k + 1]["a"] + pieces[k]["b"]) / 2 if k + 1 < len(mapped) else float("inf")
        for a, b, g in turns:
            a2, b2 = max(a, lo), min(b, hi)
            if b2 - a2 > 1e-3:
                out.append((a2, b2, g))
    out.sort()
    merged: list[list] = []
    for a, b, g in out:
        if merged and merged[-1][2] == g and a - merged[-1][1] < 1e-3:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b, g])
    return [(a, b, g) for a, b, g in merged], log, global_emb


def merge_adjacent(segs: list[tuple[float, float, int]]) -> list[tuple[float, float, int]]:
    merged: list[list] = []
    for a, b, g in segs:
        if merged and merged[-1][2] == g and a - merged[-1][1] < 1e-3:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b, g])
    return [(a, b, g) for a, b, g in merged]


def merge_to_max(segs: list[tuple[float, float, int]], global_emb: dict, max_speakers: int):
    """
    Luật max của D72 sau khi nối: còn nhiều hơn max người thì gộp người nói ít thời gian nhất (hoà: người xuất hiện
    trước) vào người gần nhất theo cosine của embedding trung bình (chuẩn hoá L2); không có embedding thì vào người nói
    lâu nhất. Lặp tới khi còn max người.
    """
    emb = {g: list(v) for g, v in global_emb.items()}
    while True:
        total: dict[int, float] = {}
        for a, b, g in segs:
            total[g] = total.get(g, 0.0) + (b - a)
        if max_speakers <= 0 or len(total) <= max_speakers:
            return segs
        victim = min(total, key=total.get)
        others = [g for g in total if g != victim]
        target = None
        if emb.get(victim):
            ve = np.mean(emb[victim], axis=0)
            ve /= np.linalg.norm(ve)
            best_d = None
            for g in others:
                if not emb.get(g):
                    continue
                ge = np.mean(emb[g], axis=0)
                ge /= np.linalg.norm(ge)
                d = 1.0 - float(np.dot(ve, ge))
                if best_d is None or d < best_d:
                    target, best_d = g, d
        if target is None:
            target = max(others, key=lambda g: total[g])
        emb[target] = emb.get(target, []) + emb.pop(victim, [])
        segs = merge_adjacent([(a, b, target if g == victim else g) for a, b, g in segs])
