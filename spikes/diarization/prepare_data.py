"""
Bước A3 của Stage 1: chép và tạo mọi dữ liệu đo vào $GUZZ_SPIKE_DATA (ngoài repo).

Chạy bằng Python của desktop, không ghi gì vào D:\\Guzz:
    D:\\Guzz\\runtime\\python\\python.exe -B -s spikes\\diarization\\prepare_data.py

Chạy lại nhiều lần được: tệp chép đã có và trùng SHA-256 với nguồn thì bỏ qua; tệp sinh ra (giải mã, cắt, mã hoá) đã có
và trùng SHA-256 ghi trong meta.json thì bỏ qua.

Kết quả:
    B1..B5/  sach.flac, danh_sach_doan.json, ref.json (pyannote của desktop), goc.<đuôi>, goc16k.wav, meta.json
    L1/      L1_10m.f32, L1_60m.f32, L1_120m.f32 (f32le 16 kHz mono, cắt từ đầu bài gốc), meta.json
    L2/      L2.f32 (cả bài gốc của B3), meta.json
    media/   L1.m4a, A1.aac, V1.mp4, L1.mp3, L1.ogg, sweep48k.wav, tones48k.wav, meta.json
    F1/      F1.f32 (phút 10–20 của sach.flac bài B1), meta.json
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import wave
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as c  # noqa: E402

import numpy as np  # noqa: E402

ACCURACY_CODES = ("B1", "B2", "B3", "B4", "B5")
# Ngưỡng chọn JSON pyannote khớp sach.flac (A3).
REF_DURATION_TOLERANCE = 0.5
F32_BYTES_PER_SEC = c.SAMPLE_RATE * 4


class Meta:
    """meta.json của một mã bài: thông tin chung + SHA-256 từng tệp. Ghi lại sau mỗi tệp để bị ngắt cũng không mất."""

    def __init__(self, folder: Path):
        self.path = folder / "meta.json"
        self.data = c.read_json(self.path) if self.path.is_file() else {}
        self.data.setdefault("tep", {})

    def known_sha(self, name: str) -> str | None:
        return (self.data["tep"].get(name) or {}).get("sha256")

    def record(self, path: Path, sha: str, duration: float | None) -> None:
        entry = {"sha256": sha, "bytes": path.stat().st_size}
        if duration is not None:
            entry["thoi_luong_giay"] = round(duration, 3)
        self.data["tep"][path.name] = entry
        self.save()

    def save(self) -> None:
        c.write_json(self.path, self.data)


def duration_of(path: Path) -> float | None:
    ext = path.suffix.lower()
    if ext == ".f32":
        return c.f32_duration(path)
    if ext == ".flac":
        return c.flac_duration(path)
    if ext == ".wav":
        with wave.open(str(path), "rb") as w:
            return w.getnframes() / w.getframerate()
    if ext == ".json":
        return None
    return c.ffmpeg_duration(path)


def ensure_copy(src: Path, dst: Path, meta: Meta) -> None:
    """Chép src → dst. Đã có và trùng SHA-256 với nguồn thì bỏ qua."""
    src_sha = c.sha256(src)
    if dst.is_file() and dst.stat().st_size == src.stat().st_size and c.sha256(dst) == src_sha:
        print(f"  đã có, khớp SHA-256: {dst.parent.name}/{dst.name}")
        if meta.known_sha(dst.name) != src_sha:
            meta.record(dst, src_sha, duration_of(dst))
        return
    print(f"  chép: {dst.parent.name}/{dst.name}")
    tmp = dst.with_name(dst.name + ".part")
    shutil.copyfile(src, tmp)
    os.replace(tmp, dst)
    meta.record(dst, src_sha, duration_of(dst))


def ensure_derived(dst: Path, meta: Meta, make) -> None:
    """Sinh dst bằng make(tệp_tạm). Đã có và trùng SHA-256 trong meta.json thì bỏ qua."""
    known = meta.known_sha(dst.name)
    if dst.is_file() and known and c.sha256(dst) == known:
        print(f"  đã có, khớp SHA-256: {dst.parent.name}/{dst.name}")
        return
    print(f"  tạo: {dst.parent.name}/{dst.name}")
    tmp = dst.with_name(dst.name + ".part")
    make(tmp)
    os.replace(tmp, dst)
    meta.record(dst, c.sha256(dst), duration_of(dst))


def ffmpeg_to(src: Path, fmt_args: list[str], pre_input: list[str] | None = None, post_input: list[str] | None = None):
    def make(tmp: Path):
        c.run_ffmpeg([*(pre_input or []), "-i", str(src), "-vn", *(post_input or []), *fmt_args, str(tmp)])
    return make


def f32_16k(src: Path, **kw):
    return ffmpeg_to(src, ["-ac", "1", "-ar", str(c.SAMPLE_RATE), "-f", "f32le"], **kw)


def prefix_of(src: Path, num_bytes: int):
    """Phần đầu của một tệp f32 đã có: L1_10m / L1_60m là phần đầu của L1_120m."""
    def make(tmp: Path):
        if src.stat().st_size < num_bytes:
            raise RuntimeError(f"{src.name} ngắn hơn {num_bytes} byte")
        with open(src, "rb") as fi, open(tmp, "wb") as fo:
            remaining = num_bytes
            while remaining:
                chunk = fi.read(min(remaining, 1 << 22))
                fo.write(chunk)
                remaining -= len(chunk)
    return make


def write_wav16(path: Path, y: np.ndarray, sr: int) -> None:
    pcm = np.clip(np.round(y * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def make_sweep(tmp: Path) -> None:
    """Quét sin tuyến tính 50 Hz → 23 kHz trong 60 s ở 48 kHz, biên độ 0,5 (−6 dBFS). Đo resampler (E4)."""
    sr, dur, f0, f1 = 48000, 60.0, 50.0, 23000.0
    t = np.arange(int(sr * dur)) / sr
    phase = 2 * np.pi * (f0 * t + (f1 - f0) / (2 * dur) * t * t)
    write_wav16(tmp, 0.5 * np.sin(phase), sr)


def make_tones(tmp: Path) -> None:
    """Tổng sin 1, 6, 10, 15 kHz (mỗi sin 0,2) trong 30 s ở 48 kHz. 10 và 15 kHz trên Nyquist 8 kHz nên phải bị lọc."""
    sr, dur = 48000, 30.0
    t = np.arange(int(sr * dur)) / sr
    y = sum(0.2 * np.sin(2 * np.pi * f * t) for f in (1000.0, 6000.0, 10000.0, 15000.0))
    write_wav16(tmp, y, sr)


def pick_ref_json(tmp_dir: Path, clean_duration: float) -> Path:
    """JSON pyannote của desktop có thoi_luong_giay khớp sach.flac (lệch ≤ 0,5 s)."""
    candidates = sorted(tmp_dir.glob("nguoi_noi.*.json"))
    matched = [p for p in candidates
               if abs(float(c.read_json(p).get("thoi_luong_giay") or -1) - clean_duration) <= REF_DURATION_TOLERANCE]
    if not matched:
        raise SystemExit(f"{tmp_dir.name}: không JSON pyannote nào khớp thời lượng sach.flac ({clean_duration:.2f} s)")
    if len(matched) > 1:
        print(f"  {len(matched)} JSON khớp thời lượng, lấy tệp mới nhất")
        matched.sort(key=lambda p: p.stat().st_mtime)
    return matched[-1]


def prepare_accuracy(code: str, rec: dict, root: Path) -> dict:
    out = root / code
    out.mkdir(exist_ok=True)
    meta = Meta(out)
    tmp_dir = Path(rec["thu_muc_tmp"])
    src = Path(rec["goc"])

    ensure_copy(tmp_dir / "sach.flac", out / "sach.flac", meta)
    ensure_copy(tmp_dir / "danh_sach_doan.json", out / "danh_sach_doan.json", meta)
    clean_duration = c.flac_duration(out / "sach.flac")
    ensure_copy(pick_ref_json(tmp_dir, clean_duration), out / "ref.json", meta)
    goc = out / f"goc{src.suffix.lower()}"
    ensure_copy(src, goc, meta)
    ensure_derived(out / "goc16k.wav", meta,
                   ffmpeg_to(goc, ["-ac", "1", "-ar", str(c.SAMPLE_RATE), "-c:a", "pcm_s16le", "-f", "wav"]))

    ref = c.read_json(out / "ref.json")
    segments = c.read_json(out / "danh_sach_doan.json")
    turns = ref.get("doan") or []
    meta.data.update({
        "ma": code,
        "thoi_luong_sach_giay": round(clean_duration, 3),
        "thoi_luong_goc_giay": segments.get("thoi_luong_goc"),
        "so_nguoi_desktop": len({t["nguoi_noi"] for t in turns}),
        "so_luot_desktop": len(turns),
        "model_desktop": ref.get("model"),
    })
    meta.save()
    return meta.data


def prepare_l1(rec: dict, root: Path) -> dict:
    out = root / "L1"
    out.mkdir(exist_ok=True)
    meta = Meta(out)
    src = Path(rec["goc"])
    full = out / "L1_120m.f32"
    ensure_derived(full, meta, f32_16k(src, post_input=["-t", "7200"]))
    for minutes in (10, 60):
        ensure_derived(out / f"L1_{minutes}m.f32", meta, prefix_of(full, minutes * 60 * F32_BYTES_PER_SEC))
    meta.data.update({"ma": "L1", "thoi_luong_goc_giay": rec.get("thoi_luong_goc"), "so_nguoi_desktop": 1})
    meta.save()
    return meta.data


def prepare_l2(rec: dict, root: Path) -> dict:
    out = root / "L2"
    out.mkdir(exist_ok=True)
    meta = Meta(out)
    ensure_derived(out / "L2.f32", meta, f32_16k(Path(rec["goc"])))
    meta.data.update({"ma": "L2", "thoi_luong_goc_giay": rec.get("thoi_luong_goc"), "so_nguoi_desktop": 5})
    meta.save()
    return meta.data


def prepare_media(recs: dict, root: Path) -> dict:
    out = root / "media"
    out.mkdir(exist_ok=True)
    meta = Meta(out)
    l1 = out / "L1.m4a"
    ensure_copy(Path(recs["L1"]["goc"]), l1, meta)
    ensure_copy(Path(recs["A1"]["goc"]), out / "A1.aac", meta)
    ensure_copy(Path(recs["V1"]["goc"]), out / "V1.mp4", meta)
    ensure_derived(out / "L1.mp3", meta, ffmpeg_to(l1, ["-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", "-f", "mp3"]))
    ensure_derived(out / "L1.ogg", meta, ffmpeg_to(l1, ["-ac", "1", "-c:a", "libopus", "-b:a", "32k", "-f", "ogg"]))
    ensure_derived(out / "sweep48k.wav", meta, make_sweep)
    ensure_derived(out / "tones48k.wav", meta, make_tones)
    meta.data.update({"ma": "media"})
    meta.save()
    return meta.data


def prepare_f1(root: Path) -> dict:
    out = root / "F1"
    out.mkdir(exist_ok=True)
    meta = Meta(out)
    ensure_derived(out / "F1.f32", meta, f32_16k(root / "B1" / "sach.flac", pre_input=["-ss", "600"],
                                                 post_input=["-t", "600"]))
    meta.data.update({"ma": "F1", "nguon": "B1/sach.flac, giây 600–1200"})
    meta.save()
    return meta.data


def main() -> None:
    c.utf8_console()
    parser = argparse.ArgumentParser(description="Chuẩn bị dữ liệu đo Stage 1 vào $GUZZ_SPIKE_DATA")
    parser.add_argument("--only", nargs="*", help="Chỉ làm các nhóm này: B1..B5 L1 L2 media F1")
    args = parser.parse_args()

    root = c.data_dir()
    recs = c.recordings()
    groups = args.only or [*ACCURACY_CODES, "L1", "L2", "media", "F1"]
    summary = []
    for g in groups:
        print(f"[{g}]")
        if g in ACCURACY_CODES:
            summary.append(prepare_accuracy(g, recs[g], root))
        elif g == "L1":
            prepare_l1(recs["L1"], root)
        elif g == "L2":
            prepare_l2(recs["L2"], root)
        elif g == "media":
            prepare_media(recs, root)
        elif g == "F1":
            prepare_f1(root)
        else:
            raise SystemExit(f"Nhóm không biết: {g}")

    if summary:
        print("\n| Mã | sạch (phút) | gốc (phút) | số người | số lượt |")
        print("| --- | --- | --- | --- | --- |")
        for m in summary:
            print(f"| {m['ma']} | {m['thoi_luong_sach_giay'] / 60:.1f} | {m['thoi_luong_goc_giay'] / 60:.1f} "
                  f"| {m['so_nguoi_desktop']} | {m['so_luot_desktop']} |")


if __name__ == "__main__":
    main()
