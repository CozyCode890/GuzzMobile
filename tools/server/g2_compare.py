"""Stage 11 G2: so kết quả server (tools/server/g2_real.py) với đường điện thoại trên B1–B5.

    D:\\Guzz\\runtime\\nguoi_noi\\python.exe -B -s tools\\server\\g2_compare.py [--out D:\\GuzzMobileData\\stage-11\\g2]

Tham chiếu (cùng dữ liệu, không chạy lại điện thoại):
- đoạn / mốc cắt: golden chuỗi làm sạch đủ của Stage 7 (`golden-stage-07/<mã>.json` + `.boosted.pcm`): Kotlin trên
  điện thoại đã khớp chuỗi này ≥ 86 dB (Stage 7), nên lệch so với nó là lệch so với điện thoại. Mốc cắt tính bằng mẫu,
  lệch ≤ 1 khối (50 ms = 800 mẫu); SNR của âm thanh sạch ghép từ các đoạn FLAC ≥ 50 dB.
  SNR tính theo khối 10 s, mỗi khối dò độ trễ ±64 mẫu: cắt lặng hai bên có thể lệch 1 ms ở vài khoảng (B5), làm cả
  phần sau lệch vài chục mẫu; chuyện đó đã nằm trong tiêu chí "mốc cắt lệch ≤ 1 khối".
- lượt nói: sherpa-onnx trên PC cùng cấu hình (Stage 5 S5B1, t 1,1, P 20), đã khớp điện thoại (Stage 5 D1). Bản đó chạy
  trên `sach.flac` cũ của desktop (Stage 1, cắt lặng khác), nên lượt nói của server đổi về âm thanh gốc (TimeMap của
  server) rồi sang trục của bản Stage 1 (TimeMap của `<mã>/danh_sach_doan.json`) trước khi tính DER (collar 0,5 s). DER
  này gồm cả khác biệt do âm thanh sạch khác nhau, không chỉ do server.
D29 so với desktop: chạy thêm spikes\\diarization\\evaluate.py --remap trên các tệp `<mã>__server.json` (mốc trên âm
thanh gốc) mà script này ghi ra.
"""

import argparse
import json
import os
import subprocess
from pathlib import Path

import sys

import numpy as np
from pyannote.core import Annotation, Segment
from pyannote.metrics.diarization import DiarizationErrorRate

SPIKE = Path(os.environ.get("GUZZ_SPIKE_DATA", r"D:\GuzzMobileData\stage-01"))
FFMPEG = os.environ.get("GUZZ_FFMPEG", r"D:\Guzz\runtime\ffmpeg\ffmpeg.exe")
PC_SHERPA = SPIKE / "out" / "pc" / "S5B1" / "campplus-zhen_shift0.1_t1.1_p20"
SR = 16000

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "spikes" / "diarization"))
import _common as c  # noqa: E402

c.use_desktop_modules()
import audio_processing  # noqa: E402

BLOCK = 10 * SR
MAX_LAG = 64


def block_snr(ref, got):
    """SNR từng khối 10 s, mỗi khối lấy độ trễ (±MAX_LAG mẫu) cho sai số nhỏ nhất. Trả về danh sách SNR (dB)."""
    out = []
    n = min(len(ref), len(got))
    for a in range(MAX_LAG, n - BLOCK - MAX_LAG, BLOCK):
        r = ref[a:a + BLOCK].astype(np.float64)
        sig = float(np.sum(r * r))
        best = None
        for lag in [0] + [k for k in range(-MAX_LAG, MAX_LAG + 1) if k != 0]:
            e = float(np.sum((r - got[a + lag:a + lag + BLOCK].astype(np.float64)) ** 2))
            best = e if best is None else min(best, e)
            if lag == 0 and e <= 1e-6 * sig + 1:
                break  # khớp ở độ trễ 0 (> 60 dB), khỏi dò
        out.append(10 * np.log10(max(sig, 1e-9) / max(best, 1e-9)))
    return np.array(out)


def decode(flac):
    out = subprocess.run([FFMPEG, "-nostdin", "-loglevel", "error", "-i", str(flac), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(out, dtype=np.int16)


def ann(turns):
    a = Annotation()
    for i, t in enumerate(turns):
        if t["ket_thuc"] > t["bat_dau"]:
            a[Segment(t["bat_dau"], t["ket_thuc"]), i] = t["nguoi_noi"]
    return a


def compare(code, out):
    srv = out / code
    lst = json.loads((srv / "danh_sach_doan.json").read_text(encoding="utf-8"))
    g7 = json.loads((SPIKE / "golden-stage-07" / f"{code}.json").read_text(encoding="utf-8"))
    row = {"ma": code, "doan": len(lst["doan"]), "doan_desktop": len(g7["points"]) - 1}

    # Mốc cắt (mẫu trên âm thanh sạch).
    pts = [round(c["bat_dau_giay"] * SR) for c in lst["doan"]] + [round(lst["doan"][-1]["ket_thuc_giay"] * SR)]
    if len(pts) == len(g7["points"]):
        row["lech_moc_max_mau"] = int(max(abs(a - b) for a, b in zip(pts, g7["points"])))
    row["mau_sach_server"] = pts[-1]
    row["mau_sach_desktop"] = g7["clean_samples"]

    # SNR: âm thanh sạch ghép từ đoạn FLAC của server, so với boosted[kept] của chuỗi desktop.
    boosted = np.memmap(SPIKE / "golden-stage-07" / g7["boosted"], dtype=np.int16, mode="r")
    ref = np.concatenate([boosted[a:b] for a, b in g7["kept"]])
    got = np.concatenate([decode(srv / c["file"]) for c in lst["doan"]])
    snr = block_snr(ref, got)
    # Khối chứa chỗ cắt lặng hai bên lệch nhau (vài mẫu) thì hỏng cả khối: báo trung vị, phân vị 5% và số khối < 50 dB.
    row["snr_trung_vi_db"] = round(float(np.median(snr)), 1)
    row["snr_p5_db"] = round(float(np.percentile(snr, 5)), 1)
    row["khoi_duoi_50db"] = f"{int(np.sum(snr < 50))}/{len(snr)}"
    row["lech_do_dai_mau"] = int(len(got) - len(ref))
    del ref, got

    # Lượt nói: server so với sherpa-onnx trên PC (đường điện thoại).
    s_json = json.loads((srv / "nguoi_noi.g2.json").read_text(encoding="utf-8"))
    p_json = json.loads((PC_SHERPA / f"{code}.json").read_text(encoding="utf-8"))
    tm_server = audio_processing.TimeMap(lst["ban_do_thoi_gian"], lst["thoi_luong_goc"])
    s1 = json.loads((SPIKE / code / "danh_sach_doan.json").read_text(encoding="utf-8"))
    tm_stage1 = audio_processing.TimeMap(s1["ban_do_thoi_gian"], s1["thoi_luong_goc"])
    goc = [dict(t, bat_dau=round(tm_server.to_original(t["bat_dau"]), 3), ket_thuc=round(tm_server.to_original(t["ket_thuc"]), 3))
           for t in s_json["doan"]]
    on_stage1 = [dict(t, bat_dau=tm_stage1.to_clean(t["bat_dau"]), ket_thuc=tm_stage1.to_clean(t["ket_thuc"])) for t in goc]
    der = DiarizationErrorRate(collar=0.5, skip_overlap=False)
    row["der_server_vs_may"] = round(der(ann(p_json["doan"]), ann(on_stage1)), 4)
    row["nguoi_server"] = s_json["so_nguoi"]
    row["nguoi_may"] = p_json["so_nguoi"]
    row["giay_xu_ly_sherpa"] = s_json.get("giay_xu_ly")
    # Ghi ra (mốc trên âm thanh gốc) để chấm D29 bằng evaluate.py --remap.
    (out / f"{code}__server.json").write_text(json.dumps(dict(s_json, doan=goc), ensure_ascii=False), encoding="utf-8")
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=r"D:\GuzzMobileData\stage-11\g2")
    ap.add_argument("--codes", default="B1,B2,B3,B4,B5")
    args = ap.parse_args()
    out = Path(args.out)
    rows = []
    for code in args.codes.split(","):
        if not (out / code / "danh_sach_doan.json").exists():
            print(f"{code}: chưa có kết quả server")
            continue
        rows.append(compare(code, out))
        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
    (out / "g2_compare.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
