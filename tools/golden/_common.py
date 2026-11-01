"""
Dùng chung cho các script golden (gen_*.py).

Script golden chạy bằng chính Python của Guzz desktop, import thẳng module của desktop, rồi ghi đầu vào + kết quả vào
testdata/golden/ để test Kotlin so khớp. Xem README.md cùng thư mục.

Python embeddable của desktop có tệp ._pth nên KHÔNG tự thêm thư mục của script vào sys.path và bỏ qua PYTHONPATH.
Vì vậy mỗi gen_*.py tự sys.path.insert(0, <thư mục của nó>) trước khi `import _common`.
"""

from __future__ import annotations

import json
import os
import platform
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
GOLDEN_DIR = REPO_ROOT / "testdata" / "golden"
DEFAULT_DESKTOP_DIR = r"D:\Guzz"


def desktop_dir() -> Path:
    """Thư mục Guzz desktop: biến môi trường GUZZ_DESKTOP_DIR, mặc định D:\\Guzz."""
    d = Path(os.environ.get("GUZZ_DESKTOP_DIR") or DEFAULT_DESKTOP_DIR)
    if not (d / "chunker.py").is_file():
        raise SystemExit(f"Không thấy Guzz desktop ở {d}. Đặt biến môi trường GUZZ_DESKTOP_DIR.")
    return d


def use_desktop_modules() -> Path:
    """Cho phép `import chunker`, `import config`… từ desktop. Chỉ đọc, không ghi gì vào đó (chạy với -B)."""
    d = desktop_dir()
    if str(d) not in sys.path:
        sys.path.insert(0, str(d))
    return d


def output_dir(name: str) -> Path:
    """testdata/golden/<name>/, tạo nếu chưa có."""
    d = GOLDEN_DIR / name
    d.mkdir(parents=True, exist_ok=True)
    return d


# ---------------------------------------------------------------- WAV

def write_wav16(path: Path, y: np.ndarray, sr: int) -> None:
    """Ghi WAV PCM 16-bit mono bằng thư viện chuẩn `wave` (không cần ffmpeg). y là float, thang [-1, 1)."""
    pcm = np.clip(np.round(np.asarray(y, dtype=np.float64) * 32768.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def read_wav16(path: Path) -> tuple[np.ndarray, int]:
    """Đọc lại WAV 16-bit mono ra float32 = int16 / 32768, đúng như bản Kotlin đọc (Golden.readWav)."""
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise ValueError(f"{path}: cần WAV 16-bit mono")
        sr = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return (pcm.astype(np.float32) / np.float32(32768.0)), sr


# ---------------------------------------------------------------- JSON

def _git(desktop: Path, *args: str) -> str:
    # --no-optional-locks: `git status` không được ghi lại .git/index của desktop.
    out = subprocess.run(["git", "--no-optional-locks", "-C", str(desktop), *args],
                         capture_output=True, text=True, encoding="utf-8", check=True)
    return out.stdout.strip()


def meta(script: str) -> dict:
    """Nguồn gốc của dữ liệu: script nào, commit nào của desktop, Python / numpy bản nào. Không ghi giờ để tất định."""
    d = desktop_dir()
    try:
        commit = _git(d, "rev-parse", "HEAD")
        dirty = bool(_git(d, "status", "--porcelain"))
    except (OSError, subprocess.CalledProcessError):
        commit, dirty = "unknown", None
    return {
        "script": script,
        "guzz_desktop_commit": commit,
        # true: desktop có sửa đổi chưa commit lúc tạo dữ liệu, nên commit trên không tái tạo đúng được.
        "guzz_desktop_dirty": dirty,
        "python": platform.python_version(),
        "numpy": np.__version__,
    }


def write_json(path: Path, data: dict, script: str) -> None:
    """Ghi JSON UTF-8, thụt 2, khoá sắp xếp, kèm `meta`; xuống dòng LF để chạy lại thì git diff trống."""
    payload = {"meta": meta(script), **data}
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
