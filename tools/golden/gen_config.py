"""
Golden cho đọc INI (Stage 8 F2, D112)  →  testdata/golden/config/ini_parse.json

Đọc hai tệp bằng chính config._read_parser của desktop (configparser với inline_comment_prefixes=("#", ";"),
interpolation=None, delimiters=("=",)) rồi ghi {tệp: {SECTION: {key: giá trị}}}:
  - desktop_config.default.txt: bản chép config.default.txt của desktop (chép lại mỗi lần chạy);
  - tricky_config.txt: tệp thử viết tay trong repo (ghi chú cuối dòng, C#, giá trị nhiều dòng, BOM, key viết hoa…).
Test Kotlin ConfigIniGoldenTest đọc cùng hai tệp bằng ConfigIni.parse và so.
"""

from __future__ import annotations

import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

desktop = _common.use_desktop_modules()
import config  # noqa: E402


def read(path) -> dict:
    cp = config._read_parser(str(path))
    return {name: {key: value for key, value in cp.items(name, raw=True)} for name in cp.sections()}


def main() -> None:
    out = _common.output_dir("config")
    shutil.copyfile(desktop / "config.default.txt", out / "desktop_config.default.txt")
    files = {name: read(out / name) for name in ("desktop_config.default.txt", "tricky_config.txt")}
    _common.write_json(out / "ini_parse.json", {"files": files}, os.path.basename(__file__))
    for name, sections in files.items():
        print(f"{name}: {len(sections)} section, {sum(len(v) for v in sections.values())} key")


if __name__ == "__main__":
    main()
