"""
Stage 5 (B1, B2): nối lại người nói giữa các phần từ `.pieces.json` đã có (sweep.py S5B1), không chạy lại sherpa-onnx.
Thử ngưỡng nối khác ngưỡng phân cụm trong phần, và luật min–max (D72) phần không cần phân cụm lại. Chạy bằng venv A4:

    $D\\venv\\Scripts\\python.exe spikes\\diarization\\relink.py --src campplus-zhen_shift0.1_t1.1_p20 --links 1.1 1.2 1.3
    ... relink.py --src <cấu hình> --links 1.2 --max-speakers 6

Kết quả: $D/out/pc/S5B1R/<cấu hình>_L<ngưỡng nối>[_max<N>]/<mã>.json (cùng định dạng run_sherpa.py) và bảng chấm .md
cạnh đó (evaluate.py bằng Python có pyannote của desktop).
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as c  # noqa: E402

import numpy as np  # noqa: E402

from run_sherpa import link_pieces, merge_to_max  # noqa: E402

HERE = Path(__file__).resolve().parent
CODES = ("B1", "B2", "B3", "B4", "B5")
EVAL_PYTHON = Path(os.environ.get("GUZZ_DESKTOP_DIR") or c.DEFAULT_DESKTOP_DIR) / "runtime" / "nguoi_noi" / "python.exe"


def load_pieces(path: Path) -> list[dict]:
    out = []
    for p in c.read_json(path):
        out.append({"a": p["a"], "b": p["b"], "turns": [(s, e, int(l)) for s, e, l in p["turns"]],
                    "emb": {int(k): (None if v is None else np.asarray(v, dtype=np.float64)) for k, v in p["emb"].items()}})
    return out


def main() -> None:
    c.utf8_console()
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Tên cấu hình trong out/pc/S5B1")
    ap.add_argument("--links", nargs="+", type=float, required=True)
    ap.add_argument("--max-speakers", type=int, default=0)
    args = ap.parse_args()
    D = c.data_dir()
    src = D / "out" / "pc" / "S5B1" / args.src
    for t in args.links:
        name = f"{args.src}_L{t}" + (f"_max{args.max_speakers}" if args.max_speakers else "")
        out_dir = D / "out" / "pc" / "S5B1R" / name
        out_dir.mkdir(parents=True, exist_ok=True)
        files = []
        for code in CODES:
            pf = src / f"{code}.pieces.json"
            if not pf.is_file():
                continue
            base = c.read_json(src / f"{code}.json")
            segs, log, gemb = link_pieces(load_pieces(pf), t)
            if args.max_speakers:
                segs = merge_to_max(segs, gemb, args.max_speakers)
            order: dict[int, int] = {}
            for _, _, s in segs:
                order.setdefault(s, len(order))
            base["doan"] = [{"bat_dau": round(a, 3), "ket_thuc": round(b, 3), "nguoi_noi": f"SPEAKER_{order[s]:02d}"}
                            for a, b, s in segs]
            base["so_nguoi"] = len(order)
            base["sherpa"]["link_threshold"] = t
            base["sherpa"]["max_speakers"] = args.max_speakers or None
            base["noi_phan"] = log
            out = out_dir / f"{code}.json"
            c.write_json(out, base)
            files.append(str(out))
            print(f"{name}/{code}: {len(order)} người")
        r = subprocess.run([str(EVAL_PYTHON), "-B", "-s", str(HERE / "evaluate.py"), "--group", f"S5B1R/{name}", *files],
                           capture_output=True, text=True, encoding="utf-8", env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        if r.returncode != 0:
            print(r.stderr[-800:])
            continue
        (out_dir.parent / f"{name}.md").write_text(f"### S5B1R {name}\n\n{r.stdout}", encoding="utf-8")
        print(f"\n### {name}\n{r.stdout}")


if __name__ == "__main__":
    main()
