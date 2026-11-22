"""Thử server Guzz đã deploy trên Modal (Stage 11 D4) bằng tệp thử tổng hợp, so với guzz-server.jar chạy trên PC.

    python -X utf8 tools\\server\\smoke.py --url https://<workspace>--guzz-server-web.<vùng>.modal.run

Token: `GUZZ_SERVER_TOKEN` (`<Modal-Key>.<Modal-Secret>`) hoặc tệp `--token-file` (JSON của
`modal workspace proxy-tokens create --json`, mặc định D:\\GuzzMobileData\\keys\\modal-proxy.json).
Kiểm: không token → 401; /health; hai job (tone_wav_22k.wav cắt đoạn; 0-four-speakers-zh.wav tách giọng 4 người) ra
danh sách đoạn trùng, đoạn FLAC giải mã ra trùng từng mẫu, lượt nói trùng (±2 ms) với jar trên PC; DELETE rồi GET → 404.
Cần: java 21, `.\\gradlew :server:serverJar`, ffmpeg (GUZZ_FFMPEG hoặc của Guzz desktop), model ở guzz-deps.
"""

import argparse
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "server" / "build" / "server" / "guzz-server.jar"
DEPS = Path(os.environ.get("GUZZ_DEPS_DIR", Path.home() / ".gradle" / "guzz-deps"))
FFMPEG = os.environ.get("GUZZ_FFMPEG", r"D:\Guzz\runtime\ffmpeg\ffmpeg.exe")


def http(method, url, token, data=None, timeout=600):
    req = urllib.request.Request(url, data=data, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/octet-stream")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def request_json(name, speaker_key=None, clean_off=False, run_on_original=False):
    r = {"protocol": 1, "fileName": name, "sourceName": "/sdcard/" + name, "audioKey": "smoke-" + name,
         "speakerKey": speaker_key, "runOnOriginal": run_on_original}
    if clean_off:
        r["clean"] = {"denoise": False, "levelWindowSec": 0.0, "transientClampDb": 0.0}
    return r


PART = 50_000


def run_remote(base, token, path, req):
    # D150: tạo job, gửi theo phần nhỏ (50 KB, để tệp thử cũng thành vài phần), gửi lại một phần, rồi start.
    q = urllib.parse.urlencode({"request": json.dumps(req), "parts": "1"})
    code, body = http("POST", f"{base}/jobs?{q}", token, b"")
    assert code == 200, (code, body[:300])
    job = json.loads(body)["id"]
    assert json.loads(body)["state"] == "uploading", body
    data = path.read_bytes()
    parts = [data[i:i + PART] for i in range(0, len(data), PART)]
    for k, p in enumerate(parts):
        code, body = http("PUT", f"{base}/jobs/{job}/parts/{k}", token, p)
        assert code == 200, (k, code, body[:200])
    code, _ = http("PUT", f"{base}/jobs/{job}/parts/{len(parts) + 1}", token, b"x")
    assert code == 409, f"bỏ cách phần phải ra 409, ra {code}"
    code, body = http("PUT", f"{base}/jobs/{job}/parts/0", token, parts[0])
    assert code == 200 and json.loads(body)["received"] == len(data), body
    code, body = http("POST", f"{base}/jobs/{job}/start", token, b"")
    assert code == 200, (code, body[:200])
    t0 = time.time()
    while True:
        code, body = http("GET", f"{base}/jobs/{job}", token)
        st = json.loads(body)
        if st["state"] in ("done", "failed"):
            break
        if time.time() - t0 > 900:
            raise AssertionError("quá 15 phút")
        time.sleep(3)
    assert st["state"] == "done", st
    code, tar = http("GET", f"{base}/jobs/{job}/result", token)
    assert code == 200, code
    out = Path(tempfile.mkdtemp(prefix="smoke-remote-"))
    with tarfile.open(fileobj=io.BytesIO(tar)) as t:
        t.extractall(out, filter="data")
    print(f"  {path.name}: job {job} ({len(parts)} phần) xong sau {time.time() - t0:.1f} s, {len(st['result']['files'])} tệp")
    return job, st, out


def run_local(path, req):
    work = Path(tempfile.mkdtemp(prefix="smoke-local-"))
    (work / "req.json").write_text(json.dumps(req))
    env = dict(os.environ, GUZZ_FFMPEG=FFMPEG)
    # Jar gộp chỉ có JNI linux-x64 (cho Modal); trên Windows thêm JNI win-x64 của guzz-deps vào classpath.
    cp = str(JAR)
    if os.name == "nt":
        cp += os.pathsep + str(DEPS / "download" / "sherpa-onnx-native-lib-win-x64-2.0.1.jar")
    subprocess.run(
        ["java", "-cp", cp, "io.github.cozycode890.guzz.server.MainKt", "process", "--in", str(path), "--request", str(work / "req.json"), "--out",
         str(work / "out"), "--status", str(work / "status.json"), "--models", str(DEPS / "models")],
        check=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return work / "out"


def pcm(flac):
    return subprocess.run([FFMPEG, "-nostdin", "-loglevel", "error", "-i", str(flac), "-f", "s16le", "-"],
                          check=True, capture_output=True).stdout


def compare(remote, local, speaker_key=None):
    a = json.loads((remote / "danh_sach_doan.json").read_text(encoding="utf-8"))
    b = json.loads((local / "danh_sach_doan.json").read_text(encoding="utf-8"))
    assert a["doan"] == b["doan"] and a["ban_do_thoi_gian"] == b["ban_do_thoi_gian"], "danh sách đoạn khác"
    for d in a["doan"]:
        assert pcm(remote / d["file"]) == pcm(local / d["file"]), d["file"] + " khác mẫu"
    if speaker_key:
        f = f"nguoi_noi.{speaker_key}.json"
        ta = json.loads((remote / f).read_text(encoding="utf-8"))["doan"]
        tb = json.loads((local / f).read_text(encoding="utf-8"))["doan"]
        assert len(ta) == len(tb), (len(ta), len(tb))
        for x, y in zip(ta, tb):
            assert x["nguoi_noi"] == y["nguoi_noi"], (x, y)
            assert abs(x["bat_dau"] - y["bat_dau"]) <= 0.002 and abs(x["ket_thuc"] - y["ket_thuc"]) <= 0.002, (x, y)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token-file", default=r"D:\GuzzMobileData\keys\modal-proxy.json")
    args = ap.parse_args()
    base = args.url.rstrip("/")
    token = os.environ.get("GUZZ_SERVER_TOKEN")
    if not token:
        t = json.loads(Path(args.token_file).read_text())
        token = f"{t['Modal-Key']}.{t['Modal-Secret']}"

    code, _ = http("GET", base + "/health", None)
    assert code == 401, f"không token phải ra 401, ra {code}"
    code, _ = http("GET", base + "/health", token.split(".")[0] + ".sai")
    assert code == 401, f"sai token phải ra 401, ra {code}"
    t0 = time.time()
    code, body = http("GET", base + "/health", token)
    assert code == 200, (code, body)
    print(f"health: {json.loads(body)} ({time.time() - t0:.1f} s)")

    cases = [
        (ROOT / "testdata" / "media" / "tone_wav_22k.wav", request_json("tone.wav"), None),
        (DEPS / "testwav" / "0-four-speakers-zh.wav",
         request_json("four.wav", "smoke", clean_off=True, run_on_original=True), "smoke"),
    ]
    for path, req, sk in cases:
        job, st, remote = run_remote(base, token, path, req)
        compare(remote, run_local(path, req), sk)
        code, _ = http("DELETE", f"{base}/jobs/{job}", token)
        assert code == 200, code
        code, _ = http("GET", f"{base}/jobs/{job}", token)
        assert code == 404, f"sau DELETE phải 404, ra {code}"
    print("OK")


if __name__ == "__main__":
    sys.exit(main())
