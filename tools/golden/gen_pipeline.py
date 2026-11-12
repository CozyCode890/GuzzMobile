"""
Golden cho các hàm thuần của pipeline (Stage 4, bước B)  →  testdata/golden/pipeline/*.json

Chạy chính transcribe.py, config.py của desktop trên các đầu vào dựng sẵn (giờ cố định), ghi:
  hash.json        _hash (chuỗi json.dumps và mã), temp_dir_for, mã âm thanh / khoá chung / tên tệp kết quả đoạn
  naming.json      output_filename, sanitize_filename, unique_path, write_text (bytes ghi ra)
  transcript.json  hms, format_header, assemble_transcript (nhánh không người nói), count_models, has_speech

Test Kotlin: io.github.cozycode890.guzz.core.pipeline.*GoldenTest.
"""

from __future__ import annotations

import base64
import datetime as dt
import json
import os
import sys
import tempfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

_common.use_desktop_modules()
import config  # noqa: E402
import transcribe  # noqa: E402
from audio_processing import TimeMap  # noqa: E402

SCRIPT = os.path.basename(__file__)


# ---------------------------------------------------------------- hash.json

HASH_PARTS = [
    ["bai giang.m4a"],
    ["Bài giảng tuần 1 – Đạo đức kinh doanh.m4a|123456789|1789660800"],
    [10.0, 1e-05, -16.0, 0.1, 2.5, 100.0, 0.0, -0.0, 1e16, 1.5e16, 1e-7, 123456.789, 1e22, 5e-324, 0.3],
    [16000, -16, 0, 1, 1000, 20, 400, 2 ** 40],
    [True, False, None],
    [[1, [2, [3, "ba"]]], [], ["a", None, True]],
    [{"b": 1, "a": [1, 2], "ă": "x", "B": None}],
    ['quote " backslash \\ slash / newline \n tab \t cr \r', "\x01\x1f\x7f", "emoji 😀 và  "],
    ["/storage/emulated/0/Android/data/io.github.cozycode890.guzz.debug/files/input/B5.m4a|81234567|1789660800",
     16000, -16.0, True, 1000, 20, 400, 10.0, 20.0, 60.0, "flac"],
    ["content://com.android.providers.media.documents/document/audio%3A1000012345|81234567|0", 16000, -16.0,
     False, 700, 16, 300, 0.4, 3.0, 5.0, "flac"],
]

TEMP_DIR_CASES = [
    ("bai giang.m4a", "/sdcard/bai giang.m4a|1000|1789660800"),
    ("Bài giảng tuần 1.mp4", "content://x/document/1|5|0"),
    ("x.y.z.mp4", "/a/x.y.z.mp4|1|2"),
    ("noext", "/a/noext|1|2"),
    (".hidden", "/a/.hidden|1|2"),
    ("trailing.", "/a/trailing.|1|2"),
]

AUDIO_HASH_CASES = [
    # (vân tay, tham số âm thanh của mobile theo thứ tự audioHash, chữ ký video hoặc None)
    ("/sdcard/B5.m4a|81234567|1789660800", [16000, -16.0, True, 1000, 20, 400, 10.0, 20.0, 60.0, "flac"], None),
    ("/sdcard/B5.m4a|81234567|1789660800", [16000, -16.0, True, 1000, 20, 400, 10.0, 20.0, 60.0, "flac"],
     ["track", "auto"]),
    ("content://p/document/7|99|1789660800", [16000, -18.5, False, 700, 16, 300, 0.4, 3.0, 5.0, "flac"],
     ["track", "1"]),
]

SHARED_KEY_CASES = [
    # (audio_key, model, prompt hay None, languages, vocabulary, transcribe_mode)
    ("0123456789", "gemini-3.5-transcribe", None, [], [], "smart"),
    ("0123456789", "gemini-3.5-flash-lite", "Transcribe this audio.\nKeep Vietnamese diacritics.", ["vi", "en"],
     ["Đạo đức", "ESG"], "verbatim"),
]


def hash_json() -> dict:
    hash_cases = []
    for parts in HASH_PARTS:
        dumps = json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str)
        hash_cases.append({"parts": parts, "dumps": dumps, "hash": transcribe._hash(*parts)})

    temp_dirs = []
    cfg = config.Config(temp_folder=tempfile.gettempdir())
    for name, fp in TEMP_DIR_CASES:
        with mock.patch.object(transcribe, "fingerprint", return_value=fp):
            folder = transcribe.temp_dir_for(cfg, os.path.join(tempfile.gettempdir(), name))
        temp_dirs.append({"name": name, "fingerprint": fp, "dir": os.path.basename(folder)})

    audio = []
    for fp, params, signature in AUDIO_HASH_CASES:
        digest = transcribe._hash(fp, *params)
        audio.append({"fingerprint": fp, "params": params, "video_signature": signature,
                      "hash": digest if signature is None else transcribe._hash(digest, signature)})

    shared = []
    for audio_key, model, prompt, langs, vocab, mode in SHARED_KEY_CASES:
        # Đúng biểu thức shared_key của transcribe_chunks khi tắt nhận diện người nói (method None).
        method = None
        key = transcribe._hash(audio_key, config.hash_value(method), prompt, tuple(langs), tuple(vocab), mode,
                               None, None)
        shared.append({"audio_key": audio_key, "model": model, "prompt": prompt, "languages": langs,
                       "vocabulary": vocab, "transcribe_mode": mode, "shared_key": key,
                       "result_file": f"doan_003.{transcribe._hash(key, None)}.json"})
    return {"hash_cases": hash_cases, "temp_dirs": temp_dirs, "audio_hash": audio, "shared_keys": shared}


# ---------------------------------------------------------------- naming.json

TEMPLATES = ["{name}", "{name}_{model}", "{ten} {ngay_ghi}", "{date} {time} {name}", "{recorded}-{name}",
             "{{name}}", "{name}.", " . {name} . ", "a/b:{name}*?", "{model}/{gio}h{ngay}",
             "{bad}", "{name", "{}", "name}", "{0}"]
SOURCES = ["bai giang.m4a", "Bài giảng: tuần 1?.mp4", "...m4a", "  spaced  .wav", "x.y.z.mp3", "noext", ".hidden",
           "a<b>c|d\"e.ogg"]
RECORDED = dt.datetime(2026, 3, 5, 7, 9, 41)
NOW = dt.datetime(2026, 11, 12, 21, 4, 5)
SANITIZE = ["", "   ", "...", " . . ", "a.b.", "<>:\"/\\|?*", "  x  ", "tên.txt..", " nbsp ", "\tTab\n"]
UNIQUE_CASES = [
    ("bai giang.txt", []),
    ("bai giang.txt", ["bai giang.txt"]),
    ("bai giang.txt", ["bai giang.txt", "bai giang (2).txt", "bai giang (3).txt"]),
    ("bai giang.txt", ["BAI GIANG.TXT"]),
    ("bai giang.txt", ["bai giang.txt", "bai giang (3).txt"]),
    ("noext", ["noext"]),
    ("a.b.md", ["a.b.md"]),
]
TEXTS = ["", "Một dòng", "Hai\n\nđoạn\n", "Đã có\r\nCRLF\nvà LF\r", "x\n"]


def naming_json() -> dict:
    filenames = []
    for template in TEMPLATES:
        for source in SOURCES:
            cfg = config.Config(filename_template=template, output_ext=".txt", model="gemini-3.5-flash-lite")
            try:
                name = cfg.output_filename(source, recorded_at=RECORDED, now=NOW)
                error = None
            except (KeyError, IndexError, ValueError) as e:
                name, error = None, type(e).__name__
            filenames.append({"template": template, "source": source, "name": name, "error": error})

    sanitized = [{"input": s, "output": config.sanitize_filename(s)} for s in SANITIZE]

    unique = []
    for name, existing in UNIQUE_CASES:
        with tempfile.TemporaryDirectory() as d:
            for e in existing:
                open(os.path.join(d, e), "w").close()
            unique.append({"name": name, "existing": existing,
                           "result": os.path.basename(transcribe.unique_path(os.path.join(d, name)))})

    encoded = []
    for text in TEXTS:
        for encoding in config.ENCODINGS:
            for newline in config.NEWLINES:
                with tempfile.TemporaryDirectory() as d:
                    p = os.path.join(d, "out.txt")
                    transcribe.write_text(p, text, encoding, newline)
                    with open(p, "rb") as f:
                        raw = f.read()
                encoded.append({"text": text, "encoding": encoding, "newline": newline,
                                "base64": base64.b64encode(raw).decode("ascii")})
    return {"recorded": RECORDED.isoformat(), "now": NOW.isoformat(), "model": "gemini-3.5-flash-lite",
            "extension": ".txt", "filenames": filenames, "sanitize": sanitized, "unique": unique, "encode": encoded}


# ---------------------------------------------------------------- transcript.json

HMS = [0, 0.4, 0.5, 1.5, 2.5, 59.5, 60, 3599.5, 3600, 5069.7, 7322.49, 86399.5]
HEADER_CASES = [
    ("bai giang.m4a", 5069.7, {}),
    ("bai giang.mp4", 4733.2, {"gemini-3.5-flash-lite": 5}),
    ("Bài giảng.MKV", 600.0, {"gemini-3.5-transcribe": 1, "gemini-3.5-flash-lite": 4}),
    ("noext", 0.4, {"gemini-3.5-flash": 2}),
]
ASSEMBLE_CASES = [
    (["Đoạn 1.", "Đoạn 2.", "Đoạn 3."], False),
    (["Đoạn 1.", "Đoạn 2.", "Đoạn 3."], True),
    (["Một", "", "Ba\n\nBa tiếp"], False),
    (["Một", "", "Ba\n\nBa tiếp"], True),
    (["", ""], False),
    (["", ""], True),
]


class _FixedDatetime(dt.datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW


def transcript_json() -> dict:
    hms = [{"sec": s, "hms": transcribe.hms(s)} for s in HMS]

    headers = []
    for path, duration, models in HEADER_CASES:
        cfg = config.Config(model="gemini-3.5-flash-lite")
        with mock.patch.object(transcribe, "datetime", _FixedDatetime):
            text = transcribe.format_header(cfg, path, duration, {}, models)
        headers.append({"path": path, "duration": duration, "models": [[m, c] for m, c in models.items()],
                        "header": text})

    assembled = []
    for texts, mark in ASSEMBLE_CASES:
        cfg = config.Config(mark_chunks=mark)
        chunks = [{"file": f"doan_{i:03d}.flac", "bat_dau_giay": i * 600.0, "ket_thuc_giay": (i + 1) * 600.0}
                  for i in range(len(texts))]
        results = [{"van_ban": t, "tu": [], "model": "m"} for t in texts]
        body, minutes = transcribe.assemble_transcript(cfg, chunks, results, [], {}, TimeMap([(0, 1)]))
        assert minutes == {}
        assembled.append({"texts": texts, "mark_chunks": mark, "body": body})

    cfg = config.Config(model="gemini-3.5-flash-lite")
    results = [{"van_ban": "a", "model": "gemini-3.5-transcribe"}, {"van_ban": "b"},
               {"van_ban": "c", "model": "gemini-3.5-flash-lite"}, {"van_ban": "d", "model": "gemini-3.5-transcribe"},
               {"van_ban": "e", "model": ""}]
    counts = transcribe.count_models(cfg, results)
    count_models = {"default_model": cfg.model, "models": [r.get("model") for r in results],
                    "counts": [[m, c] for m, c in counts.items()]}

    speech = [{"map": m, "has_speech": transcribe.has_speech(m)}
              for m in [None, "", "00:00.0 - 00:05.0 Giảng viên", "không có gạch", "a -b", "x - y\nz"]]
    return {"now": NOW.isoformat(), "default_model": "gemini-3.5-flash-lite", "hms": hms, "headers": headers,
            "assemble": assembled, "count_models": count_models, "has_speech": speech}


def main() -> None:
    out = _common.output_dir("pipeline")
    for name, data in (("hash.json", hash_json()), ("naming.json", naming_json()),
                       ("transcript.json", transcript_json())):
        _common.write_json(out / name, data, SCRIPT)
        print(f"{name}: xong")


if __name__ == "__main__":
    main()
