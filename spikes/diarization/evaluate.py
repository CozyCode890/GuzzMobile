"""
Bước B2 của Stage 1: chấm kết quả sherpa-onnx so với pyannote community-1 của desktop (ref.json), tiêu chí D29.

Chạy bằng Python có pyannote của desktop, import thẳng speaker / config / audio_processing (chỉ đọc):
    D:\\Guzz\\runtime\\nguoi_noi\\python.exe -B -s spikes\\diarization\\evaluate.py <hyp.json>... [--group B3] [--remap]
    D:\\Guzz\\runtime\\nguoi_noi\\python.exe -B -s spikes\\diarization\\evaluate.py --self-test

Mã bài lấy từ tên tệp (<mã>.json hoặc <mã>__<…>.json) hoặc --code; tham chiếu là $GUZZ_SPIKE_DATA/<mã>/ref.json.

Mỗi cặp tính cả trước và sau speaker.process_turns(turns, config.Config()) (mặc định của config.default.txt), áp cho
cả hai bên. DER = pyannote.metrics DiarizationErrorRate(collar=0.5, skip_overlap=False), tách miss / false alarm /
confusion. Tham chiếu là kết quả máy, nên DER ở đây là "lệch so với desktop", không phải sai số tuyệt đối.

--remap: hyp chạy trên âm thanh gốc, đổi mốc về âm thanh sạch bằng TimeMap của danh_sach_doan.json, đúng như
transcribe.diarize_speakers.

Mỗi lần chấm thêm một dòng vào $GUZZ_SPIKE_DATA/out/pc/metrics.csv và in bảng markdown.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import os
import sys
import warnings
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as c  # noqa: E402

c.use_desktop_modules()
import audio_processing  # noqa: E402
import config  # noqa: E402
import speaker  # noqa: E402

from pyannote.core import Annotation, Segment, Timeline  # noqa: E402
from pyannote.metrics.diarization import DiarizationErrorRate  # noqa: E402

COLLAR = 0.5
# D29
MAX_DER = 0.15
MAX_MAIN_DIFF = 0.05
MAX_SPEAKER_DIFF = 1
MAX_EXTRA_SHARE = 0.05

CSV_FIELDS = ["thoi_gian", "nhom", "ma", "hyp", "seg", "emb", "shift", "threshold", "num_clusters", "piece_minutes",
              "remap", "der_tho", "der", "miss", "fa", "conf", "chinh_ref", "chinh_hyp", "lech_chinh", "nguoi_ref",
              "nguoi_hyp", "nguoi_them_5pct", "dat_d29", "truot", "giay_xu_ly", "ram_dinh_mb", "chong_lan"]


def to_annotation(turns: list[speaker.SpeakerTurn]) -> Annotation:
    ann = Annotation()
    for i, t in enumerate(turns):
        if t.end > t.start:
            ann[Segment(t.start, t.end), i] = t.speaker
    return ann


def speaker_shares(turns: list[speaker.SpeakerTurn]) -> dict[str, float]:
    total = defaultdict(float)
    for t in turns:
        total[t.speaker] += t.end - t.start
    s = sum(total.values())
    return {k: v / s for k, v in total.items()} if s > 0 else {}


def der_parts(ref: list, hyp: list, duration: float, collar: float = COLLAR) -> dict:
    metric = DiarizationErrorRate(collar=collar, skip_overlap=False)
    uem = Timeline([Segment(0, duration)])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        d = metric(to_annotation(ref), to_annotation(hyp), uem=uem, detailed=True)
    total = d["total"] or 1.0
    return {"der": d["diarization error rate"], "miss": d["missed detection"] / total,
            "fa": d["false alarm"] / total, "conf": d["confusion"] / total}


def judge(ref: list, hyp: list, duration: float, cfg) -> dict:
    raw = der_parts(ref, hyp, duration)
    ref_p, _ = speaker.process_turns(ref, cfg)
    hyp_p, _ = speaker.process_turns(hyp, cfg)
    after = der_parts(ref_p, hyp_p, duration)
    rs, hs = speaker_shares(ref_p), speaker_shares(hyp_p)
    main_ref = max(rs.values()) if rs else 0.0
    main_hyp = max(hs.values()) if hs else 0.0
    n_ref, n_hyp = len(rs), len(hs)
    extra = sum(1 for v in sorted(hs.values(), reverse=True)[1:] if v > MAX_EXTRA_SHARE)
    fails = []
    if after["der"] > MAX_DER:
        fails.append("DER")
    if abs(main_ref - main_hyp) > MAX_MAIN_DIFF:
        fails.append("người chính")
    if abs(n_ref - n_hyp) > MAX_SPEAKER_DIFF:
        fails.append("số người")
    if n_ref == 1 and extra > 0:
        fails.append("người thêm > 5%")
    return {"der_tho": raw["der"], **after, "chinh_ref": main_ref, "chinh_hyp": main_hyp,
            "lech_chinh": main_hyp - main_ref, "nguoi_ref": n_ref, "nguoi_hyp": n_hyp, "nguoi_them_5pct": extra,
            "dat_d29": not fails, "truot": ", ".join(fails)}


def code_of(path: Path, explicit: str | None) -> str:
    return explicit or path.stem.split("__")[0]


def load_hyp(path: Path, code: str, remap: bool) -> list[speaker.SpeakerTurn]:
    turns = speaker.load_speaker_turns(str(path))
    if remap:
        seg = c.read_json(c.data_dir() / code / "danh_sach_doan.json")
        tm = audio_processing.TimeMap(seg["ban_do_thoi_gian"], seg["thoi_luong_goc"])
        turns = speaker.remap_timestamps(turns, tm.to_clean)
    return turns


def evaluate_file(path: Path, args, cfg) -> dict:
    code = code_of(path, args.code)
    ref_path = Path(args.ref) if args.ref else c.data_dir() / code / "ref.json"
    ref = speaker.load_speaker_turns(str(ref_path))
    duration = float(c.read_json(ref_path)["thoi_luong_giay"])
    hyp_json = c.read_json(path)
    hyp = load_hyp(path, code, args.remap)
    r = judge(ref, hyp, duration, cfg)
    sh = hyp_json.get("sherpa") or {}
    return {"thoi_gian": dt.datetime.now().isoformat(timespec="seconds"), "nhom": args.group or "", "ma": code,
            "hyp": path.name, "seg": sh.get("seg"), "emb": sh.get("emb"), "shift": sh.get("window_shift_ratio"),
            "threshold": sh.get("threshold"), "num_clusters": sh.get("num_clusters"),
            "piece_minutes": sh.get("piece_minutes"), "remap": args.remap, **r,
            "giay_xu_ly": hyp_json.get("giay_xu_ly"), "ram_dinh_mb": hyp_json.get("ram_dinh_mb"),
            "chong_lan": hyp_json.get("ty_le_chong_lan")}


def pct(x) -> str:
    return f"{100 * x:.1f}"


def print_table(rows: list[dict]) -> None:
    print("| Mã | Tệp | DER (thô) | miss / FA / conf | Người chính ref → hyp | Số người ref / hyp | D29 | Thời gian (s) "
          "| RAM (MB) |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in rows:
        print(f"| {r['ma']} | {r['hyp']} | {pct(r['der'])} ({pct(r['der_tho'])}) | {pct(r['miss'])} / {pct(r['fa'])} / "
              f"{pct(r['conf'])} | {pct(r['chinh_ref'])} → {pct(r['chinh_hyp'])} | {r['nguoi_ref']} / {r['nguoi_hyp']} "
              f"| {'đạt' if r['dat_d29'] else 'trượt: ' + r['truot']} | {r['giay_xu_ly']} | {r['ram_dinh_mb']} |")
    if len(rows) > 1:
        n = len(rows)
        print(f"| TB | | {pct(sum(r['der'] for r in rows) / n)} ({pct(sum(r['der_tho'] for r in rows) / n)}) | | | | "
              f"{sum(r['dat_d29'] for r in rows)}/{n} đạt | {sum(r['giay_xu_ly'] or 0 for r in rows):.0f} | "
              f"{max(r['ram_dinh_mb'] or 0 for r in rows)} |")


def append_csv(rows: list[dict]) -> None:
    out = c.data_dir() / "out" / "pc" / "metrics.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    new = not out.is_file()
    with open(out, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if new:
            w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 5) if isinstance(v, float) else v) for k, v in r.items() if k in CSV_FIELDS})


def share_outside_collar(ref: list, main: str, collar: float) -> float:
    """Phần thời gian của những người không phải `main`, sau khi bỏ ±collar/2 quanh mọi ranh giới lượt của ref.
    Tự tính bằng số học khoảng, không qua pyannote, để kiểm chéo cách chấm."""
    half = collar / 2
    zones = sorted((b - half, b + half) for t in ref for b in (t.start, t.end))
    merged: list[list[float]] = []
    for a, b in zones:
        if merged and a <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    starts = [z[0] for z in merged]
    import bisect

    def outside(a: float, b: float) -> float:
        kept = b - a
        i = max(0, bisect.bisect_right(starts, a) - 1)
        while i < len(merged) and merged[i][0] < b:
            kept -= max(0.0, min(b, merged[i][1]) - max(a, merged[i][0]))
            i += 1
        return max(0.0, kept)

    total = other = 0.0
    for t in ref:
        x = outside(t.start, t.end)
        total += x
        if t.speaker != main:
            other += x
    return other / total if total > 0 else 0.0


def self_test(cfg) -> None:
    """Kiểm của B2: ref với chính nó và với bản đổi tên nhãn → DER 0; gán hết cho một người → confusion bằng phần của
    những người không phải người nói chính (sai số ≤ 0,5 điểm %). Với collar 0,5 s, phần đó tính sau khi bỏ vùng collar,
    vì ref có nhiều lượt ngắn mà collar cắt mất phần lớn."""
    ok = True
    for code in ("B1", "B2", "B3", "B4", "B5"):
        ref_path = c.data_dir() / code / "ref.json"
        ref = speaker.load_speaker_turns(str(ref_path))
        duration = float(c.read_json(ref_path)["thoi_luong_giay"])
        same = der_parts(ref, ref, duration)["der"]
        renamed = [speaker.SpeakerTurn(t.start, t.end, "X" + t.speaker[::-1]) for t in ref]
        ren = der_parts(ref, renamed, duration)["der"]
        main = speaker.find_main_speaker(ref)
        one = [speaker.SpeakerTurn(t.start, t.end, main) for t in ref]
        conf0 = der_parts(ref, one, duration, collar=0.0)["conf"]
        others0 = 1.0 - speaker_shares(ref)[main]
        conf = der_parts(ref, one, duration)["conf"]
        others = share_outside_collar(ref, main, COLLAR)
        good = (same < 1e-9 and ren < 1e-9 and abs(conf0 - others0) <= 0.005 and abs(conf - others) <= 0.005)
        ok &= good
        print(f"{code}: DER(ref, ref) = {same:.6f}; DER(ref, đổi tên) = {ren:.6f}; confusion(một người): "
              f"collar 0 → {pct(conf0)}% / phần người khác {pct(others0)}%; collar 0,5 → {pct(conf)}% / "
              f"{pct(others)}% → {'đạt' if good else 'SAI'}")
    print("self-test:", "đạt" if ok else "SAI")
    if not ok:
        sys.exit(1)


def main() -> None:
    c.utf8_console()
    ap = argparse.ArgumentParser(description="Chấm JSON sherpa-onnx so với pyannote của desktop (D29)")
    ap.add_argument("hyp", nargs="*")
    ap.add_argument("--code", help="Mã bài (mặc định lấy từ tên tệp)")
    ap.add_argument("--ref", help="Tham chiếu khác ref.json (vd JSON của PC khi chấm JSON điện thoại, D2)")
    ap.add_argument("--group", help="Tên nhóm ghi vào metrics.csv, vd B3")
    ap.add_argument("--remap", action="store_true", help="hyp chạy trên âm thanh gốc: đổi mốc về âm thanh sạch")
    ap.add_argument("--no-csv", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    cfg = config.Config()
    if args.self_test:
        self_test(cfg)
        return
    rows = [evaluate_file(Path(p), args, cfg) for p in args.hyp]
    if not args.no_csv:
        append_csv(rows)
    print_table(rows)


if __name__ == "__main__":
    main()
