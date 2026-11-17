"""
Golden cho mặc định cài đặt của desktop  →  testdata/golden/config/default_config.json

Đọc config.default.txt bằng chính config._read_parser của desktop (cùng cách bỏ comment cuối dòng, không nội suy %),
ghi ra {SECTION: {key: giá trị dạng chữ}}. Test Kotlin (DesktopDefaultsTest) so mặc định của mọi SettingKey có
sameAsDesktop = true với bảng này.
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _common  # noqa: E402

desktop = _common.use_desktop_modules()
import config  # noqa: E402


def main() -> None:
    cp = config._read_parser(str(desktop / "config.default.txt"))
    sections = {name: {key: value for key, value in cp.items(name, raw=True)} for name in cp.sections()}
    out = _common.output_dir("config")
    _common.write_json(out / "default_config.json", {"sections": sections}, os.path.basename(__file__))
    print(f"default_config.json: {len(sections)} section, {sum(len(v) for v in sections.values())} key")

    # Preset âm thanh (Stage 7, A2): bảng preset, và preset mà desktop nhận ra từ config.default.txt (D87).
    sys.path.insert(0, str(desktop / "gui"))
    import presets  # noqa: E402

    default_cfg = config.load_config(str(desktop / "config.default.txt"))
    _common.write_json(out / "presets.json", {
        "fields": list(presets.PRESET_FIELDS),
        "presets": presets.PRESETS,
        "labels": list(presets.PRESET_LABELS),
        "default_values": presets.values_from_config(default_cfg),
        "default_preset": presets.detect_preset(presets.values_from_config(default_cfg)),
    }, os.path.basename(__file__))
    print(f"presets.json: {len(presets.PRESETS)} preset, mặc định = {presets.detect_preset(presets.values_from_config(default_cfg))}")


if __name__ == "__main__":
    main()
