"""
Sổ hạn mức mẫu cho trang Hạn mức (Stage 8 D2): ghi su_dung.json (cùng khoá JSON với desktop, D52) có một model khoá
theo ngày, một model khoá tới khi mở tay, hạn mức Google đã báo, lỗi và lịch sử; rồi đẩy vào files/quota/ của bản debug.

    python tools/device/quota_fixture.py                     # máy theo ANDROID_SERIAL
    python tools/device/quota_fixture.py --serial emulator-5554 --out su_dung.json   # chỉ ghi tệp, không đẩy: --no-push

App phải tắt trước khi đẩy (script tự force-stop) để sổ trong bộ nhớ không ghi đè tệp mới.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
import time

PKG = "io.github.cozycode890.guzz.debug"


def fixture(now: float) -> dict:
    return {
        "ngay": time.strftime("%Y-%m-%d", time.gmtime(now - 8 * 3600)),
        "model": {
            "gemini-3.5-flash": {
                "yeu_cau_hom_nay": 20, "token_hom_nay": 812345, "yeu_cau_tong": 140, "token_tong": 5_000_000,
                "lan_dung_cuoi": now - 600, "khoa_den": now + 3 * 3600 + 125, "khoa_vinh_vien": False,
                "ly_do_khoa": "han_muc_ngay", "chi_tiet_khoa": "429 RESOURCE_EXHAUSTED (thử)", "luc_khoa": now - 600,
                "loi_cuoi": "429 RESOURCE_EXHAUSTED: quota exceeded (thử)", "loai_loi_cuoi": "han_muc_ngay",
                "luc_loi_cuoi": now - 600, "dem_loi": {"han_muc_ngay": 1, "qua_tai": 2},
                "hoc_duoc": {"rpd": 20}, "cac_lan_khoa_phut": [],
            },
            "gemini-9.9-khong-co": {
                "yeu_cau_hom_nay": 0, "token_hom_nay": 0, "yeu_cau_tong": 1, "token_tong": 0,
                "khoa_vinh_vien": True, "ly_do_khoa": "khong_ton_tai", "chi_tiet_khoa": "404 (thử)",
                "luc_khoa": now - 300, "dem_loi": {"model": 1}, "hoc_duoc": {}, "cac_lan_khoa_phut": [],
            },
            "gemini-3.5-flash-lite": {
                "yeu_cau_hom_nay": 7, "token_hom_nay": 120000, "yeu_cau_tong": 7, "token_tong": 120000,
                "lan_dung_cuoi": now - 60, "khoa_vinh_vien": False, "dem_loi": {}, "hoc_duoc": {}, "cac_lan_khoa_phut": [],
            },
        },
        "su_kien": [
            {"luc": now - 900, "model": "gemini-3.5-flash", "loai": "loi", "noi_dung": "503 high demand (thử)",
             "loai_loi": "qua_tai"},
            {"luc": now - 600, "model": "gemini-3.5-flash", "loai": "khoa", "noi_dung": "khoá tới 15:00 (thử)",
             "ly_do": "han_muc_ngay", "den": now + 3 * 3600 + 125},
            {"luc": now - 599, "model": "gemini-3.5-flash-lite", "loai": "doi_model", "noi_dung": "han_muc_ngay",
             "tu_model": "gemini-3.5-flash"},
            {"luc": now - 300, "model": "gemini-9.9-khong-co", "loai": "khoa", "noi_dung": "404 (thử)",
             "ly_do": "khong_ton_tai"},
        ],
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--out")
    ap.add_argument("--no-push", action="store_true")
    a = ap.parse_args()
    path = a.out or os.path.join(tempfile.gettempdir(), "su_dung.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(fixture(time.time()), f, ensure_ascii=False, indent=2)
    print("Đã ghi", path)
    if a.no_push:
        return
    adb = ["adb"] + (["-s", a.serial] if a.serial else [])
    subprocess.run(adb + ["shell", "am", "force-stop", PKG], check=True)
    subprocess.run(adb + ["push", path, "/data/local/tmp/su_dung.json"], check=True)
    subprocess.run(adb + ["shell", f"run-as {PKG} sh -c 'mkdir -p files/quota && cp /data/local/tmp/su_dung.json files/quota/su_dung.json'"],
                   check=True)
    subprocess.run(adb + ["shell", "rm", "/data/local/tmp/su_dung.json"], check=True)
    print("Đã đẩy vào files/quota/su_dung.json; mở app → tab Hạn mức.")


if __name__ == "__main__":
    main()
