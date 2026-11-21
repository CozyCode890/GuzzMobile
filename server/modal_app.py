"""Vỏ Modal của server xử lý âm thanh Guzz (Stage 11, D140–D148).

Chạy `guzz-server.jar` (Kotlin, cùng code với điện thoại) trong một container có ffmpeg + JRE 21 + model sherpa-onnx.
API (FastAPI, proxy auth của Modal: header `Authorization: Bearer <Modal-Key>.<Modal-Secret>`):

    GET    /health                → {"protocol": 1, "server": "<phiên bản jar>"}
    POST   /jobs?request=<JSON>   thân = tệp âm thanh, trả JobStatus (queued, có id)
    GET    /jobs/{id}             JobStatus
    GET    /jobs/{id}/result      tar các tệp kết quả (khi state = done)
    DELETE /jobs/{id}             huỷ nếu đang chạy, xoá mọi tệp của job

Lưu tệp (D147): tệp gốc xoá ngay khi job xong / lỗi; kết quả xoá khi điện thoại gọi DELETE; hàm `cleanup` chạy mỗi ngày
xoá mọi job cũ hơn 24 giờ. Log không ghi tên tệp hay nội dung.

Deploy (build jar trước):

    .\\gradlew :server:serverJar
    modal deploy server/modal_app.py
"""

import json
import os
import shutil
import subprocess
import tarfile
import threading
import time
import uuid
from pathlib import Path

import modal

APP_NAME = "guzz-server"
HERE = Path(__file__).resolve().parent
JAR_LOCAL = HERE / "build" / "server" / "guzz-server.jar"
DEPS_LOCAL = HERE.parent / "gradle" / "guzz-deps.txt"
# D149: model embedding lấy đúng tệp đã kiểm trong cache guzz-deps của máy deploy (bản trên GitHub đã bị thay 2026-11-19).
DEPS_DIR_LOCAL = Path(os.environ.get("GUZZ_DEPS_DIR", Path.home() / ".gradle" / "guzz-deps"))
EMB_LOCAL = DEPS_DIR_LOCAL / "models" / "campplus_zh_en_advanced.onnx"
JAR = "/opt/guzz/guzz-server.jar"
DEPS = "/opt/guzz/guzz-deps.txt"
MODELS = "/opt/guzz/models"
JOBS = "/jobs"
MAX_AGE_S = 24 * 3600
PROTOCOL = 1

# Hàm xử lý: số nhân / RAM theo số đo A3 + G3 (D2). sherpa-onnx dùng số luồng = số vCPU (1 nhân vật lý = 2 vCPU).
PROCESS_CPU = float(os.environ.get("GUZZ_CPU", "4"))
PROCESS_MEMORY_MB = int(os.environ.get("GUZZ_MEMORY_MB", "4096"))


def fetch_models():
    """Chạy lúc build image: tải segmentation theo gradle/guzz-deps.txt, kiểm SHA-256 cả hai model, sai thì dừng build."""
    import hashlib
    import urllib.request

    entries = {}
    for line in Path(DEPS).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            name, sha, path, src = line.split()
            entries[name] = (sha, path, src)

    def sha256(p):
        h = hashlib.sha256()
        with open(p, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        return h.hexdigest()

    def fetch(name):
        sha, _, url = entries[name]
        out = Path("/tmp") / Path(url).name
        urllib.request.urlretrieve(url, out)
        got = sha256(out)
        if got != sha:
            raise RuntimeError(f"SHA-256 sai: {name}: {got} != {sha}")
        return out

    Path(MODELS).mkdir(parents=True, exist_ok=True)
    seg_tar = fetch("seg-tar")
    member = entries["seg"][2].split(":", 2)[2]
    with tarfile.open(seg_tar, "r:bz2") as t:
        f = t.extractfile(member)
        Path(MODELS, "segmentation-3.0.onnx").write_bytes(f.read())
    if sha256(Path(MODELS, "segmentation-3.0.onnx")) != entries["seg"][0]:
        raise RuntimeError("SHA-256 sai: seg")
    emb = Path(MODELS, "campplus_zh_en_advanced.onnx")  # chép vào image từ cache của máy deploy (D149)
    if sha256(emb) != entries["emb"][0]:
        raise RuntimeError("SHA-256 sai: emb")
    seg_tar.unlink()


image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("ffmpeg", "openjdk-21-jre-headless")
    .pip_install("fastapi[standard]==0.121.3")
    .add_local_file(DEPS_LOCAL, DEPS, copy=True)
    .add_local_file(EMB_LOCAL, MODELS + "/campplus_zh_en_advanced.onnx", copy=True)
    .run_function(fetch_models)
    .env({"GUZZ_MODELS": MODELS, "GUZZ_FFMPEG": "ffmpeg"})
    .add_local_file(JAR_LOCAL, JAR, copy=True)
)
app = modal.App(APP_NAME, image=image)
volume = modal.Volume.from_name("guzz-jobs", create_if_missing=True)
# Trạng thái job: hàm xử lý ghi, API đọc. Dict cập nhật ngay, không phải chờ commit Volume.
statuses = modal.Dict.from_name("guzz-job-status", create_if_missing=True)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def job_dir(job_id):
    return Path(JOBS, job_id)


@app.function(cpu=PROCESS_CPU, memory=PROCESS_MEMORY_MB, timeout=3600, volumes={JOBS: volume})
def process(job_id: str):
    """Một job: chép tệp từ Volume ra đĩa của container, chạy jar, đưa kết quả về Volume, xoá tệp gốc (D147)."""
    d = job_dir(job_id)
    volume.reload()
    meta = json.loads((d / "meta.json").read_text())
    work = Path("/tmp", job_id)
    work.mkdir(parents=True, exist_ok=True)
    src = work / meta["input"]
    shutil.copyfile(d / meta["input"], src)
    status_file = work / "status.json"
    vcpu = max(1, int(PROCESS_CPU * 2))
    cmd = [
        "java", "-Xmx2g", "-jar", JAR, "process", "--in", str(src), "--request", str(d / "request.json"),
        "--out", str(work / "out"), "--status", str(status_file), "--models", MODELS, "--threads", str(vcpu),
    ]
    log(f"job {job_id}: bắt đầu, {src.stat().st_size} byte, {vcpu} luồng")
    t0 = time.time()
    proc = subprocess.Popen(cmd)
    stop = threading.Event()

    def pump():
        # Đưa status.json của jar lên Dict mỗi 2 s.
        last = None
        while not stop.wait(2):
            try:
                text = status_file.read_text()
            except OSError:
                continue
            if text != last:
                last = text
                st = json.loads(text)
                st["id"] = job_id
                statuses[job_id] = st

    t = threading.Thread(target=pump, daemon=True)
    t.start()
    try:
        code = proc.wait()
    finally:
        if proc.poll() is None:
            proc.terminate()  # bị huỷ (DELETE): jar dừng ở điểm kiểm tiếp theo
            try:
                proc.wait(25)
            except subprocess.TimeoutExpired:
                proc.kill()
        stop.set()
        t.join()
    st = json.loads(status_file.read_text()) if status_file.exists() else {
        "state": "failed", "error": "internal", "message": f"jar thoát mã {code}",
    }
    st["id"] = job_id
    st["protocol"] = PROTOCOL
    if st.get("state") == "done":
        shutil.copytree(work / "out", d / "out", dirs_exist_ok=True)
    (d / meta["input"]).unlink(missing_ok=True)
    (d / "status.json").write_text(json.dumps(st))
    volume.commit()
    statuses[job_id] = st
    shutil.rmtree(work, ignore_errors=True)
    log(f"job {job_id}: {st.get('state')} {st.get('error') or ''} sau {time.time() - t0:.1f} s, mã {code}")


@app.function(schedule=modal.Period(days=1), volumes={JOBS: volume}, timeout=600)
def cleanup(max_age_s: int = MAX_AGE_S):
    """Xoá job cũ hơn 24 giờ (D147), tính theo `created` trong meta.json (thiếu thì theo giờ sửa thư mục)."""
    volume.reload()
    now = time.time()
    removed = 0
    root = Path(JOBS)
    for d in root.iterdir() if root.exists() else []:
        try:
            created = json.loads((d / "meta.json").read_text())["created"]
        except Exception:
            created = d.stat().st_mtime
        if now - created > max_age_s:
            shutil.rmtree(d, ignore_errors=True)
            statuses.pop(d.name, None)
            removed += 1
    volume.commit()
    log(f"cleanup: xoá {removed} job")
    return removed


@app.function(cpu=0.25, memory=512, volumes={JOBS: volume}, timeout=900)
@modal.concurrent(max_inputs=8)
@modal.asgi_app(requires_proxy_auth=True)
def web():
    from fastapi import FastAPI, HTTPException, Query, Request
    from fastapi.responses import JSONResponse, StreamingResponse

    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    version = {}

    @api.get("/health")
    def health():
        if "server" not in version:
            out = subprocess.run(["java", "-jar", JAR, "version"], capture_output=True, text=True)
            info = json.loads(out.stdout.splitlines()[0])
            version.update(info)
        return {"protocol": PROTOCOL, "server": version["server"]}

    def status_of(job_id):
        st = statuses.get(job_id)
        if st is None:
            volume.reload()
            f = job_dir(job_id) / "status.json"
            if not f.exists():
                if (job_dir(job_id) / "meta.json").exists():
                    return {"protocol": PROTOCOL, "id": job_id, "state": "queued", "fraction": 0.0}
                raise HTTPException(404, "không có job này")
            st = json.loads(f.read_text())
        return st

    @api.post("/jobs")
    async def create(request: Request, req: str = Query(alias="request")):
        try:
            body = json.loads(req)
        except ValueError:
            raise HTTPException(400, "request không phải JSON")
        if body.get("protocol") != PROTOCOL:
            return JSONResponse(
                {"protocol": PROTOCOL, "state": "failed", "error": "bad_request", "message": "khác phiên bản giao thức"},
                status_code=409,
            )
        job_id = uuid.uuid4().hex[:16]
        d = job_dir(job_id)
        d.mkdir(parents=True)
        ext = Path(body.get("fileName", "")).suffix.lower()[:8] or ".bin"
        name = "input" + ext
        n = 0
        with open(d / name, "wb") as f:
            async for chunk in request.stream():
                n += len(chunk)
                f.write(chunk)
        if n == 0:
            shutil.rmtree(d, ignore_errors=True)
            raise HTTPException(400, "tệp rỗng")
        (d / "request.json").write_text(json.dumps(body))
        (d / "meta.json").write_text(json.dumps({"created": time.time(), "input": name, "bytes": n}))
        volume.commit()
        st = {"protocol": PROTOCOL, "id": job_id, "state": "queued", "fraction": 0.0}
        statuses[job_id] = st
        call = process.spawn(job_id)
        meta = json.loads((d / "meta.json").read_text())
        meta["call"] = call.object_id
        (d / "meta.json").write_text(json.dumps(meta))
        volume.commit()
        log(f"job {job_id}: nhận {n} byte")
        return st

    @api.get("/jobs/{job_id}")
    def get(job_id: str):
        return status_of(job_id)

    @api.get("/jobs/{job_id}/result")
    def result(job_id: str):
        st = status_of(job_id)
        if st.get("state") != "done":
            raise HTTPException(409, "job chưa xong")
        volume.reload()
        out = job_dir(job_id) / "out"
        names = [f["name"] for f in st["result"]["files"]]
        if not all((out / n).is_file() for n in names):
            raise HTTPException(410, "kết quả không còn")

        def gen():
            # tar không nén (FLAC đã nén), ghi theo từng khối để không giữ cả tệp trong RAM.
            r, w = os.pipe()
            def writer():
                with os.fdopen(w, "wb") as fw, tarfile.open(fileobj=fw, mode="w|") as t:
                    for n in names:
                        t.add(out / n, arcname=n)
            th = threading.Thread(target=writer, daemon=True)
            th.start()
            with os.fdopen(r, "rb") as fr:
                for b in iter(lambda: fr.read(1 << 16), b""):
                    yield b
            th.join()

        return StreamingResponse(gen(), media_type="application/x-tar")

    @api.delete("/jobs/{job_id}")
    def delete(job_id: str):
        volume.reload()
        d = job_dir(job_id)
        if not d.exists() and statuses.get(job_id) is None:
            raise HTTPException(404, "không có job này")
        st = statuses.get(job_id) or {}
        if st.get("state") in (None, "queued", "running"):
            try:
                call_id = json.loads((d / "meta.json").read_text()).get("call")
                if call_id:
                    modal.FunctionCall.from_id(call_id).cancel()
            except Exception as e:
                log(f"job {job_id}: huỷ lỗi {type(e).__name__}")
        shutil.rmtree(d, ignore_errors=True)
        volume.commit()
        statuses.pop(job_id, None)
        log(f"job {job_id}: đã xoá")
        return {"deleted": job_id}

    return api
