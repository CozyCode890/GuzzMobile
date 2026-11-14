"""
Chạy các phép đo B3–B8 của Stage 1 trên B1–B5, rồi chấm bằng evaluate.py. Chạy bằng venv của bước A4:

    $D\\venv\\Scripts\\python.exe spikes\\diarization\\sweep.py B3
    ... sweep.py B4 --shift 0.25
    ... sweep.py B5 --shift 0.25 --embs <emb1> <emb2>
    ... sweep.py B6 --shift 0.25 --embs <emb> --threshold 0.6
    ... sweep.py B7 --shift 0.25 --embs <emb> --threshold 0.6
    ... sweep.py B8 --shift 0.25 --embs <emb> --threshold 0.6
    ... sweep.py S5B1 --embs campplus-zhen --jobs 3 --threads 4      (Stage 5 B1: ngưỡng, chia phần 20 phút)

Mỗi lần chạy sherpa-onnx là một process riêng (run_sherpa.py), để RAM đỉnh đo đúng từng lần. JSON đã có thì bỏ qua, nên
bị ngắt thì chạy lại lệnh cũ là làm tiếp. Kết quả: $D/out/pc/<bước>/<cấu hình>/<mã>.json; bảng chấm của từng cấu hình ở
$D/out/pc/<bước>/<cấu hình>.md; mọi dòng chấm cộng dồn vào $D/out/pc/metrics.csv.

--jobs N chạy N process cùng lúc (mỗi process --threads luồng). Thời gian đo khi chạy song song không so được với khi
chạy một mình; B3 và B6 so thời gian nên để --jobs 1.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as c  # noqa: E402

HERE = Path(__file__).resolve().parent
CODES = ("B1", "B2", "B3", "B4", "B5")
EMBEDDINGS = (
    "3dspeaker_speech_campplus_sv_zh-cn_16k-common",
    "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced",
    "3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common",
    "nemo_en_titanet_small",
    "wespeaker_en_voxceleb_resnet34_LM",
)
SHORT = {
    "3dspeaker_speech_campplus_sv_zh-cn_16k-common": "campplus-zh",
    "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced": "campplus-zhen",
    "3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common": "eres2net-base",
    "nemo_en_titanet_small": "titanet-small",
    "wespeaker_en_voxceleb_resnet34_LM": "wespeaker-r34",
}
THRESHOLDS = (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)
S5_THRESHOLDS = (1.0, 1.2, 0.9, 1.1, 1.3, 0.8, 1.4)
EVAL_PYTHON = Path(os.environ.get("GUZZ_DESKTOP_DIR") or c.DEFAULT_DESKTOP_DIR) / "runtime" / "nguoi_noi" / "python.exe"


def emb_path(name: str) -> Path:
    full = next((e for e in EMBEDDINGS if e == name or SHORT[e] == name), None)
    if full is None:
        raise SystemExit(f"Embedding không biết: {name}")
    return c.data_dir() / "models" / "emb" / full / f"{full}.onnx"


def emb_short(name: str) -> str:
    return SHORT.get(name, name)


def num_speakers(code: str) -> int:
    return int(c.read_json(c.data_dir() / code / "meta.json")["so_nguoi_desktop"])


def plan(args) -> list[tuple[str, list[dict]]]:
    """[(tên cấu hình, [việc cho từng bài])]. Mỗi việc: {code, audio, out, argv}."""
    D = c.data_dir()
    seg = D / "models" / "seg" / "model.onnx"
    seg8 = D / "models" / "seg" / "model.int8.onnx"
    groups: list[tuple[str, list[dict]]] = []

    def job(code: str, cfg_name: str, emb: str, extra: list[str], audio: str = "sach.flac", seg_model=seg) -> dict:
        out = D / "out" / "pc" / args.step / cfg_name / f"{code}.json"
        argv = [str(D / code / audio), "--seg", str(seg_model), "--emb", str(emb_path(emb)), "--threads",
                str(args.threads), "--out", str(out), *extra]
        return {"code": code, "out": out, "argv": argv}

    if args.step == "B3":
        for shift in (0.1, 0.25, 0.5):
            name = f"campplus-zh_shift{shift}"
            groups.append((name, [job(k, name, "campplus-zh", ["--shift", str(shift), "--num-clusters",
                                                                  str(num_speakers(k))]) for k in CODES]))
    elif args.step == "B4":
        for e in EMBEDDINGS:
            name = f"{SHORT[e]}_shift{args.shift}"
            groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--num-clusters",
                                                    str(num_speakers(k))]) for k in CODES]))
    elif args.step == "B5":
        for e in args.embs:
            for t in args.thresholds or THRESHOLDS:
                name = f"{emb_short(e)}_shift{args.shift}_t{t}"
                groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--threshold", str(t)])
                                      for k in CODES]))
    elif args.step == "B6":
        e = args.embs[0]
        name = f"{emb_short(e)}_shift{args.shift}_t{args.threshold}_int8"
        groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--threshold", str(args.threshold)],
                                  seg_model=seg8) for k in CODES]))
    elif args.step == "B7":
        e = args.embs[0]
        name = f"{emb_short(e)}_shift{args.shift}_t{args.threshold}_goc"
        groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--threshold", str(args.threshold)],
                                  audio="goc16k.wav") for k in CODES]))
    elif args.step == "B8":
        e = args.embs[0]
        for p in (30, 60):
            name = f"{emb_short(e)}_shift{args.shift}_t{args.threshold}_p{p}"
            groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--threshold", str(args.threshold),
                                                    "--piece-minutes", str(p), "--overlap-seconds", "60"])
                                  for k in CODES]))
    elif args.step == "S5B1":
        # Stage 5 B1: chạy giống trên máy (chia phần P = 20 phút, O = 60 s), một ngưỡng cho cả phân cụm trong phần lẫn
        # nối người nói. Ghi thêm .pieces.json để B2 (luật min–max) và golden C3 dùng lại.
        for e in args.embs:
            for t in args.thresholds or S5_THRESHOLDS:
                name = f"{emb_short(e)}_shift{args.shift}_t{t}_p20"
                groups.append((name, [job(k, name, e, ["--shift", str(args.shift), "--threshold", str(t),
                                                       "--piece-minutes", "20", "--overlap-seconds", "60",
                                                       "--pieces-out"]) for k in CODES]))
    else:
        raise SystemExit(f"Bước không biết: {args.step}")
    return groups


def run_job(j: dict) -> None:
    if j["out"].is_file():
        print(f"đã có: {j['out'].parent.name}/{j['out'].name}", flush=True)
        return
    cmd = [sys.executable, str(HERE / "run_sherpa.py"), *j["argv"]]
    log = j["out"].with_suffix(".log")
    j["out"].parent.mkdir(parents=True, exist_ok=True)
    with open(log, "w", encoding="utf-8") as f:
        r = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
    tail = log.read_text(encoding="utf-8", errors="replace").strip().splitlines()[-1:]
    print(f"{'xong' if r.returncode == 0 else 'LỖI'} {j['out'].parent.name}/{j['code']}: {tail[0] if tail else ''}",
          flush=True)


def evaluate(step: str, name: str, jobs: list[dict], remap: bool) -> None:
    files = [str(j["out"]) for j in jobs if j["out"].is_file()]
    if not files:
        return
    md = c.data_dir() / "out" / "pc" / step / f"{name}.md"
    if md.is_file() and md.stat().st_mtime > max(Path(f).stat().st_mtime for f in files) and len(files) == len(jobs):
        print(f"đã chấm: {md.name}")
        return
    cmd = [str(EVAL_PYTHON), "-B", "-s", str(HERE / "evaluate.py"), "--group", f"{step}/{name}", *files]
    if remap:
        cmd.append("--remap")
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    if r.returncode != 0:
        print(f"LỖI chấm {name}: {r.stderr[-800:]}")
        return
    md.write_text(f"### {step} {name}\n\n{r.stdout}", encoding="utf-8")
    print(f"\n### {step} {name}\n{r.stdout}", flush=True)


def main() -> None:
    c.utf8_console()
    ap = argparse.ArgumentParser(description="Chạy B3–B8 trên B1–B5 rồi chấm")
    ap.add_argument("step", choices=["B3", "B4", "B5", "B6", "B7", "B8", "S5B1"])
    ap.add_argument("--shift", type=float, default=0.1)
    ap.add_argument("--embs", nargs="*", default=[])
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--thresholds", nargs="*", type=float)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--jobs", type=int, default=1)
    ap.add_argument("--only", nargs="*", help="Chỉ chạy các bài này")
    args = ap.parse_args()

    for name, jobs in plan(args):
        if args.only:
            jobs = [j for j in jobs if j["code"] in args.only]
        with ThreadPoolExecutor(max_workers=args.jobs) as ex:
            list(ex.map(run_job, jobs))
        evaluate(args.step, name, jobs, remap=args.step == "B7")


if __name__ == "__main__":
    main()
