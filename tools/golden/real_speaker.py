"""
Golden speaker.py trên lượt nói của bản ghi thật (Stage 5, bước C2). KHÔNG đặt tên gen_*: regenerate.ps1 không chạy nó,
và kết quả nằm NGOÀI repo (lượt nói của bản ghi thật không commit).

Với mỗi bài B1–B5 của Stage 1 và mỗi nguồn lượt nói có sẵn (ref.json của desktop pyannote; JSON sherpa-onnx của
Stage 1 B4 và Stage 5 B1 nếu có): chạy load_speaker_turns → process_turns → find_main_speaker → build_name_map →
minutes_per_speaker với cài đặt của config.default.txt. Ghi `$GUZZ_SPIKE_DATA\\golden-stage-05\\<mã>__<nguồn>.json`;
test Kotlin `RealSpeakerGoldenTest` đọc lại và so từng lượt.

    D:\\Guzz\\runtime\\python\\python.exe -B -s tools\\golden\\real_speaker.py
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

_common.use_desktop_modules()
import config  # noqa: E402
import speaker  # noqa: E402

CODES = ["B1", "B2", "B3", "B4", "B5"]


def data_dir() -> Path:
    return Path(os.environ.get("GUZZ_SPIKE_DATA") or r"D:\GuzzMobileData\stage-01")


def sources(D: Path, code: str) -> dict[str, Path]:
    out = {"ref": D / code / "ref.json"}
    b4 = D / "out" / "pc" / "B4" / "campplus-zhen_shift0.1" / f"{code}.json"
    if b4.is_file():
        out["s1b4"] = b4
    s5 = D / "out" / "pc" / "S5B1"
    if s5.is_dir():
        for cfg_dir in sorted(p for p in s5.iterdir() if p.is_dir()):
            f = cfg_dir / f"{code}.json"
            if f.is_file():
                out[f"s5b1_{cfg_dir.name}"] = f
    return {k: v for k, v in out.items() if v.is_file()}


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    D = data_dir()
    cfg = config.load_config(str(_common.desktop_dir() / "config.default.txt"))
    out_dir = D / "golden-stage-05"
    out_dir.mkdir(parents=True, exist_ok=True)
    for code in CODES:
        for name, path in sources(D, code).items():
            raw = speaker.load_speaker_turns(str(path))
            processed, n = speaker.process_turns(raw, cfg)
            primary = speaker.find_main_speaker(raw)
            names = speaker.build_name_map(processed, cfg, primary=primary)
            minutes = speaker.minutes_per_speaker(processed, names)
            data = {
                "input": str(path),
                "processed": [[t.start, t.end, t.speaker] for t in processed],
                "num_changed": n,
                "primary": primary,
                "name_map": names,
                "minutes": [[k, v] for k, v in minutes.items()],
            }
            with open(out_dir / f"{code}__{name}.json", "w", encoding="utf-8", newline="\n") as f:
                json.dump(data, f, ensure_ascii=False)
            print(f"{code}/{name}: {len(raw)} → {len(processed)} lượt, {len(names)} người, đổi {n}")


if __name__ == "__main__":
    main()
