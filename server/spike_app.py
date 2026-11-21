"""Spike Stage 11 A3: đo những gì của Modal mà kế hoạch còn phụ thuộc, rồi `modal app stop guzz-spike`.

- image debian-slim + ffmpeg + JRE 21 có build được không, khởi động lạnh mất bao lâu;
- proxy auth: thiếu / sai token thì ra mã gì, header nào dùng được;
- body lớn nhất một yêu cầu nhận được, và tốc độ tải lên (thử 100 MB, 300 MB dữ liệu ngẫu nhiên, không phải bản ghi).

    modal deploy server/spike_app.py
"""

import hashlib
import subprocess
import time

import modal

image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("ffmpeg", "openjdk-21-jre-headless")
    .pip_install("fastapi[standard]==0.121.3")
)
app = modal.App("guzz-spike", image=image)
BOOT = time.time()


@app.function(cpu=1.0, memory=1024, timeout=900)
@modal.asgi_app(requires_proxy_auth=True)
def web():
    from fastapi import FastAPI, Request

    api = FastAPI()

    @api.get("/health")
    def health():
        def first_line(cmd):
            out = subprocess.run(cmd, capture_output=True, text=True)
            return (out.stdout or out.stderr).splitlines()[0]

        return {
            "ffmpeg": first_line(["ffmpeg", "-hide_banner", "-version"]),
            "java": first_line(["java", "-version"]),
            "uptime_s": round(time.time() - BOOT, 1),
        }

    @api.post("/upload")
    async def upload(request: Request):
        t0 = time.time()
        h = hashlib.sha256()
        n = 0
        with open("/tmp/up.bin", "wb") as f:
            async for chunk in request.stream():
                h.update(chunk)
                n += len(chunk)
                f.write(chunk)
        return {"bytes": n, "sha256": h.hexdigest(), "seconds": round(time.time() - t0, 2)}

    return api
