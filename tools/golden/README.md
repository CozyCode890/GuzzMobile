# Golden test

Thuật toán port từ Guzz desktop (Python) phải cho **đúng** kết quả của bản Python trên cùng đầu vào. Cách làm:

1. Script `gen_*.py` ở thư mục này chạy bằng **chính Python của desktop**, `import` thẳng module của desktop (vd
   `chunker`), dựng đầu vào tổng hợp, gọi hàm cần port, rồi ghi đầu vào + kết quả vào `testdata/golden/<tên>/`.
2. Dữ liệu đó được **commit** vào repo (D18), nên `gradlew test` chạy được trên mọi bản clone, không cần desktop.
3. Test Kotlin đọc dữ liệu bằng `Golden` (test fixtures của `:core:common`) và so với bản port.

Chỉ khi tạo lại dữ liệu mới cần desktop.

## Chạy

```powershell
.\tools\golden\regenerate.ps1
```

Chạy lần lượt mọi `gen_*.py` bằng `<GUZZ_DESKTOP_DIR>\runtime\python\python.exe -B -s`, dừng ở lỗi đầu tiên.
`GUZZ_DESKTOP_DIR` mặc định `D:\Guzz`. Cần PowerShell 7.

- `-B`: không ghi `__pycache__` vào thư mục desktop. **Không được ghi gì vào `D:\Guzz`.**
- `-s`: chỉ dùng thư viện của runtime, bỏ site-packages của tài khoản Windows.

Kết quả phải **tất định**: chạy lại thì `git diff testdata/golden` trống (seed cố định, JSON khoá sắp xếp, không ghi
giờ).

## Có gì

| Script | Ra | Test Kotlin |
| --- | --- | --- |
| `gen_chunker.py` | `chunker/find_split_points.json` + WAV | `:core:audio` `ChunkerGoldenTest` |
| `gen_config_keys.py` | `config/default_config.json` (mọi mục của `config.default.txt`) | `:core:common` `DesktopDefaultsTest` |
| `gen_wav_io.py` | `wav/known_samples.{wav,json}` | `:core:common` `GoldenTest` (kiểm chính công cụ đọc WAV) |

## Quy ước

- Một script `gen_<tên>.py` ghi vào `testdata/golden/<tên>/` (gọi `_common.output_dir("<tên>")`).
- JSON ghi bằng `_common.write_json`: UTF-8, thụt 2, khoá sắp xếp, có khoá `meta` (script, commit của desktop, cờ
  `guzz_desktop_dirty` nếu desktop có sửa đổi chưa commit, phiên bản Python / numpy). Test Kotlin bỏ qua `meta`.
- Đầu vào âm thanh ghi bằng `_common.write_wav16` (WAV PCM 16-bit mono, thư viện chuẩn `wave`). Script **đọc lại** WAV
  bằng `_common.read_wav16` (float32 = int16 / 32768) rồi mới gọi hàm của desktop, để Python và Kotlin
  (`Golden.readWav`, cũng chia 32768) nhận đúng một mảng.
- Giữ dữ liệu nhỏ: tần số lấy mẫu thấp (vd 1000 Hz), tham số thu nhỏ. Cả thư mục một bộ nên dưới 1 MB.
- Python tính bằng float32 ở chỗ Kotlin tính bằng double thì dựng dữ liệu có **biên an toàn** (vd hai giá trị nhỏ nhất
  cách nhau tương đối > 1e-4) và để script tự kiểm, không đủ thì dừng và báo đổi seed. Xem `window_margins` trong
  `gen_chunker.py`.
- Python embeddable của desktop có tệp `._pth`: **không** tự thêm thư mục của script vào `sys.path` và bỏ qua
  `PYTHONPATH`. Mỗi `gen_*.py` mở đầu bằng `sys.path.insert(0, <thư mục của nó>)` rồi mới `import _common`.

## Thêm một bộ golden mới, từng bước

Ví dụ: port `trim_silence` của `audio_processing.py` sang `:core:audio`.

1. **Script.** Tạo `tools/golden/gen_trim_silence.py`:

   ```python
   import os
   import sys

   sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

   import numpy as np  # noqa: E402

   import _common  # noqa: E402

   _common.use_desktop_modules()
   import audio_processing  # noqa: E402


   def main() -> None:
       out = _common.output_dir("trim_silence")
       rng = np.random.default_rng(1)
       results = []
       for name, y in [("speech_with_gap", ...), ...]:   # dựng tín hiệu tổng hợp
           _common.write_wav16(out / f"{name}.wav", y, 16000)
           y, sr = _common.read_wav16(out / f"{name}.wav")   # đọc lại: cùng đầu vào với Kotlin
           expected = audio_processing.trim_silence(y, sr, ...)
           results.append({"name": name, "wav": f"{name}.wav", "sr": sr, "expected": ...})
       _common.write_json(out / "trim_silence.json", {"cases": results}, os.path.basename(__file__))


   if __name__ == "__main__":
       main()
   ```

2. **Tạo dữ liệu.** `.\tools\golden\regenerate.ps1`, rồi chạy lại lần nữa và kiểm `git diff testdata/golden` trống.
3. **Phụ thuộc test.** Trong `build.gradle.kts` của module port (module dùng plugin `guzz.jvm.library`):

   ```kotlin
   plugins {
       alias(libs.plugins.guzz.jvm.library)
       alias(libs.plugins.kotlin.serialization)   // cho @Serializable của ca test
   }
   dependencies {
       testImplementation(testFixtures(project(":core:common")))
   }
   ```

   Plugin `guzz.jvm.library` truyền `-Dguzz.testdata=<gốc repo>/testdata` cho task `test` và khai báo thư mục đó là
   input, nên đổi dữ liệu golden thì test chạy lại.
4. **Test.** Mỗi ca một test, tên test = tên ca, so khớp tuyệt đối (mẫu: `ChunkerGoldenTest`):

   ```kotlin
   @Serializable
   private class GoldenCase(val name: String, val wav: String, val sr: Int, val expected: List<Int>)

   @Serializable
   private class GoldenFile(val cases: List<GoldenCase>)

   @TestFactory
   fun trimSilence(): List<DynamicTest> =
       Golden.readJson<GoldenFile>("trim_silence/trim_silence.json").cases.map { case ->
           dynamicTest(case.name) {
               val wav = Golden.readWav("trim_silence/${case.wav}")
               assertEquals(case.expected, trimSilence(wav.samples, wav.sampleRate))
           }
       }
   ```

5. **Thử đỏ.** Sửa tạm một hằng số trong bản Kotlin → test phải đỏ; trả lại → xanh.
6. **Ghi bảng.** Thêm một dòng vào bảng "Có gì" ở trên. KDoc của hàm Kotlin ghi `Port của <tệp>.py: <hàm>`.
