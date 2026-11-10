"""
Tệp thử tổng hợp cho test trên máy của :core:media (bước C4)  →  testdata/media/

Sinh bằng ffmpeg của desktop từ nguồn lavfi (sin, khung màu), không có bản ghi thật. Mỗi tệp dài 3 giây, vài chục KB.
Cờ bitexact và bỏ metadata để chạy lại ra đúng từng byte (git diff trống).

- tone_aac_stereo_44k.m4a : AAC-LC stereo 44,1 kHz, sin 1 kHz ở cả hai kênh
- tone_mp3_48k.mp3        : MP3 mono 48 kHz, sin 1 kHz
- tone_opus.ogg           : Opus trong OGG (đầu vào 48 kHz), sin 1 kHz
- tone_wav_22k.wav        : PCM 16-bit mono 22,05 kHz, sin 1 kHz
- two_tracks.mp4          : hình + 2 luồng AAC: luồng 0 sin 440 Hz, luồng 1 sin 880 Hz
- silent_video.mp4        : chỉ có hình, không có luồng âm thanh
- broken.m4a              : byte rác có đuôi .m4a (tệp hỏng)
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

OUT = _common.REPO_ROOT / "testdata" / "media"
EXACT = ["-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1"]


def ffmpeg() -> str:
    return str(_common.desktop_dir() / "runtime" / "ffmpeg" / "ffmpeg.exe")


def run(*args: str) -> None:
    cmd = [ffmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args]
    subprocess.run(cmd, check=True)


def sine(freq: int, rate: int, seconds: float = 3.0) -> list[str]:
    return ["-f", "lavfi", "-i", f"sine=frequency={freq}:sample_rate={rate}:duration={seconds}"]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    run(*sine(1000, 44100), "-ac", "2", "-c:a", "aac", "-b:a", "96k", *EXACT, str(OUT / "tone_aac_stereo_44k.m4a"))
    run(*sine(1000, 48000), "-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", *EXACT, str(OUT / "tone_mp3_48k.mp3"))
    run(*sine(1000, 48000), "-ac", "1", "-c:a", "libopus", "-b:a", "32k", *EXACT, str(OUT / "tone_opus.ogg"))
    run(*sine(1000, 22050), "-ac", "1", "-c:a", "pcm_s16le", *EXACT, str(OUT / "tone_wav_22k.wav"))
    video = ["-f", "lavfi", "-i", "color=c=blue:s=64x64:r=10:d=3"]
    run(*video, *sine(440, 48000), *sine(880, 48000), "-map", "0:v", "-map", "1:a", "-map", "2:a",
        "-c:v", "mpeg4", "-c:a", "aac", "-b:a", "64k", "-shortest", *EXACT, str(OUT / "two_tracks.mp4"))
    run(*video, "-c:v", "mpeg4", *EXACT, str(OUT / "silent_video.mp4"))
    # Byte rác tất định: không có header container nào khớp.
    (OUT / "broken.m4a").write_bytes(bytes((i * 37 + 11) % 251 for i in range(4096)))

    for p in sorted(OUT.iterdir()):
        print(f"{p.name}: {p.stat().st_size} byte")


if __name__ == "__main__":
    main()
