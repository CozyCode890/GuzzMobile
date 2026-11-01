"""
Bước F3 của Stage 1 (D37): gửi cùng một đoạn 10 phút ở các định dạng khác nhau lên Gemini bằng google_ai.py của desktop,
xem định dạng nén có làm bản chép lời kém đi không.

Chạy bằng Python của desktop, chỉ đọc cài đặt và API key đã lưu của desktop, không ghi gì vào D:\\Guzz:
    D:\\Guzz\\runtime\\python\\python.exe -B -s spikes\\formats\\gemini_compare.py --yes

Không có --yes thì chỉ in ra sẽ gửi những gì. Gửi 5 yêu cầu: FLAC (hai lần, để biết Gemini tự dao động bao nhiêu),
OGG-Opus 32 kbps, M4A-AAC 64 kbps, WAV 16-bit. Tệp lấy từ $GUZZ_SPIKE_DATA/out/phone/formats (bench `encode` trên điện
thoại). Bản chép lời lưu ở $GUZZ_SPIKE_DATA/gemini (ngoài repo); script chỉ in số tổng hợp, không in lời nói.

Độ giống: difflib.SequenceMatcher trên dãy từ đã viết thường, so từng bản với FLAC lần 1.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "diarization"))
import _common as c  # noqa: E402

c.use_desktop_modules()
import config  # noqa: E402
import google_ai  # noqa: E402

RUNS = [
    ("flac_1", "F1_flac.flac"),
    ("flac_2", "F1_flac.flac"),
    ("opus32", "F1_opus32.ogg"),
    ("aac64", "F1_aac64.m4a"),
    ("wav", "F1_wav.wav"),
]


def words(text: str) -> list[str]:
    return re.findall(r"\w+", text.lower())


def main() -> None:
    c.utf8_console()
    D = c.data_dir()
    ap = argparse.ArgumentParser()
    ap.add_argument("--formats-dir", default=str(D / "out" / "phone" / "formats"))
    ap.add_argument("--yes", action="store_true", help="Gửi thật (đã hỏi người dùng, D37)")
    args = ap.parse_args()
    fdir = Path(args.formats_dir)
    out = D / "gemini"
    out.mkdir(parents=True, exist_ok=True)

    cfg = config.load_config()
    model = cfg.model
    prompt = None if google_ai.is_transcribe_model(model) else cfg.read_prompt()
    for _, name in RUNS:
        if not (fdir / name).is_file():
            raise SystemExit(f"Thiếu {fdir / name}")
    print(f"model {model}; prompt: {'không (model *-transcribe)' if prompt is None else 'prompt.txt của desktop'}")
    for label, name in RUNS:
        print(f"  {label}: {name} ({(fdir / name).stat().st_size / 2**20:.2f} MB)")
    if not args.yes:
        print("Chưa gửi. Thêm --yes để gửi thật.")
        return

    key = google_ai.get_api_key()
    if not key:
        raise SystemExit("Không thấy API key của desktop")
    client = google_ai.Client(key)
    summary = json.loads((out / "summary.json").read_text("utf-8")) if (out / "summary.json").is_file() else {}
    for label, name in RUNS:
        txt = out / f"{label}.txt"
        if txt.is_file() and label in summary:
            print(f"đã có: {label}")
            continue
        t0 = time.monotonic()
        try:
            text = client.transcribe(str(fdir / name), cfg, prompt, audio_seconds=600)
            err = None
        except google_ai.GoogleAIError as e:
            text, err = "", f"{e.kind} {e.http_status}: {e}"
        summary[label] = {"tep": name, "mime": google_ai.guess_mime(name), "bytes": (fdir / name).stat().st_size,
                          "giay": round(time.monotonic() - t0, 1), "token_vao": client.last_token_count,
                          "so_tu": len(words(text)), "loi": err, "model": model}
        txt.write_text(text, encoding="utf-8")
        (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{label}: {summary[label]['giay']} s, {summary[label]['token_vao']} token vào, "
              f"{summary[label]['so_tu']} từ" + (f", LỖI {err}" if err else ""))

    base = words((out / "flac_1.txt").read_text("utf-8"))
    print("\n| Bản | Tệp | MB | Token vào | Số từ | Giống FLAC 1 (%) |")
    print("| --- | --- | --- | --- | --- | --- |")
    for label, name in RUNS:
        s = summary[label]
        w = words((out / f"{label}.txt").read_text("utf-8"))
        ratio = difflib.SequenceMatcher(None, base, w, autojunk=False).ratio() * 100
        s["giong_flac_1"] = round(ratio, 2)
        print(f"| {label} | {name} | {s['bytes'] / 2**20:.2f} | {s['token_vao']} | {s['so_tu']} | {ratio:.1f} |")
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
