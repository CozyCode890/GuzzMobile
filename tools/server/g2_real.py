"""Stage 11 G2: chạy bài thật B1–B5 qua server Modal, tải kết quả về để so (tools/server/g2_compare.py).

    python -X utf8 tools\\server\\g2_real.py --url https://<workspace>--guzz-server-web.<vùng>.modal.run

Bài lấy ở $GUZZ_SPIKE_DATA/<mã>/goc.* (mặc định D:\\GuzzMobileData\\stage-01), kết quả ghi vào --out/<mã>/ cùng
`g2_jobs.json` (thời gian tải lên, server xử lý, tải về). Cấu hình như lần chạy sherpa-onnx trên PC của Stage 5
(S5B1, t 1,1, P 20): làm sạch / cắt đoạn mặc định, không đặt số người tối thiểu / tối đa. Gửi bài thật lên Modal: chỉ
chạy khi người dùng đã đồng ý (D148). Xong thì DELETE job.
"""

import argparse
import io
import json
import os
import subprocess
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SPIKE = Path(os.environ.get("GUZZ_SPIKE_DATA", r"D:\GuzzMobileData\stage-01"))


def http(method, url, token, data=None, timeout=1800):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/octet-stream")
        req.add_header("Content-Length", str(os.path.getsize(data.name)) if hasattr(data, "name") else str(len(data)))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def run_one(base, token, code, out_root, results, existing):
    src = next(p for p in (SPIKE / code).glob("goc.*") if p.suffix in (".m4a", ".aac", ".mp3", ".wav") and "16k" not in p.name)
    req = {"protocol": 1, "fileName": src.name, "sourceName": f"{code}/{src.name}", "audioKey": f"g2-{code}",
           "speakerKey": "g2", "bounds": {"min": 0, "max": 0}}
    t0 = time.time()
    job = existing.get(code)
    if not job:
        # Tải lên bằng curl: urllib gửi tệp lớn qua TLS hay bị ngắt "EOF occurred in violation of protocol" (G2),
        # cùng tệp đó curl gửi được.
        q = urllib.parse.urlencode({"request": json.dumps(req)})
        r = subprocess.run(
            ["curl", "-sS", "-H", f"Authorization: Bearer {token}", "-H", "Content-Type: application/octet-stream",
             "--data-binary", f"@{src}", f"{base}/jobs?{q}"], capture_output=True, text=True)
        try:
            job = json.loads(r.stdout)["id"]
        except Exception:
            results[code] = {"loi": f"POST lỗi: {r.stdout[:200]} {r.stderr[:200]}"}
            return
    t_up = time.time() - t0
    print(f"{code}: tải lên {src.stat().st_size / 1e6:.0f} MB xong sau {t_up:.0f} s, job {job}", flush=True)
    last = None
    while True:
        time.sleep(10)
        c, b = http("GET", f"{base}/jobs/{job}", token)
        st = json.loads(b)
        if st.get("state") in ("done", "failed"):
            break
        step = (st.get("step"), round(st.get("fraction", 0), 2))
        if step != last:
            last = step
            print(f"{code}: {st.get('state')} {step[0]} {step[1]:.0%}", flush=True)
    t_srv = time.time() - t0 - t_up
    if st["state"] != "done":
        results[code] = {"loi": st}
        http("DELETE", f"{base}/jobs/{job}", token)
        return
    t1 = time.time()
    c, tar = http("GET", f"{base}/jobs/{job}/result", token)
    out = out_root / code
    out.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(tar)) as t:
        t.extractall(out, filter="data")
    t_down = time.time() - t1
    http("DELETE", f"{base}/jobs/{job}", token)
    results[code] = {"job": job, "bytes_len": src.stat().st_size, "tai_len_s": round(t_up, 1),
                     "server_s": round(t_srv, 1), "tai_ve_s": round(t_down, 1), "ket_qua_mb": round(len(tar) / 1e6, 1)}
    print(f"{code}: xong, server {t_srv:.0f} s, tải về {len(tar) / 1e6:.0f} MB trong {t_down:.0f} s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--codes", default="B1,B2,B3,B4,B5")
    ap.add_argument("--out", default=r"D:\GuzzMobileData\stage-11\g2")
    ap.add_argument("--token-file", default=r"D:\GuzzMobileData\keys\modal-proxy.json")
    ap.add_argument("--job", action="append", default=[], help="<mã>=<id job đã tạo>: chờ job đó, không tải lên lại")
    args = ap.parse_args()
    t = json.loads(Path(args.token_file).read_text())
    token = f"{t['Modal-Key']}.{t['Modal-Secret']}"
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    results = {}
    def guarded(c):
        try:
            run_one(args.url.rstrip("/"), token, c, out, results, dict(j.split("=", 1) for j in args.job))
        except Exception as e:
            results[c] = {"loi": f"{type(e).__name__}: {e}"}
            print(f"{c}: lỗi {e}", flush=True)

    threads = [threading.Thread(target=guarded, args=(c,)) for c in args.codes.split(",")]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    (out / "g2_jobs.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(results, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
