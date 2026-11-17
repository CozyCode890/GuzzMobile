"""
Golden cho các hàm thuần của pipeline (Stage 4, bước B)  →  testdata/golden/pipeline/*.json

Chạy chính transcribe.py, config.py của desktop trên các đầu vào dựng sẵn (giờ cố định), ghi:
  hash.json        _hash (chuỗi json.dumps và mã), temp_dir_for, mã âm thanh / khoá chung / tên tệp kết quả đoạn
  naming.json      output_filename, sanitize_filename, unique_path, write_text (bytes ghi ra)
  transcript.json  hms, format_header, assemble_transcript (nhánh không người nói), count_models, has_speech
  speaker.json     (Stage 5) speaker_hash, shared_key + tên tệp kết quả theo cách nhận diện, prompt người nói,
                   assemble_transcript + format_header đủ 3 cách, full_speaker_map, closest_alternative_method

Test Kotlin: io.github.cozycode890.guzz.core.pipeline.*GoldenTest.
"""

from __future__ import annotations

import base64
import dataclasses
import datetime as dt
import json
import os
import sys
import tempfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

desktop = _common.use_desktop_modules()
import config  # noqa: E402
import speaker  # noqa: E402
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
    # (vân tay, tham số Config của desktop, chữ ký video hoặc None). Mã tính bằng chính transcribe.audio_hash (Stage 7:
    # gồm cả tham số làm sạch; transient_clamp_db chỉ vào mã khi > 0).
    ("/sdcard/B5.m4a|81234567|1789660800", {}, None),
    ("/sdcard/B5.m4a|81234567|1789660800", {}, ["track", "auto"]),
    ("content://p/document/7|99|1789660800",
     dict(denoise=False, target_dbfs=-18.5, silence_trimming=False, min_silence_len=700, silence_thresh_offset=16,
          keep_silence=300, chunk_minutes=0.4, chunk_search_seconds=3.0, min_last_chunk_seconds=5.0,
          noise_sample_sec=2.0, bandpass_low=120, bandpass_high=8000, prop_decrease=0.85, level_window_sec=0.0),
     ["track", "1"]),
    ("/sdcard/L1.m4a|5|7", dict(noise_sample_sec=3.0, bandpass_low=120, bandpass_high=8000, prop_decrease=0.85,
                                level_window_sec=0.0, transient_clamp_db=15.0), None),
]

# Tên trường Config của desktop → khoá trong golden (đúng tên tham số Kotlin).
AUDIO_HASH_FIELDS = ["denoise", "sample_rate", "target_dbfs", "silence_trimming", "min_silence_len",
                     "silence_thresh_offset", "keep_silence", "noise_sample_sec", "bandpass_low", "bandpass_high",
                     "prop_decrease", "level_window_sec", "transient_clamp_db", "chunk_minutes",
                     "chunk_search_seconds", "min_last_chunk_seconds", "chunk_format"]


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
    default_cfg = config.load_config(str(desktop / "config.default.txt"))
    for fp, overrides, signature in AUDIO_HASH_CASES:
        cfg = dataclasses.replace(default_cfg, **overrides)
        with mock.patch.object(transcribe, "fingerprint", return_value=fp):
            digest = transcribe.audio_hash(cfg, "x", None if signature is None else tuple(signature))
        audio.append({"fingerprint": fp, "params": {k: getattr(cfg, k) for k in AUDIO_HASH_FIELDS},
                      "video_signature": signature, "hash": digest})

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


# ---------------------------------------------------------------- speaker.json (Stage 5)

SPEAKER_HASH_CASES = [
    # (audio_key, run_on, diarization_model, num, min, max)
    ("0123456789", "clean", "sherpa-onnx 2.0.1: segmentation-3.0 + campplus_zh_en_advanced, shift 0.1, on 0.2, "
                            "off 0.5, t 1.1, P 20.0, O 60.0", 0, 1, 6),
    ("abcdef0123", "original", "x", 3, 1, 6),
    ("abcdef0123", "clean", "x", 0, 0, 0),
]

MAP_TEXTS = [None, "SPEAKER MAP\nTimes are mm:ss.s measured from the start of this audio clip.\n\n"
                   "00:00.0 - 00:05.0  Giảng viên\n"]


def _cfg(**kw) -> config.Config:
    c = config.Config(model="gemini-3.8-flash")
    for k, v in kw.items():
        setattr(c, k, v)
    return c


def speaker_json() -> dict:
    hashes = []
    for audio_key, run_on, model, num, mn, mx in SPEAKER_HASH_CASES:
        cfg = _cfg(diarization_source=run_on, diarization_model=model, diarization_num_speakers=num,
                   diarization_min_speakers=mn, diarization_max_speakers=mx)
        hashes.append({"audio_key": audio_key, "run_on": run_on, "model": model, "num": num, "min": mn, "max": mx,
                       "hash": transcribe.speaker_hash(cfg, audio_key)})

    prompt = _cfg().read_diarization_prompt()
    shared = []
    for method in (None, "pyannote", "gemini", "hybrid"):
        for delivery, ts in (("file", True), ("text", False)):
            model = "gemini-3.8-flash" if method in (None, "pyannote") else "gemini-3.5-transcribe"
            cfg = _cfg(model=model, diarization_enabled=method is not None, diarization_method=method or "pyannote",
                       diarization_map_delivery=delivery, diarization_timestamps=ts, languages=("vi",),
                       vocabulary=("ESG",), transcribe_mode="verbatim")
            plain = None if config.is_transcribe_model(model) else cfg.read_prompt()
            key = transcribe._hash("0123456789", config.hash_value(method),
                                   prompt if method == "pyannote" else plain if method is None else None,
                                   cfg.languages, cfg.vocabulary, cfg.transcribe_mode,
                                   config.hash_value(delivery) if method == "pyannote" else None,
                                   ts if method == "pyannote" else None)
            for m in MAP_TEXTS:
                shared.append({"method": method, "model": model, "map_delivery": delivery, "timestamps": ts,
                               "map_text": m, "shared_key": key,
                               "result_file": f"doan_002.{transcribe._hash(key, m)}.json"})

    # assemble_transcript + format_header: 3 đoạn, cắt lặng, đủ 3 cách.
    tmap = TimeMap([(1.5, 30.0), (31.0, 70.25), (80.0, 200.0)], 210.0)
    chunks = [{"file": f"doan_{i:03d}.flac", "bat_dau_giay": a, "ket_thuc_giay": b}
              for i, (a, b) in enumerate([(0.0, 50.0), (50.0, 100.0), (100.0, 187.75)], 1)]
    turns = [speaker.SpeakerTurn(0.0, 20.0, "SPEAKER_01"), speaker.SpeakerTurn(20.0, 31.5, "SPEAKER_00"),
             speaker.SpeakerTurn(31.5, 90.0, "SPEAKER_01"), speaker.SpeakerTurn(91.0, 95.0, "SPEAKER_02"),
             speaker.SpeakerTurn(95.0, 187.75, "SPEAKER_01")]
    name_map = {"SPEAKER_01": "Giảng viên", "SPEAKER_00": "Người hỏi 1", "SPEAKER_02": "Người hỏi 2"}

    def wd(items):
        return [{"tu": t, "nguoi_noi": s, "bat_dau": a, "ket_thuc": b} for t, s, a, b in items]

    results_by_method = {
        None: [{"van_ban": "Một.", "tu": []}, {"van_ban": "", "tu": []}, {"van_ban": "Ba\n\nbốn", "tu": []}],
        "pyannote": [
            {"van_ban": "[00:01] Giảng viên: Mở đầu.\n\n[00:21] **Người hỏi 1:** Hỏi?\nTiếp câu hỏi\n[00:40] Giảng viên:",
             "tu": []},
            {"van_ban": "Không có tên nào ở đây\n\nđoạn hai", "tu": []},
            {"van_ban": "(01:02) Người hỏi 2: Em hỏi.\n[1:00:00] Giảng viên: Trả lời dài.", "tu": []},
        ],
        "gemini": [
            {"van_ban": "a b c", "tu": wd([("Hỏi", "spk_1", 0.0, 0.5), ("gì", "spk_1", 0.5, 1.0), ("?", "spk_1", 1.0, 1.1),
                                          ("Trả", "spk_2", 1.5, 2.0), ("lời", "spk_2", 2.0, 30.0)])},
            {"van_ban": "không mốc", "tu": []},
            {"van_ban": "x", "tu": wd([("x", None, 0.0, 2.0), ("y", None, 6.0, 7.0)])},
        ],
        "hybrid": [
            {"van_ban": "a", "tu": wd([("một", None, 0.0, 1.0), ("hai", None, 21.0, 22.0), ("ba", None, 25.0, 49.0)])},
            {"van_ban": "b", "tu": wd([("bốn", None, 0.0, 30.0), ("năm", None, 41.5, 44.0), ("sáu", None, 45.0, 46.0)])},
            {"van_ban": "chỉ chữ", "tu": []},
        ],
    }
    assembles = []
    for method, results in results_by_method.items():
        for mark, ts, template, smooth in ((False, True, "[{time}] {speaker}: {text}", True),
                                           (True, False, "{speaker} ({time}): {text}", False)):
            cfg = _cfg(diarization_enabled=method is not None, diarization_method=method or "pyannote",
                       mark_chunks=mark, diarization_timestamps=ts, diarization_paragraph_template=template,
                       diarization_smooth=smooth, diarization_density_min_sec=5.0)
            body, minutes = transcribe.assemble_transcript(cfg, chunks, results, turns,
                                                           name_map if method == "pyannote" else {}, tmap)
            with mock.patch.object(transcribe, "datetime", _FixedDatetime):
                header = transcribe.format_header(cfg, "/x/bai giang.m4a", tmap.orig_duration, minutes,
                                                  {"gemini-3.8-flash": 3})
            assembles.append({"method": method, "mark_chunks": mark, "timestamps": ts, "template": template,
                              "smooth": smooth, "body": body, "minutes": [[k, v] for k, v in minutes.items()],
                              "header": header})

    full_map = transcribe.full_speaker_map(turns, name_map, tmap)

    models = ["gemini-3.5-transcribe", "gemini-3.8-flash", "gemini-3.5-flash-lite", "my-transcribe-x"]
    alternatives = [{"model": m, "method": meth, "closest": config.closest_alternative_method(m, meth)}
                    for m in models for meth in ("pyannote", "gemini", "hybrid")]
    return {
        "speaker_hash": hashes, "shared_keys": shared, "diarization_prompt": prompt,
        "time_map": [list(s) for s in tmap.segments], "orig_duration": tmap.orig_duration,
        "chunks": chunks, "turns": [[t.start, t.end, t.speaker] for t in turns], "name_map": name_map,
        "results": {str(k): v for k, v in results_by_method.items()}, "assemble": assembles,
        "full_speaker_map": full_map, "alternatives": alternatives,
    }


def main() -> None:
    out = _common.output_dir("pipeline")
    for name, data in (("hash.json", hash_json()), ("naming.json", naming_json()),
                       ("transcript.json", transcript_json()), ("speaker.json", speaker_json())):
        _common.write_json(out / name, data, SCRIPT)
        print(f"{name}: xong")


if __name__ == "__main__":
    main()
