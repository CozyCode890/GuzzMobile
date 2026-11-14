"""
Golden cho speaker.py  →  testdata/golden/speaker/speaker.json

Lượt nói và từ tổng hợp, sinh bằng random.Random với seed cố định (đầu vào ghi luôn vào JSON, nên Kotlin không cần sinh
lại): nhiều người, lượt ngắn kẹp giữa, người phụ thưa, lượt chạm biên đoạn, từ có / không có nhãn. Chạy chính các hàm
của speaker.py trên đó và ghi kết quả để SpeakerGoldenTest so khớp:

- process_turns (nhiều bộ cài đặt) + build_name_map + minutes_per_speaker;
- split_turns_by_chunk + speaker_map_text + speakers_in_chunk;
- parse_prompt_paragraphs (TimeMap có cắt lặng);
- words_to_spans + assign_speakers + smooth + rename_speakers + merge_spans + spans_to_paragraphs;
- format_paragraph, join_words, _mm_ss, hh_mm_ss.
"""

from __future__ import annotations

import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

_common.use_desktop_modules()
import config  # noqa: E402
import speaker  # noqa: E402
from audio_processing import TimeMap  # noqa: E402

SEED = 20261114


def make_cfg(o: dict) -> config.Config:
    c = config.Config()
    for k, v in o.items():
        setattr(c, k, v)
    return c


# Bộ cài đặt thử: tên trường của Config desktop → giá trị. Kotlin đọc cùng tên (SpeakerOptions).
CONFIGS = {
    "mac_dinh": {},
    "tat_lam_muot": {"diarization_smooth": False},
    "khong_nguoi_chinh": {"diarization_label_main": False, "diarization_generic_label": "Speaker"},
    "kep_rong": {"diarization_sandwich_max_sec": 8.0, "diarization_sandwich_gap_sec": 2.5,
                 "diarization_merge_gap_sec": 0.0},
    "mat_do_chat": {"diarization_density_window_sec": 20.0, "diarization_density_min_sec": 4.0,
                    "diarization_merge_gap_sec": 3.0, "diarization_main_label": "Thầy",
                    "diarization_other_label": "SV"},
    "tat_mat_do": {"diarization_density_min_sec": 0.0},
}


def opts(cfg: config.Config) -> dict:
    return {
        "smooth": cfg.diarization_smooth,
        "sandwich_max_sec": cfg.diarization_sandwich_max_sec,
        "sandwich_gap_sec": cfg.diarization_sandwich_gap_sec,
        "density_window_sec": cfg.diarization_density_window_sec,
        "density_min_sec": cfg.diarization_density_min_sec,
        "merge_gap_sec": cfg.diarization_merge_gap_sec,
        "label_main": cfg.diarization_label_main,
        "main_label": cfg.diarization_main_label,
        "other_label": cfg.diarization_other_label,
        "generic_label": cfg.diarization_generic_label,
    }


def turns_json(items) -> list:
    return [[t.start, t.end, t.speaker] for t in items]


def gen_turns(rng: random.Random, n: int, speakers: list[str], main_share: float, short_prob: float) -> list:
    """Lượt nói không chồng lấn kiểu bài giảng: người chính nói dài, người phụ chen vào; có lượt rất ngắn kẹp giữa."""
    out, t = [], rng.uniform(0, 3)
    main = speakers[0]
    for _ in range(n):
        if rng.random() < main_share:
            s = main
            dur = rng.uniform(2, 40)
        else:
            s = rng.choice(speakers[1:]) if len(speakers) > 1 else main
            dur = rng.uniform(0.2, 2.5) if rng.random() < short_prob else rng.uniform(2, 20)
        # Mốc lẻ kiểu ms để có số thực thật.
        a = round(t, 3)
        b = round(t + dur, 3)
        out.append(speaker.SpeakerTurn(a, b, s))
        t = b + (0 if rng.random() < 0.3 else rng.uniform(0.01, 4))
    return out


def turn_sets(rng: random.Random) -> dict:
    sets = {
        "mot_nguoi": gen_turns(rng, 40, ["SPEAKER_00"], 1.0, 0),
        "hai_nguoi_kep": gen_turns(rng, 200, ["SPEAKER_00", "SPEAKER_01"], 0.7, 0.6),
        "nhieu_nguoi": gen_turns(rng, 250, ["SPEAKER_01", "SPEAKER_00", "SPEAKER_02", "SPEAKER_03", "SPEAKER_04"],
                                 0.55, 0.4),
        "nguoi_phu_thua": gen_turns(rng, 200, ["SPEAKER_02", "SPEAKER_00", "SPEAKER_01"], 0.92, 0.2),
        "hoi_dap": gen_turns(rng, 150, ["SPEAKER_00", "SPEAKER_01"], 0.5, 0.1),
        "rong": [],
    }
    # Bằng thời gian: hai người nói đúng bằng nhau, người gặp trước là người chính (max của dict).
    sets["hoa"] = [speaker.SpeakerTurn(0.0, 10.0, "B"), speaker.SpeakerTurn(10.5, 20.5, "A"),
                   speaker.SpeakerTurn(21.0, 21.4, "B"), speaker.SpeakerTurn(22.0, 22.4, "A")]
    return sets


def process_cases(sets: dict) -> list:
    out = []
    for set_name, raw in sets.items():
        for cfg_name, o in CONFIGS.items():
            cfg = make_cfg(o)
            processed, n = speaker.process_turns(raw, cfg)
            primary = speaker.find_main_speaker(raw)
            names = speaker.build_name_map(processed, cfg, primary=primary)
            names_self = speaker.build_name_map(processed, cfg)
            out.append({
                "name": f"{set_name}/{cfg_name}",
                "set": set_name,
                "options": opts(cfg),
                "processed": turns_json(processed),
                "num_changed": n,
                "primary": primary,
                "name_map": names,
                "name_map_self": names_self,
                "minutes": speaker.minutes_per_speaker(processed, names),
                # write_json sắp khoá, nên ghi riêng thứ tự (người nói lâu nhất trước).
                "minutes_order": list(speaker.minutes_per_speaker(processed, names)),
            })
    return out


def chunk_cases(sets: dict) -> list:
    out = []
    names = {"SPEAKER_00": "Giảng viên", "SPEAKER_01": "Người hỏi 1", "SPEAKER_02": "Người hỏi 2"}
    for set_name in ("hai_nguoi_kep", "nhieu_nguoi", "rong"):
        raw = sets[set_name]
        end = raw[-1].end if raw else 100.0
        # Biên đoạn rơi vào giữa lượt, đúng mép lượt, và đoạn ngoài cùng.
        bounds = [(0.0, 600.0), (600.0, 1200.0), (123.456, 789.012), (end - 30, end + 30), (end + 10, end + 20)]
        if raw:
            bounds.append((raw[3].start, raw[7].end))
        for a, b in bounds:
            for min_sec in (0.3, 0.0, 2.0):
                chunk = speaker.split_turns_by_chunk(raw, a, b, min_sec)
                out.append({
                    "name": f"{set_name}/{a}-{b}/{min_sec}",
                    "set": set_name,
                    "start": a, "end": b, "min_sec": min_sec,
                    "name_map": names,
                    "chunk": turns_json(chunk),
                    "map_text": speaker.speaker_map_text(chunk, names),
                    "speakers": speaker.speakers_in_chunk(chunk, names),
                })
    return out


PROMPT_TEXTS = [
    "[00:05] Giảng viên: Hôm nay học SVM.\n\n**Người hỏi 1:** Thưa thầy, ví dụ: kernel là gì?\n"
    "Ví dụ: một dòng không có tên\n[01:02:03]\nGiảng viên:\n\nKernel là hàm.",
    "(12:34.5) **Giảng viên**: chào cả lớp\r\n[1:02] *Người hỏi 2*: em hỏi\r\n  [ 03:04 ]   tiếp tục\r\nGIẢNG VIÊN : hết",
    "Không có tên ở dòng đầu\n[00:10] Người lạ: không nhận\nNgười hỏi 1:\n[00:20]\nmốc treo\n\u2028Giảng viên: sau U+2028",
    "",
    "   \n\n",
    "[99:59] Giảng viên: phút lớn\n[100:00] Giảng viên: ba chữ số phút\n[1000:00] không khớp mốc",
    "Giảng viên:   \n[00:01]   \nNgười hỏi 1: tên rồi mốc treo bị bỏ\n",
]


def prompt_cases() -> list:
    out = []
    tmaps = {
        "cat_lang": ([(10.0, 20.0), (30.0, 50.0), (60.5, 4000.25)], 4100.0),
        "khong_cat": ([(0.0, 7200.0)], None),
    }
    names = ["Giảng viên", "Người hỏi 1", "Người hỏi 2"]
    for i, text in enumerate(PROMPT_TEXTS):
        for tm_name, (segs, dur) in tmaps.items():
            tm = TimeMap(segs, dur)
            for chunk_start in (0.0, 15.5):
                paras = speaker.parse_prompt_paragraphs(text, names, chunk_start, tm.to_original)
                out.append({
                    "name": f"text{i}/{tm_name}/{chunk_start}",
                    "text": text, "names": names, "chunk_start": chunk_start,
                    "segments": [list(s) for s in segs], "orig_duration": dur,
                    "paragraphs": [[p.content, p.speaker, p.timestamp, p.review] for p in paras],
                })
    return out


def gen_words(rng: random.Random, n: int, labels: list | None) -> list:
    vocab = ["Hôm", "nay", "chúng", "ta", "học", "SVM", ",", ".", "(", ")", "?", "kernel", "là", "gì", "%", "…", "]",
             "[", "{", "}", ";", ":", "!", "dữ", "liệu"]
    out, t = [], rng.uniform(0, 1)
    for _ in range(n):
        dur = rng.uniform(0.05, 0.9)
        w = {"tu": rng.choice(vocab), "bat_dau": round(t, 3), "ket_thuc": round(t + dur, 3)}
        w["nguoi_noi"] = rng.choice(labels) if labels else None
        out.append(w)
        t += dur + (rng.uniform(2, 6) if rng.random() < 0.03 else rng.uniform(0, 0.4))
    return out


def word_cases(rng: random.Random, sets: dict, word_lists: dict) -> list:
    out = []
    tm = TimeMap([(5.0, 300.0), (301.0, 900.0), (950.0, 5000.0)], 5100.0)
    combos = [
        ("hai_nguoi_kep", None, 0.0),
        ("nhieu_nguoi", None, 37.25),
        ("nguoi_phu_thua", ["spk_1", "spk_2"], 0.0),
        ("hoi_dap", None, 600.0),
        ("rong", None, 0.0),
    ]
    for set_name, labels, offset in combos:
        turns = sets[set_name]
        words = gen_words(rng, 250, labels)
        word_lists[set_name] = words
        for cfg_name in ("mac_dinh", "tat_mat_do", "khong_nguoi_chinh"):
            cfg = make_cfg(CONFIGS[cfg_name])
            for max_sec, gap in ((60.0, 3.0), (15.0, 0.5)):
                spans = speaker.words_to_spans(words, offset)
                speaker.assign_speakers(spans, turns)
                assigned = [[c.start, c.end, c.content, c.speaker, c.review] for c in spans]
                changed = speaker.smooth(spans, turns, cfg.diarization_sandwich_max_sec,
                                         cfg.diarization_sandwich_gap_sec, cfg.diarization_density_window_sec,
                                         cfg.diarization_density_min_sec, prefer_primary=cfg.diarization_label_main)
                primary = speaker.find_main_speaker(turns)
                names = speaker.build_name_map(spans, cfg, primary=primary)
                speaker.rename_speakers(spans, names)
                merged = speaker.merge_spans(spans, max_sec, gap)
                paras = speaker.spans_to_paragraphs(merged, tm.to_original)
                out.append({
                    "name": f"{set_name}/{cfg_name}/{max_sec}-{gap}",
                    "options": opts(cfg),
                    "set": set_name, "offset": offset,
                    "max_seconds": max_sec, "gap": gap,
                    "segments": [list(s) for s in tm.segments], "orig_duration": tm.orig_duration,
                    "assigned": assigned,
                    "smooth_changed": changed,
                    "primary": primary,
                    "name_map": names,
                    "merged": [[c.start, c.end, c.content, c.speaker, c.review] for c in merged],
                    "paragraphs": [[p.content, p.speaker, p.timestamp, p.review] for p in paras],
                })
    return out


def format_cases() -> list:
    paras = [
        speaker.Paragraph("abc", "Giảng viên", 3725.4, True),
        speaker.Paragraph("không mốc", "Người hỏi 1", None, False),
        speaker.Paragraph("không tên", None, 12.9, True),
        speaker.Paragraph("tên rỗng", "", 7.0, False),
        speaker.Paragraph("  có khoảng  ", "SV", 0.0, False),
        speaker.Paragraph("âm", "A", -5.0, False),
        speaker.Paragraph("lớn", "B", 400000.99, True),
    ]
    templates = ["[{time}] {speaker}: {text}", "{speaker} ({time}): {text}", "{speaker}: {text}",
                 "[{moc}] {nguoi}: {noi_dung}", "<{time}> {speaker} — {text}", "{{{speaker}}} [ {time} ] {text}",
                 "{time} {speaker}: {text}", "{text}"]
    out = []
    for p in paras:
        for t in templates:
            for ts in (True, False):
                for mr in (True, False):
                    out.append({"paragraph": [p.content, p.speaker, p.timestamp, p.review], "template": t,
                                "timestamps": ts, "mark_review": mr,
                                "result": speaker.format_paragraph(p, t, ts, mr)})
    return out


def misc_cases() -> dict:
    secs = [0.0, -3.0, 0.04, 0.05, 0.15, 0.25, 0.35, 9.95, 59.94, 59.95, 59.96, 60.0, 61.25, 3599.99, 3600.0,
            7322.45, 12345.678]
    joins = [("", "a"), ("a", ","), ("a", "( b"), ("x (", "y"), ("a", "…"), ("a", " b "), ("", " ( x ) ."),
             ("Hôm", "nay ,"), ("a\u00a0", "% b")]
    return {
        "mm_ss": [[s, speaker._mm_ss(s)] for s in secs],
        "hh_mm_ss": [[s, speaker.hh_mm_ss(s)] for s in secs],
        "join_words": [[a, b, speaker.join_words(a, b)] for a, b in joins],
    }


def main() -> None:
    rng = random.Random(SEED)
    sets = turn_sets(rng)
    word_lists: dict = {}
    data = {
        "turn_sets": {k: turns_json(v) for k, v in sets.items()},
        "process": process_cases(sets),
        "chunks": chunk_cases(sets),
        "prompt": prompt_cases(),
        "words": word_cases(rng, sets, word_lists),
        "word_lists": word_lists,
        "format": format_cases(),
        **misc_cases(),
    }
    out = _common.output_dir("speaker")
    _common.write_json(out / "speaker.json", data, os.path.basename(__file__))
    print(f"speaker.json: {len(data['process'])} process, {len(data['chunks'])} chunks, {len(data['prompt'])} prompt, "
          f"{len(data['words'])} words, {len(data['format'])} format")


if __name__ == "__main__":
    main()
