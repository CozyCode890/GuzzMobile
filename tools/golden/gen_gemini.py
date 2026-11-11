"""
Golden cho client Gemini (Stage 3)  →  testdata/golden/gemini/*.json

Chạy chính google_ai.py, quota.py, config.py, transcribe.ChunkDispatcher của desktop trên các đầu vào dựng sẵn:

    errors.json    error_from_http, _to_seconds, check_status                     ErrorGoldenTest
    requests.json  build_request (ma trận cài đặt), extract_text / words, token…   RequestGoldenTest
    pacific.json   pacific_date, next_daily_reset quanh các lần đổi giờ 2025–2030  PacificTimeGoldenTest
    tracker.json   chuỗi thao tác trên QuotaTracker (đồng hồ giả) → tệp JSON       QuotaTrackerGoldenTest
    config.json    model_quotas mặc định, [QUOTAS], model_chain, constraint_error  GeminiConfigGoldenTest
    replay.json    ChunkDispatcher + Client thật, chỉ HTTP giả (chuỗi lỗi 17/09)   ReplayGoldenTest

Không gọi mạng. Tệp tạm (tệp hạn mức, đoạn âm thanh giả) nằm trong thư mục tạm của hệ thống, không ghi gì vào desktop.
"""

from __future__ import annotations

import base64
import email.message
import io
import itertools
import json
import os
import random
import sys
import tempfile
import urllib.error
from datetime import datetime, timedelta, timezone
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

desktop = _common.use_desktop_modules()
import config  # noqa: E402
import google_ai as ga  # noqa: E402
import quota  # noqa: E402
import transcribe  # noqa: E402

SCRIPT = os.path.basename(__file__)
UTC = timezone.utc


def epoch(*a) -> float:
    return datetime(*a, tzinfo=UTC).timestamp()


# Các trường của Config mà bản Kotlin (GeminiConfig) có. Ghi đủ, để mặc định khác nhau giữa dataclass Python và Kotlin
# không làm lệch kết quả.
CFG_FIELDS = (
    "model", "transcribe_mode", "languages", "vocabulary", "store_on_server", "timeout_seconds", "chunk_retries",
    "delete_remote_file", "token_rate_limit", "auto_switch_model", "fallback_models", "retries_before_switch",
    "overload_lock_minutes", "return_to_main_model", "switch_wait_threshold_sec", "max_all_locked_wait_min",
    "proactive_daily_lock", "model_quotas", "diarization_enabled", "diarization_method",
)


def cfg_json(cfg: config.Config) -> dict:
    out = {}
    for name in CFG_FIELDS:
        value = getattr(cfg, name)
        if name == "model_quotas":
            value = {m: list(q) for m, q in value.items()}
        elif isinstance(value, tuple):
            value = list(value)
        out[name] = value
    return out


def make_cfg(**kw) -> config.Config:
    cfg = config.Config()
    for k, v in kw.items():
        setattr(cfg, k, v)
    return cfg


def error_json(e: ga.GoogleAIError) -> dict:
    return {
        "kind": e.kind, "message": str(e), "http_status": e.http_status, "retry_after": e.retry_after,
        "is_daily": e.is_daily, "is_retryable": e.is_retryable, "stop_queue": e.stop_queue,
        "violations": [{"metric": v.metric, "limit": v.limit, "model": v.model, "quota_id": v.quota_id,
                        "is_token": v.is_token, "unit": v.unit} for v in e.violations],
    }


# ---------------------------------------------------------------- errors.json

REAL_TOKEN_429 = ("You exceeded your current quota, please check your plan and billing details.\n"
                  "* Quota exceeded for metric: generativelanguage.googleapis.com/"
                  "generate_content_free_tier_input_token_count, limit: 10000, "
                  "model: gemini-3.5-transcribe\nPlease retry in 55.981490556s.")
REAL_DAILY_429 = ("You exceeded your current quota, please check your plan and billing details.\n"
                  "* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, "
                  "limit: 25, model: gemini-3.5-transcribe\nPlease retry in 27.888016836s.")
HIGH_DEMAND = ("gemini-3.8-flash is currently experiencing high demand, spikes in demand are usually temporary. "
               "Please try again later.")
INPUT_BLOCKED = ("Input blocked: This request was blocked by Gemini's filters. They can occasionally trigger by "
                 "mistake on safe coding, security, or biology-related queries. Please try rephrasing your prompt.")


def j(obj) -> str:
    return json.dumps(obj, ensure_ascii=False)


ERROR_CASES = [
    ("429_retry_info", 429, j({"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "quota", "details": [
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37s"}]}}), {}),
    ("429_real_token_2026_09_17", 429, j({"error": {"code": 429, "status": "RESOURCE_EXHAUSTED",
                                                    "message": REAL_TOKEN_429}}), {}),
    ("429_real_daily_2026_09_17", 429, j({"error": {"code": 429, "message": REAL_DAILY_429}}), {}),
    ("429_quota_failure_per_day", 429, j({"error": {"status": "RESOURCE_EXHAUSTED", "message": "quota", "details": [
        {"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [{
            "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
            "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
            "quotaDimensions": {"model": "gemini-3.8-flash", "location": "global"}, "quotaValue": "20"}]}]}}), {}),
    ("429_quota_failure_per_minute_tokens", 429, j({"error": {"status": "RESOURCE_EXHAUSTED", "message": "x",
                                                              "details": [
        {"violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_input_token_count",
                         "quotaId": "GenerateContentInputTokensPerModelPerMinute-FreeTier", "quotaValue": 250000}]},
        "not a dict",
        {"violations": [{"quotaDimensions": {"model": "m"}}, {"quotaId": "OnlyId", "quotaValue": "abc"}]},
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": {"seconds": 5, "nanos": 500000000}}]}}),
     {}),
    ("429_retry_after_header", 429, j({"error": {"message": "slow down"}}), {"Retry-After": "120"}),
    ("429_retry_in_message_and_header", 429, j({"error": {"message": "Please retry in 3.5 s, thanks"}}),
     {"Retry-After": "2"}),
    ("429_several_violations_in_message", 429, j({"error": {"message": (
        "* Quota exceeded for metric: a/requests, limit: 5, model: gemini-3.5-flash.\n"
        "* Quota exceeded for metric: a/input_token_count , limit: 250000\n"
        "* QUOTA EXCEEDED FOR METRIC: b/requests_per_day, limit: 20, model: gemini-3.8-flash")}}), {}),
    ("400_api_key_invalid", 400, j({"error": {"status": "INVALID_ARGUMENT", "message": "API key not valid.",
                                              "details": [{"reason": "API_KEY_INVALID"}]}}), {}),
    ("400_input_blocked_real", 400, j({"error": {"code": 400, "message": INPUT_BLOCKED}}), {}),
    ("400_prohibited_content_status", 400, j({"error": {"status": "PROHIBITED_CONTENT", "message": "nope"}}), {}),
    ("400_other", 400, j({"error": {"status": "INVALID_ARGUMENT", "message": "Bad mime type"}}), {}),
    ("401", 401, j({"error": {"message": "Unauthorized"}}), {}),
    ("403", 403, j({"error": {"message": "Permission denied"}}), {}),
    ("404_model", 404, j({"error": {"message": "models/gemini-9 is not found for API version v1beta"}}), {}),
    ("404_not_model", 404, j({"error": {"status": "NOT_FOUND", "message": "Requested entity was not found."}}), {}),
    ("408", 408, j({"error": {"message": "timeout"}}), {}),
    ("500_high_demand_real", 500, j({"error": {"code": 500, "message": HIGH_DEMAND}}), {}),
    ("503_html", 503, "<html>Service Unavailable</html>", {}),
    ("500_empty_body", 500, "", {}),
    ("502_long_non_json", 502, "x" * 250 + " ở giữa " + "y" * 200, {}),
    ("302", 302, "", {}),
    ("400_error_is_string", 400, j({"error": "boom"}), {}),
    ("400_error_is_null", 400, j({"error": None}), {}),
    ("400_body_is_list", 400, j([1, 2]), {}),
    ("400_body_without_error_key", 400, j({"message": "top level", "status": "FAILED_PRECONDITION"}), {}),
    ("400_message_is_number", 400, j({"error": {"message": 42, "status": 7}}), {}),
    ("429_unicode_message", 429, j({"error": {"message": "Hết hạn mức — thử lại sau. Please retry in 10s."}}), {}),
]

TO_SECONDS_CASES = ["30s", "1.5s", 30, 2.5, "  7 s ", "-5s", "1.2.3", "", "abc", None, {"seconds": 1, "nanos": 500000000},
                    {"seconds": "2"}, {"seconds": "x"}, {"nanos": 1e9}, {}, "0.100s", "1e-3s"]

CHECK_STATUS_CASES = [
    ("completed", {"status": "completed", "steps": []}),
    ("no_status", {"steps": []}),
    ("upper_completed", {"status": "COMPLETED"}),
    ("incomplete_with_text", {"status": "incomplete", "errors": [{"message": "max tokens"}],
                              "steps": [{"type": "model_output", "content": [{"type": "text", "text": "Có chữ"}]}]}),
    ("incomplete_without_text", {"status": "incomplete", "steps": [
        {"type": "model_output", "content": [{"type": "text", "text": "   "}]}]}),
    ("failed", {"status": "failed", "errors": [{"message": "hong"}]}),
    ("failed_code_only", {"status": "failed", "errors": [{"code": 13}, "chuoi loi", 7]}),
    ("in_progress", {"status": "in_progress"}),
    ("queued", {"status": "queued"}),
    ("blocked_in_details", {"status": "failed", "errors": [{"message": "Response was blocked: PROHIBITED_CONTENT"}]}),
    ("cancelled", {"status": "cancelled"}),
]


def gen_errors(out) -> None:
    cases = []
    for name, code, body, hdr in ERROR_CASES:
        headers = email.message.Message()
        for k, v in hdr.items():
            headers[k] = v
        e = ga.error_from_http(code, body.encode("utf-8"), headers)
        cases.append({"name": name, "code": code, "body": body, "headers": hdr, "expected": error_json(e)})
    to_seconds = [{"raw": raw, "expected": ga._to_seconds(raw)} for raw in TO_SECONDS_CASES]
    statuses = []
    for name, response in CHECK_STATUS_CASES:
        try:
            ga.check_status(response)
            statuses.append({"name": name, "response": response, "ok": True})
        except ga.GoogleAIError as e:
            statuses.append({"name": name, "response": response, "ok": False, "kind": e.kind, "message": str(e)})
    _common.write_json(out / "errors.json", {"http": cases, "to_seconds": to_seconds, "check_status": statuses}, SCRIPT)
    print(f"errors.json: {len(cases)} lỗi HTTP, {len(to_seconds)} to_seconds, {len(statuses)} check_status")


# ---------------------------------------------------------------- requests.json

REQUEST_ARGS = [
    {},
    {"prompt": "  Gõ chữ giúp.  "},
    {"model": "gemini-3.5-transcribe", "word_timestamps": True},
    {"model": "gemini-3.5-transcribe", "diarize": True, "word_timestamps": True},
    {"model": "gemini-3.5-transcribe", "diarize": True},
    {"model": "gemini-3.8-flash", "prompt": "P", "document": ["uri://b", "text/plain"]},
    {"model": "gemini-3.8-flash", "prompt": "P", "attached_text": " 00:00.0 - 00:05.0  A \n"},
    {"model": "gemini-3.8-flash", "prompt": "   ", "diarize": True},
]

RESPONSES = {
    "done": {"id": "int_1", "status": "completed", "steps": [
        {"type": "user_input", "content": [{"type": "text", "text": "KHONG LAY"}]},
        {"type": "model_output", "content": [{"type": "text", "text": "Xin chào "}, {"type": "text", "text": "các bạn."}]},
    ]},
    "with_words": {"id": "int_2", "status": "completed", "steps": [
        {"type": "model_output", "content": [{
            "type": "text", "text": "Hello world",
            "annotations": [
                {"type": "word_info", "text": "Hello", "speaker": "spk_1", "start_offset": "0.100s", "end_offset": "0.450s"},
                {"type": "word_info", "text": "world", "speaker": "spk_2", "start_offset": "0.500s", "end_offset": "0.850s"},
                {"type": "other", "text": "bo qua"},
                {"type": "word_info", "text": "  ", "start_offset": "1s"},
                {"type": "word_info", "text": " tiếp ", "start_offset": "2.0004s"},
                {"type": "word_info", "text": "lùi", "start_offset": {"seconds": 3, "nanos": 250000000},
                 "end_offset": "1s"},
                {"type": "word_info", "text": "x", "start_offset": "0.0005s", "end_offset": "0.0015s"},
            ]}]},
        "not a step",
        {"type": "model_output", "content": ["bad", {"type": "image"}, {"type": "text", "text": ""}]},
    ], "usage": {"total_input_tokens": 4000, "total_tokens": 4900}},
    "output_text": {"output_text": "Trực tiếp", "steps": [
        {"type": "model_output", "content": [{"type": "text", "text": "khác"}]}]},
    "usage_input_tokens": {"usage": {"input_tokens": "12"}},
    "usage_metadata": {"usageMetadata": {"promptTokenCount": 12.7}},
    "usage_bad": {"usage": {"total_input_tokens": "12.5", "input_tokens": 0, "promptTokenCount": None}},
    "usage_empty": {"usage": {}, "usageMetadata": {"promptTokenCount": 33}},
    "empty": {},
}

MIME_PATHS = ["doan_001.flac", "a/B.WAV", r"C:\x\y.m4a", "x.mp3", "x.ogg", "x.aac", "nguoi_noi.txt", "a.json",
              "noext", ".flac", "..flac", "a.tar.gz", "dir.with.dot/file", "x.opus"]
# Key mẫu cố ý không bắt đầu bằng "AIza": lúc đóng stage, `git grep AIza` chỉ được bắt key thật.
KEYS = [None, "", "abc", "0123456789", "TestKey-1234567890abcdefXYZ", "12345678901"]
MODELS = ["gemini-3.5-transcribe", "Gemini-3.5-TRANSCRIBE", "gemini-3.8-flash", "", None, "my-transcriber"]


def gen_requests(out) -> None:
    cases = []
    for model, mode, langs, vocab in itertools.product(
            ["gemini-3.5-transcribe", "gemini-3.5-flash-lite"], ["smart", "verbatim"],
            [(), ("vi-VN", "en-US")], [(), ("SVM", "Data Mining")]):
        cfg = make_cfg(model=model, transcribe_mode=mode, languages=langs, vocabulary=vocab,
                       store_on_server=(mode == "verbatim" and bool(langs)))
        for args in REQUEST_ARGS:
            kw = dict(args)
            if "document" in kw:
                kw["document"] = tuple(kw["document"])
            request = ga.build_request(cfg, "https://g/files/f1", "audio/flac", **kw)
            cases.append({"cfg": cfg_json(cfg), "args": args, "expected": request})
    responses = []
    for name, response in RESPONSES.items():
        responses.append({"name": name, "response": response, "text": ga.extract_text(response),
                          "words": ga.extract_words(response), "input_tokens": ga.parse_input_tokens(response)})
    _common.write_json(out / "requests.json", {
        "build_request": cases, "responses": responses,
        "guess_mime": [{"path": p, "expected": ga.guess_mime(p)} for p in MIME_PATHS],
        "mask_api_key": [{"key": k, "expected": ga.mask_api_key(k)} for k in KEYS],
        "is_transcribe_model": [{"model": m, "expected": ga.is_transcribe_model(m)} for m in MODELS],
    }, SCRIPT)
    print(f"requests.json: {len(cases)} yêu cầu, {len(responses)} phản hồi")


# ---------------------------------------------------------------- pacific.json

def gen_pacific(out) -> None:
    moments = set()
    for year in range(2025, 2031):
        start = datetime.combine(quota._nth_sunday(year, 3, 2), datetime.min.time()) + timedelta(hours=10)
        end = datetime.combine(quota._nth_sunday(year, 11, 1), datetime.min.time()) + timedelta(hours=9)
        for switch in (start, end):
            base = switch.replace(tzinfo=UTC).timestamp()
            for d in (-86400, -25 * 3600, -3601, -3600, -1, -0.5, 0, 0.25, 1, 3599, 3600, 7200, 23 * 3600, 86400):
                moments.add(base + d)
            # Quanh 0 giờ Thái Bình Dương của ngày đổi giờ và ngày kế (07:00 / 08:00 UTC).
            day = switch.replace(hour=0, minute=0, second=0, tzinfo=UTC).timestamp()
            for h in (7, 8, 31, 32):
                for d in (-1, 0, 1):
                    moments.add(day + h * 3600 + d)
    rng = random.Random(20261111)
    lo, hi = epoch(2025, 1, 1), epoch(2031, 1, 1)
    for _ in range(60):
        moments.add(round(rng.uniform(lo, hi), 3))
    points = [{"epoch": t, "date": quota.pacific_date(t), "next_reset": quota.next_daily_reset(t)}
              for t in sorted(moments)]
    countdown = [{"sec": s, "expected": quota.format_countdown(s)}
                 for s in (0, -5, 0.5, 1.5, 2.5, 59.4, 59.5, 3600, 3 * 3600 + 5 * 60 + 9.4, 86399.6, 100 * 3600)]
    _common.write_json(out / "pacific.json", {"points": points, "countdown": countdown}, SCRIPT)
    print(f"pacific.json: {len(points)} mốc")


# ---------------------------------------------------------------- tracker.json

TRACKER_CFG = make_cfg(token_rate_limit=10000, model_quotas={"a": (0, 5, 0), "m": (250000, 15, 500)})

TRACKER_STEPS = [
    {"op": "record_request", "model": "m", "tokens": 15000},
    {"op": "advance", "sec": 30.04},
    {"op": "record_request", "model": "m", "tokens": 15000},
    {"op": "record_request", "model": "t", "tokens": -5},
    {"op": "learn_quota", "model": "m", "field": "rpd", "value": 25},
    {"op": "learn_quota", "model": "m", "field": "rpd", "value": 25},
    {"op": "learn_quota", "model": "b", "field": "tpm", "value": 250000},
    {"op": "learn_quota", "model": "b", "field": "xyz", "value": 3},
    {"op": "learn_quota", "model": "b", "field": "rpm", "value": 0},
    {"op": "record_error", "model": "m", "kind": "qua_tai", "message": "HTTP 500: high demand " + "x" * 600},
    {"op": "record_error", "model": "m", "kind": "qua_tai", "message": "HTTP 503"},
    {"op": "lock", "model": "m", "until": "reset", "reason": "han_muc_ngay", "details": "429"},
    {"op": "lock", "model": "m", "until_in": 600, "reason": "qua_tai", "details": "không rút ngắn"},
    {"op": "lock", "model": "x", "until": None, "reason": "khong_ton_tai", "details": "404"},
    {"op": "lock", "model": "o", "until_in": 600, "reason": "qua_tai", "details": "HTTP 500"},
    {"op": "read"},
    {"op": "reopen"},
    {"op": "read"},
    {"op": "advance", "sec": 601},
    {"op": "read"},
    {"op": "lock", "model": "q", "until_in": 60, "reason": "han_muc_phut", "details": "429 phút"},
    {"op": "advance", "sec": 61},
    {"op": "lock", "model": "q", "until_in": 60, "reason": "han_muc_phut", "details": "429 phút lần 2"},
    {"op": "read"},
    {"op": "unlock", "model": "q"},
    {"op": "unlock", "model": "q"},
    {"op": "record_model_switch", "from": "m", "to": "a", "reason": "han_muc_ngay"},
    {"op": "set_waiting", "model": "a", "reason": "tpm", "in": 39, "details": "10000"},
    {"op": "read"},
    {"op": "clear_waiting"},
    {"op": "advance_to_reset", "plus": 1},
    {"op": "read"},
    {"op": "record_request", "model": "m", "tokens": 7},
    {"op": "unlock", "model": "x"},
    {"op": "errors", "model": "e", "kind": "khac", "count": 305},
    {"op": "reset_counters", "model": "m"},
    {"op": "forget_quota", "model": "b"},
    {"op": "read"},
    {"op": "reopen"},
    {"op": "read"},
]

READ_MODELS = ["m", "t", "a", "b", "x", "o", "q", "e", "khong_co"]


class Clock:
    def __init__(self, t: float):
        self.t = t

    def __call__(self):
        return self.t


def tracker_reads(tracker: quota.QuotaTracker, clock: Clock) -> dict:
    snap = tracker.snapshot()
    return {
        "clock": clock(),
        "models": {m: {"lock_status": list(tracker.lock_status(m)), "requests_today": tracker.requests_today(m),
                       "recent_minute_locks": tracker.recent_minute_locks(m),
                       "learned": tracker.learned_limits(m),
                       "limit": list(tracker.limit(TRACKER_CFG, m).__dict__.values())} for m in READ_MODELS},
        "locked": list(tracker.revision()[1]),
        "waiting": snap["dang_cho"],
        "event_count": len(snap["su_kien"]),
    }


def run_tracker(start: float) -> dict:
    clock = Clock(start)
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, quota.FILENAME)
        tracker = quota.QuotaTracker(path, clock=clock)
        reads = []
        for step in TRACKER_STEPS:
            op = step["op"]
            if op == "advance":
                clock.t += step["sec"]
            elif op == "advance_to_reset":
                clock.t = quota.next_daily_reset(clock()) + step["plus"]
            elif op == "record_request":
                tracker.record_request(step["model"], step["tokens"])
            elif op == "record_error":
                tracker.record_error(step["model"], step["kind"], step["message"])
            elif op == "errors":
                for i in range(step["count"]):
                    tracker.record_error(step["model"], step["kind"], f"lỗi {i}")
            elif op == "learn_quota":
                tracker.learn_quota(step["model"], step["field"], step["value"])
            elif op == "forget_quota":
                tracker.forget_quota(step.get("model"))
            elif op == "lock":
                if "until_in" in step:
                    until = clock() + step["until_in"]
                elif step["until"] == "reset":
                    until = quota.next_daily_reset(clock())
                else:
                    until = None
                tracker.lock(step["model"], until, step["reason"], step["details"])
            elif op == "unlock":
                tracker.unlock(step["model"])
            elif op == "record_model_switch":
                tracker.record_model_switch(step["from"], step["to"], step["reason"])
            elif op == "set_waiting":
                tracker.set_waiting(step["model"], step["reason"], clock() + step["in"], step["details"])
            elif op == "clear_waiting":
                tracker.clear_waiting()
            elif op == "reset_counters":
                tracker.reset_counters(step.get("model"))
            elif op == "reopen":
                tracker = quota.QuotaTracker(path, clock=clock)
            elif op == "read":
                reads.append(tracker_reads(tracker, clock))
            else:
                raise ValueError(op)
        with open(path, "r", encoding="utf-8") as f:
            final_file = json.load(f)
    return {"start": start, "reads": reads, "file": final_file}


def gen_tracker(out) -> None:
    # Hai điểm bắt đầu: giữa mùa hè, và ngay trước lần đổi giờ tháng 3 (ngày Thái Bình Dương chỉ có 23 giờ).
    runs = [run_tracker(epoch(2026, 9, 17, 16, 0)), run_tracker(epoch(2026, 3, 8, 6, 30))]
    _common.write_json(out / "tracker.json", {"cfg": cfg_json(TRACKER_CFG), "steps": TRACKER_STEPS, "runs": runs},
                       SCRIPT)
    print(f"tracker.json: {len(TRACKER_STEPS)} bước × {len(runs)} lần chạy")


# ---------------------------------------------------------------- config.json

QUOTA_SECTIONS = {
    "comments_and_case": "# chú thích\n; chú thích\nGemini-X = 1, 2, 3   # cuối dòng\n\ngemini-y=4,5\n"
                         "gemini-z = \ngemini-w = 7.9, -3, 1e3\n",
    "duplicate_last_wins": "a = 1, 1, 1\nb = 2, 2, 2\nkhac = 1 ;2\nmodel;x = 5\n",
    "empty": "",
}
QUOTA_SPECS = ["10000, 0, 25", "10000", "", " , 5", "1.9, 2.1, 3", "-5, 0, 7", "1, 2, 3, 4", "abc", "1, x"]


def gen_config(out) -> None:
    default_cfg = config.load_config(str(desktop / "config.default.txt"))
    sections = {}
    with tempfile.TemporaryDirectory() as d:
        for name, text in QUOTA_SECTIONS.items():
            p = os.path.join(d, name + ".txt")
            with open(p, "w", encoding="utf-8") as f:
                f.write("[QUOTAS]\n" + text)
            cp = config._read_parser(p)
            sections[name] = {"text": text, "expected": {m: list(config.read_quota_spec(m, v))
                                                          for m, v in cp.items("QUOTAS") if v is not None}}
    specs = []
    for s in QUOTA_SPECS:
        try:
            specs.append({"spec": s, "expected": list(config.read_quota_spec("m", s))})
        except ValueError:
            specs.append({"spec": s, "expected": None})

    chains = []
    fallback_lists = [config.DEFAULT_FALLBACK_MODELS, (),
                      ("gemini-3.5-transcribe", "gemini-3.8-flash", "gemini-3.8-flash", "", "gemini-3.5-flash-lite"),
                      ("my-transcribe-x", "gemini-3.5-transcribe", "my-model")]
    for model, method, enabled, auto, fallback in itertools.product(
            ["gemini-3.5-flash-lite", "gemini-3.5-transcribe", "gemini-3.8-flash", "my-model"],
            ["pyannote", "gemini", "hybrid"], [False, True], [True, False], fallback_lists):
        cfg = make_cfg(model=model, diarization_method=method, diarization_enabled=enabled, auto_switch_model=auto,
                       fallback_models=fallback, token_rate_limit=12345)
        chain = cfg.model_chain()
        chains.append({
            "cfg": cfg_json(cfg), "chain": chain, "constraint_ok": config.constraint_error(cfg) is None,
            "valid_methods": list(config.valid_diarization_methods(model)),
            "quota_for": {m: list(cfg.quota_for(m)) for m in chain + ["khong-khai-bao"]},
        })
    _common.write_json(out / "config.json", {
        "default_model_quotas": {m: list(q) for m, q in default_cfg.model_quotas.items()},
        "default_model": default_cfg.model,
        "default_fallback_models": list(default_cfg.fallback_models),
        "quota_sections": sections, "quota_specs": specs, "chains": chains,
    }, SCRIPT)
    print(f"config.json: {len(chains)} chuỗi model")


# ---------------------------------------------------------------- replay.json

OUT_OF_25 = (429, REAL_DAILY_429)
HIGH = (500, HIGH_DEMAND)
BLOCKED = (400, INPUT_BLOCKED)
TOKEN_429 = (429, "* Quota exceeded for metric: generativelanguage.googleapis.com/"
                  "generate_content_free_tier_input_token_count, limit: 10000, model: gemini-3.5-transcribe\n"
                  "Please retry in 20.5s.")
NOT_FOUND = (404, "models/gemini-9-flash is not found for API version v1beta, or is not supported.")

BASE = dict(model="gemini-3.5-transcribe", token_rate_limit=0, fallback_models=(
    "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.5-transcribe"))

REPLAYS = [
    # TestReplayRealLog.test_daily_quota_then_high_demand_moves_to_flash_lite
    ("daily_quota_then_high_demand", dict(BASE, fallback_models=("gemini-3.8-flash", "gemini-3.5-flash-lite")),
     {"gemini-3.5-transcribe": ["ok", OUT_OF_25], "gemini-3.8-flash": [HIGH], "gemini-3.5-flash-lite": ["ok"]}, 4),
    # TestReplayRealLog.test_input_blocked_does_not_stop_file
    ("input_blocked", dict(BASE, model="gemini-3.8-flash", fallback_models=("gemini-3.5-flash-lite",)),
     {"gemini-3.8-flash": ["ok", BLOCKED, "ok"], "gemini-3.5-flash-lite": ["ok"]}, 4),
    # 429 theo phút (token) chỉ một model: chờ đúng retry + 2 giây rồi gửi lại, học tpm.
    ("minute_token_quota_retry", dict(BASE, diarization_enabled=True, diarization_method="gemini"),
     {"gemini-3.5-transcribe": ["ok", TOKEN_429, "ok"]}, 3),
    # 404 model không tồn tại: khoá tới khi mở tay, đổi model.
    ("model_not_found", dict(BASE, model="gemini-9-flash", fallback_models=("gemini-3.5-flash-lite",)),
     {"gemini-9-flash": [NOT_FOUND], "gemini-3.5-flash-lite": ["ok"]}, 2),
    # Hạn mức ngày khai báo 2 yêu cầu: gửi đủ thì tự khoá trước khi bị 429.
    ("reaching_daily_quota", dict(BASE, model_quotas={"gemini-3.5-transcribe": (0, 0, 2)}),
     {"gemini-3.5-transcribe": ["ok"], "gemini-3.5-flash": ["ok"]}, 3),
]

REPLAY_START = epoch(2026, 9, 17, 16, 0)
PROCESS_SEC = 15.0      # mỗi yêu cầu Interactions, Google xử lý mất 15 giây (đồng hồ giả)


class ReplayServer:
    """Giả lập Gemini API cho Client THẬT, như tests/test_transcribe.py: ServerByModel, ghi lại mọi yêu cầu."""

    def __init__(self, script: dict, clock: Clock):
        self.script = {m: list(items) for m, items in script.items()}
        self.clock = clock
        self.requests = []

    def __call__(self, req, timeout=None):
        url, method = req.full_url, req.get_method()
        if url.endswith("/upload/v1beta/files"):
            self.requests.append("upload_start")
            h = email.message.Message()
            h["X-Goog-Upload-URL"] = "https://upload.example/abc"
            return FakeResponse({}, h)
        if url == "https://upload.example/abc":
            self.requests.append("upload_finalize")
            return FakeResponse({"file": {"name": "files/f1", "uri": "https://g/files/f1", "mimeType": "audio/flac",
                                          "state": "ACTIVE"}})
        if url.endswith("/interactions"):
            model = json.loads(req.data)["model"]
            self.requests.append("interaction " + model)
            self.clock.t += PROCESS_SEC
            items = self.script[model]
            result = items.pop(0) if len(items) > 1 else items[0]
            if isinstance(result, tuple):
                code, message = result
                raise urllib.error.HTTPError(url, code, "loi", email.message.Message(), io.BytesIO(json.dumps(
                    {"error": {"code": code, "message": message}}).encode()))
            return FakeResponse({"status": "completed", "steps": [{"type": "model_output", "content": [
                {"type": "text", "text": f"{model}: {result}"}]}], "usage": {"total_input_tokens": 3000}})
        if method == "DELETE":
            self.requests.append("delete " + url.split("/v1beta/", 1)[1])
            return FakeResponse({})
        raise AssertionError(f"Yêu cầu không mong đợi: {method} {url}")


class FakeResponse:
    def __init__(self, body, hdr=None):
        self.status = 200
        self.headers = hdr or email.message.Message()
        self._body = json.dumps(body).encode()

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def run_replay(name: str, cfg_kw: dict, script: dict, chunks: int) -> dict:
    cfg = make_cfg(**cfg_kw)
    clock = Clock(REPLAY_START)
    tracker = quota.QuotaTracker(None, clock=clock)
    ga._LIMITERS_BY_MODEL.clear()
    for m in set(cfg.model_chain()) | set(script):
        ga._LIMITERS_BY_MODEL[m] = ga.RateLimiter(clock=clock)
    server = ReplayServer(script, clock)
    client = ga.Client("KHONG_PHAI_KEY", opener=server, tracker=tracker)
    waits = []

    def fake_sleep(sec, should_stop=None):
        waits.append(round(sec, 3))
        clock.t += sec

    results, error = [], None
    with tempfile.TemporaryDirectory() as d:
        audio = os.path.join(d, "doan_001.flac")
        with open(audio, "wb") as f:
            f.write(b"fLaC" + b"\0" * 100)
        with mock.patch.object(ga, "sleep", side_effect=fake_sleep):
            dispatcher = transcribe.ChunkDispatcher(cfg, client)
            for _ in range(chunks):
                try:
                    response, model = dispatcher.send(
                        audio, 15.0, lambda m: (None if config.is_transcribe_model(m) else "PROMPT", {}))
                except ga.GoogleAIError as e:
                    error = {"kind": e.kind, "stop_queue": e.stop_queue}
                    break
                results.append({"model": model, "text": ga.extract_text(response)})
    snap = tracker.snapshot()
    return {"name": name, "cfg": cfg_json(cfg), "script": {m: [list(x) if isinstance(x, tuple) else x for x in items]
                                                           for m, items in script.items()},
            "chunks": chunks, "requests": server.requests, "results": results, "error": error, "waits": waits,
            "final_model": dispatcher.current_model, "clock_end": clock(),
            "tracker": {"ngay": snap["ngay"], "model": snap["model"], "su_kien": snap["su_kien"]}}


def gen_replay(out) -> None:
    runs = [run_replay(*r) for r in REPLAYS]
    _common.write_json(out / "replay.json", {"start": REPLAY_START, "process_sec": PROCESS_SEC, "runs": runs}, SCRIPT)
    print(f"replay.json: {len(runs)} kịch bản")


def main() -> None:
    # Log của desktop (WARNING khoá model…) không cần in ra khi tạo golden.
    import logging
    logging.getLogger("Guzz").setLevel(logging.CRITICAL)
    out = _common.output_dir("gemini")
    gen_errors(out)
    gen_requests(out)
    gen_pacific(out)
    gen_tracker(out)
    gen_config(out)
    gen_replay(out)


if __name__ == "__main__":
    main()
