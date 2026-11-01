"""
Dùng chung cho các script spike trên PC (prepare_data.py, run_sherpa.py, evaluate.py…). Chỉ dùng thư viện chuẩn, vì
mỗi script chạy bằng một Python khác nhau: Python của desktop, Python có pyannote của desktop, hoặc venv sherpa-onnx.

Python embeddable của desktop có tệp ._pth nên KHÔNG tự thêm thư mục của script vào sys.path; mỗi script tự
sys.path.insert(0, <thư mục của nó>) trước khi `import _common`.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_DATA_DIR = r"D:\GuzzMobileData\stage-01"
DEFAULT_DESKTOP_DIR = r"D:\Guzz"
SAMPLE_RATE = 16000


def data_dir() -> Path:
    """Thư mục dữ liệu ngoài repo: biến môi trường GUZZ_SPIKE_DATA, mặc định D:\\GuzzMobileData\\stage-01."""
    d = Path(os.environ.get("GUZZ_SPIKE_DATA") or DEFAULT_DATA_DIR)
    d.mkdir(parents=True, exist_ok=True)
    return d


def desktop_dir() -> Path:
    d = Path(os.environ.get("GUZZ_DESKTOP_DIR") or DEFAULT_DESKTOP_DIR)
    if not (d / "speaker.py").is_file():
        raise SystemExit(f"Không thấy Guzz desktop ở {d}. Đặt biến môi trường GUZZ_DESKTOP_DIR.")
    return d


def use_desktop_modules() -> Path:
    """Cho phép `import speaker`, `import config`… từ desktop. Chỉ đọc; chạy Python với -B để không ghi __pycache__."""
    d = desktop_dir()
    if str(d) not in sys.path:
        sys.path.insert(0, str(d))
    return d


def ffmpeg() -> str:
    exe = desktop_dir() / "runtime" / "ffmpeg" / "ffmpeg.exe"
    if not exe.is_file():
        raise SystemExit(f"Không thấy {exe}")
    return str(exe)


def run_ffmpeg(args: list[str]) -> None:
    cmd = [ffmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError("ffmpeg lỗi: " + result.stderr.decode("utf-8", "replace").strip()[-800:])


_DURATION_RE = re.compile(r"Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)")


def ffmpeg_duration(path: Path) -> float | None:
    """Thời lượng đọc từ dòng "Duration:" của `ffmpeg -i` (không có ffprobe), như audio_io.get_audio_duration."""
    result = subprocess.run([ffmpeg(), "-hide_banner", "-nostdin", "-i", str(path)], capture_output=True)
    m = _DURATION_RE.search(result.stderr.decode("utf-8", "replace"))
    if not m:
        return None
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


def flac_duration(path: Path) -> float:
    """Thời lượng chính xác tới từng mẫu, đọc từ STREAMINFO của FLAC (không giải mã)."""
    with open(path, "rb") as f:
        head = f.read(4 + 4 + 34)
    if head[:4] != b"fLaC" or head[4] & 0x7F != 0:
        raise ValueError(f"{path.name}: không phải FLAC có STREAMINFO ở đầu")
    info = head[8:]
    sample_rate = (info[10] << 12) | (info[11] << 4) | (info[12] >> 4)
    total_samples = ((info[13] & 0x0F) << 32) | int.from_bytes(info[14:18], "big")
    return total_samples / sample_rate


def f32_duration(path: Path, sr: int = SAMPLE_RATE) -> float:
    return path.stat().st_size / 4 / sr


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 22):
            h.update(chunk)
    return h.hexdigest()


def read_json(path: Path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data) -> None:
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def recordings() -> dict:
    """$GUZZ_SPIKE_DATA/recordings.json (bước A2): mã bài → thư mục tạm của desktop và tệp gốc. Ngoài repo."""
    path = data_dir() / "recordings.json"
    if not path.is_file():
        raise SystemExit(f"Chưa có {path} (bước A2).")
    # PowerShell ghi UTF-8 có thể kèm BOM.
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def utf8_console() -> None:
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
