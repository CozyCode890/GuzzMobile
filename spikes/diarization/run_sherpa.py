"""
Bước B1 / B8 của Stage 1: tách người nói bằng sherpa-onnx 2.0.1 (bản Python) trên PC.

Chạy bằng venv của bước A4:
    $D\\venv\\Scripts\\python.exe spikes\\diarization\\run_sherpa.py <wav|flac|f32> --seg <model.onnx> --emb <emb.onnx>
        [--threads 8] [--shift 0.1] [--threshold 0.5 | --num-clusters N] --out <ra.json>
        [--piece-minutes 30 --overlap-seconds 60]

Đầu vào 16 kHz mono; .f32 là f32le thô. JSON ra cùng định dạng với diarization_worker.py của desktop (model, thiet_bi,
file_wav, thoi_luong_giay, giay_xu_ly, so_nguoi, doan) để speaker.load_speaker_turns đọc được, thêm `sherpa` (cấu hình đã
chạy), `ram_dinh_mb`, `ty_le_chong_lan`. `--out` đã có thì bỏ qua (thêm --force để chạy lại).

`doan` không chồng lấn, như bản "exclusive" của pyannote mà desktop dùng: chỗ hai người cùng nói thuộc về lượt dài hơn.

Chia phần (D34, B8): cắt bài thành các phần P phút, hai phần liền nhau chồng lấn O giây; tách từng phần rồi nối người nói:
  1. trong vùng chồng lấn, ghép nhãn của hai phần theo thời gian trùng nhau (linear_sum_assignment), chỉ nhận cặp ≥ 5 s;
  2. nhãn chưa ghép: so cosine embedding trung bình (≤ 30 s tiếng nói) với từng người đã có; khoảng cách cosine
     ≤ ngưỡng phân cụm thì gộp, không thì thêm người mới;
  3. trong vùng chồng lấn, lấy lượt của phần trước tới giữa vùng, của phần sau từ giữa vùng trở đi.
Bản Kotlin ở app spike (D5) port đúng thuật toán này.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as c  # noqa: E402

import numpy as np  # noqa: E402
import psutil  # noqa: E402
import sherpa_onnx  # noqa: E402

# Mặc định của Kotlin API sherpa-onnx (bản Python để min_duration_on = 0,3); đặt rõ để PC và điện thoại chạy cùng cấu hình.
MIN_DURATION_ON = 0.2
MIN_DURATION_OFF = 0.5
# B8: cặp nhãn trùng nhau ít hơn ngần này giây trong vùng chồng lấn thì không coi là cùng người.
MIN_LINK_OVERLAP_SEC = 5.0
# B8: lấy tối đa ngần này giây tiếng nói của một người để tính embedding trung bình.
EMBEDDING_MAX_SEC = 30.0
EMBEDDING_MIN_TURN_SEC = 1.0


# ---------------------------------------------------------------- đọc âm thanh

def audio_length(path: Path) -> int:
    if path.suffix.lower() == ".f32":
        return path.stat().st_size // 4
    import soundfile as sf
    info = sf.info(str(path))
    if info.samplerate != c.SAMPLE_RATE or info.channels != 1:
        raise SystemExit(f"{path.name}: cần 16 kHz mono, đang là {info.samplerate} Hz × {info.channels} kênh")
    return info.frames


def read_audio(path: Path, start: int = 0, stop: int | None = None) -> np.ndarray:
    """Đọc mẫu [start, stop) dạng float32. Chỉ đọc phần cần, để chạy chia phần không giữ cả bài trong RAM."""
    if path.suffix.lower() == ".f32":
        mm = np.memmap(path, dtype="<f4", mode="r")
        return np.array(mm[start:stop], dtype=np.float32)
    import soundfile as sf
    data, sr = sf.read(str(path), start=start, stop=stop, dtype="float32", always_2d=False)
    if sr != c.SAMPLE_RATE or data.ndim != 1:
        raise SystemExit(f"{path.name}: cần 16 kHz mono")
    return data


# ---------------------------------------------------------------- sherpa-onnx

def build_config(args) -> sherpa_onnx.OfflineSpeakerDiarizationConfig:
    return sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=args.seg, window_shift_ratio=args.shift),
            num_threads=args.threads,
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=args.emb, num_threads=args.threads),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=args.num_clusters if args.num_clusters else -1,
            threshold=args.threshold),
        min_duration_on=MIN_DURATION_ON,
        min_duration_off=MIN_DURATION_OFF,
    )


def diarize(sd: sherpa_onnx.OfflineSpeakerDiarization, samples: np.ndarray, label: str) -> list[tuple[float, float, int]]:
    last = [0.0]

    def progress(done: int, total: int) -> int:
        now = time.monotonic()
        if done == total or now - last[0] > 10:
            last[0] = now
            print(f"  {label}: {done}/{total} khối", file=sys.stderr, flush=True)
        return 0

    result = sd.process(samples, callback=progress).sort_by_start_time()
    return [(r.start, r.end, int(r.speaker)) for r in result]


# ---------------------------------------------------------------- chồng lấn

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


# ---------------------------------------------------------------- chia phần (B8)

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


def mean_embedding(extractor, audio: np.ndarray, turns: list[tuple[float, float]]) -> np.ndarray | None:
    """Embedding của tối đa 30 s tiếng nói (lượt dài trước), chuẩn hoá L2. `turns` tính bằng giây trong `audio`."""
    picked, total = [], 0.0
    for a, b in sorted(turns, key=lambda t: t[1] - t[0], reverse=True):
        if b - a < EMBEDDING_MIN_TURN_SEC and picked:
            break
        take = min(b - a, EMBEDDING_MAX_SEC - total)
        picked.append(audio[int(a * c.SAMPLE_RATE):int((a + take) * c.SAMPLE_RATE)])
        total += take
        if total >= EMBEDDING_MAX_SEC:
            break
    if not picked:
        return None
    stream = extractor.create_stream()
    stream.accept_waveform(c.SAMPLE_RATE, np.concatenate(picked))
    stream.input_finished()
    if not extractor.is_ready(stream):
        return None
    e = np.asarray(extractor.compute(stream), dtype=np.float64)
    n = np.linalg.norm(e)
    return e / n if n > 0 else None


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


def write_pieces(path: Path, pieces: list[dict]) -> None:
    c.write_json(path, [
        {"a": p["a"], "b": p["b"], "turns": [[round(s, 3), round(e, 3), l] for s, e, l in p["turns"]],
         "emb": {str(l): (None if v is None else [float(x) for x in v]) for l, v in p["emb"].items()}}
        for p in pieces])


def load_pieces_cache(path: Path) -> list[dict]:
    out = []
    for p in c.read_json(path):
        out.append({"a": p["a"], "b": p["b"], "turns": [(s, e, int(l)) for s, e, l in p["turns"]],
                    "emb": {int(k): (None if v is None else np.asarray(v, dtype=np.float64))
                            for k, v in p["emb"].items()}})
    return out


# ---------------------------------------------------------------- main

def main() -> None:
    c.utf8_console()
    ap = argparse.ArgumentParser(description="Tách người nói bằng sherpa-onnx, ra JSON như diarization_worker.py")
    ap.add_argument("audio")
    ap.add_argument("--seg", required=True)
    ap.add_argument("--emb", required=True)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--shift", type=float, default=0.1, help="window_shift_ratio")
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--num-clusters", type=int, default=0, help="> 0: biết trước số người (bỏ qua --threshold)")
    ap.add_argument("--piece-minutes", type=float, default=0, help="> 0: chia phần (B8)")
    ap.add_argument("--overlap-seconds", type=float, default=60)
    ap.add_argument("--out", required=True)
    ap.add_argument("--pieces-out", action="store_true",
                    help="Chia phần: ghi thêm <out>.pieces.json (lượt + embedding từng phần) cho Stage 5 B2 / C3")
    ap.add_argument("--pieces-in", help="Chia phần: lấy phần đã tách (ngưỡng) từ .pieces.json, chỉ chạy lại phần cần "
                                        "phân cụm lại theo luật min–max")
    ap.add_argument("--min-speakers", type=int, default=0, help="Luật min–max của D72 (0 = tắt)")
    ap.add_argument("--max-speakers", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out = Path(args.out)
    if out.is_file() and not args.force:
        print(f"đã có: {out}")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    audio_path = Path(args.audio)
    total = audio_length(audio_path)

    config = build_config(args)
    if not config.validate():
        raise SystemExit("Cấu hình sherpa-onnx không hợp lệ (kiểm đường dẫn model)")
    sd = sherpa_onnx.OfflineSpeakerDiarization(config)

    started = time.monotonic()
    log = None
    rule_log: list[str] = []
    if args.piece_minutes > 0:
        if args.num_clusters:
            raise SystemExit("Chia phần chỉ dùng ngưỡng (--threshold), không dùng --num-clusters")
        extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=args.emb, num_threads=args.threads))
        bounds = piece_bounds(total, int(args.piece_minutes * 60 * c.SAMPLE_RATE),
                              int(args.overlap_seconds * c.SAMPLE_RATE))
        cache = load_pieces_cache(Path(args.pieces_in)) if args.pieces_in else None
        sd_fixed: dict[int, sherpa_onnx.OfflineSpeakerDiarization] = {}

        def sd_for(n: int):
            # Luật min–max (D72): phân cụm lại với số cụm cố định. Cấu hình khác num_clusters giữ nguyên.
            if n <= 0:
                return sd
            if n not in sd_fixed:
                cfg = build_config(args)
                cfg.clustering.num_clusters = n
                sd_fixed[n] = sherpa_onnx.OfflineSpeakerDiarization(cfg)
            return sd_fixed[n]

        ov_total, speech_total = 0.0, 0.0

        def run_piece(k: int, n: int) -> dict:
            nonlocal ov_total, speech_total
            a, b = bounds[k]
            if n <= 0 and cache is not None and k < len(cache):
                return cache[k]
            samples = read_audio(audio_path, a, b)
            raw = diarize(sd_for(n), samples, f"phần {k + 1}/{len(bounds)}" + (f" (số cụm {n})" if n > 0 else ""))
            ov, sp = overlap_seconds(raw)
            ov_total, speech_total = ov_total + ov, speech_total + sp
            excl = to_exclusive(raw)
            emb = {}
            for l in sorted({s for _, _, s in excl}):
                emb[l] = mean_embedding(extractor, samples, [(s, e) for s, e, sp in excl if sp == l])
            off = a / c.SAMPLE_RATE
            return {"a": off, "b": b / c.SAMPLE_RATE, "turns": [(s + off, e + off, l) for s, e, l in excl], "emb": emb}

        pieces = [run_piece(k, 0) for k in range(len(bounds))]
        if args.pieces_out:
            write_pieces(out.with_suffix(".pieces.json"), pieces)
        if args.max_speakers > 0:
            for k, p in enumerate(pieces):
                n = len({l for _, _, l in p["turns"]})
                if n > args.max_speakers:
                    rule_log.append(f"phần {k}: {n} cụm > max {args.max_speakers}, phân cụm lại")
                    pieces[k] = run_piece(k, args.max_speakers)
        segs, log, gemb = link_pieces(pieces, args.threshold)
        if args.min_speakers > 0 and segs and len({g for _, _, g in segs}) < args.min_speakers:
            rule_log.append(f"{len({g for _, _, g in segs})} người < min {args.min_speakers}, phân cụm lại mọi phần")
            pieces = [run_piece(k, args.min_speakers) for k in range(len(bounds))]
            segs, log, gemb = link_pieces(pieces, args.threshold)
        if args.max_speakers > 0:
            before = len({g for _, _, g in segs})
            segs = merge_to_max(segs, gemb, args.max_speakers)
            if before > args.max_speakers:
                rule_log.append(f"{before} người > max {args.max_speakers}, gộp người ít thời gian nhất")
        if rule_log:
            print("luật min–max: " + "; ".join(rule_log))
    else:
        raw = diarize(sd, read_audio(audio_path), audio_path.name)
        ov_total, speech_total = overlap_seconds(raw)
        segs = to_exclusive(raw)
    ratio = ov_total / speech_total if speech_total > 0 else 0.0
    elapsed = time.monotonic() - started

    # Đánh số lại theo thứ tự xuất hiện, như nhãn SPEAKER_xx của pyannote.
    order: dict[int, int] = {}
    for _, _, s in segs:
        order.setdefault(s, len(order))
    doan = [{"bat_dau": round(a, 3), "ket_thuc": round(b, 3), "nguoi_noi": f"SPEAKER_{order[s]:02d}"}
            for a, b, s in segs]
    data = {
        "model": f"sherpa-onnx {sherpa_onnx.__version__}: {Path(args.seg).parent.name}/{Path(args.seg).name} + "
                 f"{Path(args.emb).stem}",
        "thiet_bi": "cpu",
        "file_wav": str(audio_path),
        "thoi_luong_giay": round(total / c.SAMPLE_RATE, 2),
        "giay_xu_ly": round(elapsed, 1),
        "so_nguoi": len(order),
        "sherpa": {
            "phien_ban": sherpa_onnx.__version__,
            "seg": Path(args.seg).name,
            "emb": Path(args.emb).stem,
            "threads": args.threads,
            "window_shift_ratio": args.shift,
            "num_clusters": args.num_clusters or -1,
            "threshold": args.threshold,
            "min_duration_on": MIN_DURATION_ON,
            "min_duration_off": MIN_DURATION_OFF,
            "piece_minutes": args.piece_minutes or None,
            "overlap_seconds": args.overlap_seconds if args.piece_minutes else None,
            "min_speakers": args.min_speakers or None,
            "max_speakers": args.max_speakers or None,
        },
        "ram_dinh_mb": round(psutil.Process().memory_info().peak_wset / 2**20),
        "ty_le_chong_lan": round(ratio, 4),
        "doan": doan,
    }
    if log is not None:
        data["noi_phan"] = log
    if rule_log:
        data["luat_min_max"] = rule_log
    c.write_json(out, data)
    print(f"xong {audio_path.name}: {len(doan)} lượt, {len(order)} người, {elapsed:.0f} s, "
          f"RAM đỉnh {data['ram_dinh_mb']} MB, chồng lấn {ratio:.1%} → {out}")


if __name__ == "__main__":
    main()
