# Guzz Mobile

Port **Guzz** (app desktop Windows chuyển âm thanh / video thành văn bản, Python + PySide6) sang **Android**.
Bản desktop nằm ở `D:\Guzz`: chỉ dùng làm bản tham chiếu khi port, **không sửa**.

Kế hoạch tổng: [plans/README.md](plans/README.md). Mỗi stage có một tệp `plans/stage-NN-*.md`.

## Đang ở đâu

**Stage hiện tại:** Stage 4, đang làm (plan chi tiết 2026-11-11, D59–D70). Stage 3 xong ngày 2026-11-11, Stage 2 xong
ngày 2026-11-11, Stage 1 xong ngày 2026-11-10 (rút gọn, D39–D42), Stage 0 xong ngày 2026-11-01.
**Việc tiếp theo:** Stage 4: A–C xong (PC), D–E đã viết, D2 đạt trên điện thoại. Thử tiếp D3 và E trên máy ảo `guzz_15t`
(D70, `tools/device/emulator.ps1`), rồi đợt thử cuối trên Xiaomi 15T gồm F (gửi thật B5 + B4, tổng ≤ 15 yêu cầu, D65).
Gửi bất cứ thứ gì lên Google thì hỏi người dùng ngay trước khi gửi. Số đo của Stage 1 ở
[stage-01-results.md](plans/stage-01-results.md).

| Stage | Tên | Trạng thái | Tệp |
| --- | --- | --- | --- |
| 0 | Khởi tạo dự án | ✅ 2026-11-01 | [stage-00-setup.md](plans/stage-00-setup.md) |
| 1 | Spike kỹ thuật | ✅ 2026-11-10 | [stage-01-spike.md](plans/stage-01-spike.md) |
| 2 | Lõi âm thanh tối giản | ✅ 2026-11-11 | [stage-02-audio-core.md](plans/stage-02-audio-core.md) |
| 3 | Client Gemini, hạn mức, đổi model | ✅ 2026-11-11 | [stage-03-gemini-client.md](plans/stage-03-gemini-client.md) |
| 4 | Pipeline + hàng đợi nền — **MVP** | 🔨 | [stage-04-pipeline-mvp.md](plans/stage-04-pipeline-mvp.md) |
| 5 | Tách giọng trên máy | ⬜ | [stage-05-diarization.md](plans/stage-05-diarization.md) |
| 6 | Intent cho MacroDroid | ⬜ | [stage-06-intents.md](plans/stage-06-intents.md) |
| 7 | Làm sạch âm thanh đầy đủ | ⬜ | [stage-07-audio-cleaning.md](plans/stage-07-audio-cleaning.md) |
| 8 | Hoàn thiện giao diện | ⬜ | [stage-08-ui.md](plans/stage-08-ui.md) |
| 9 | Đóng gói & phát hành | ⬜ | [stage-09-release.md](plans/stage-09-release.md) |
| 10 | (Tuỳ chọn) Plugin Tasker/Locale | ⬜ | [stage-10-tasker-plugin.md](plans/stage-10-tasker-plugin.md) |

Ký hiệu: ⬜ chưa plan chi tiết · 📝 đã plan chi tiết · 🔨 đang làm · ✅ xong (ghi ngày) · ⏸ hoãn

## Quyết định đã chốt

Bảng đầy đủ kèm lý do: [plans/README.md §2](plans/README.md#2-quyết-định-đã-chốt).

- Chỉ Android; Kotlin + Jetpack Compose.
- Tách giọng: sherpa-onnx (pyannote segmentation-3.0 + embedding 3D-Speaker), chạy CPU, model đóng gói trong APK.
- Phát hành APK tự cài (GitHub Releases); có flavor `play` để sau này lên Play.
- MacroDroid gọi Guzz bằng broadcast intent; plugin Tasker/Locale để stage tuỳ chọn cuối.
- Đọc tệp: có quyền "Truy cập mọi tệp" thì nhận đường dẫn tuyệt đối, không có thì nhận SAF / content:// URI.
- Làm sạch âm thanh theo bậc: MVP chỉ cắt lặng và chuẩn hoá âm lượng, Stage 7 port đủ DSP.
- Sau MVP làm tách giọng trên máy trước.

## Cách làm việc giữa các session

1. **Mở session:** đọc tệp này → [plans/README.md](plans/README.md) → tệp của stage đang làm.
2. **Plan chi tiết một stage** (mỗi stage một session riêng):
   - hỏi người dùng các mục "Câu hỏi cần chốt" của stage bằng câu hỏi **trắc nghiệm** (AskUserQuestion), phương án đề
     xuất đặt đầu và ghi "(Recommended)";
   - gặp hướng đi mới chưa có trong plan cũng hỏi trắc nghiệm, không tự chọn;
   - ghi câu trả lời vào mục "Quyết định" của tệp stage và vào bảng quyết định ở plans/README.md;
   - viết mục "Kế hoạch chi tiết": các bước nhỏ dạng checklist `- [ ]`, bước nào cũng kiểm chứng được (bằng test, lệnh,
     hoặc thao tác trên máy);
   - đổi trạng thái stage sang 📝 trong bảng ở trên.
3. **Làm stage:** đổi sang 🔨; xong bước nào thì đánh `[x]` bước đó; làm khác kế hoạch thì ghi vào mục "Nhật ký" của tệp
   stage.
4. **Xong stage:** kiểm đủ "Tiêu chí hoàn thành", đổi sang ✅ kèm ngày, cập nhật "Stage hiện tại" và "Việc tiếp theo".
5. Thấy việc cần làm nhưng nằm ngoài stage hiện tại thì ghi vào mục "Phát sinh" của stage phù hợp, không làm luôn.

## Quy ước

- Code Kotlin. Tên class, hàm, biến, tệp bằng tiếng Anh, như bản desktop sau refactor. Comment giải thích *vì sao*, viết
  tiếng Việt như bản desktop.
- Port một hàm từ desktop thì ghi nguồn trong KDoc, vd `Port của chunker.py: find_split_points`.
- Thuật toán port từ Python nằm ở module JVM thuần và phải có **golden test**: chạy chính bản Python
  (`D:\Guzz\runtime\python\python.exe`) trên cùng đầu vào rồi so kết quả.
- Tài liệu và plan viết tiếng Việt có dấu. Giao diện: `strings.xml`, tiếng Việt mặc định, có tiếng Anh.
- Commit message viết tiếng Việt, như repo desktop.
- Không commit API key, token, hay bản ghi âm thật. Bản ghi thật để ngoài repo; đường dẫn ghi trong `local.properties`
  hoặc biến môi trường.

## Lệnh thường dùng

Chạy trong PowerShell 7 tại `D:\GuzzMobile`. Máy mới thì làm mục "Cài môi trường một lần" ở dưới trước.

```powershell
# Build APK debug, ra app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
.\gradlew assembleSideloadDebug
.\gradlew assemblePlayDebug

# Test JVM (gồm golden test) và lint (biến thể mặc định: sideload debug)
.\gradlew test
.\gradlew lint

# Chỉ chạy golden test chunker, không lấy kết quả từ build cache
.\gradlew :core:audio:test --tests "*ChunkerGoldenTest" --rerun

# Cài lên máy thật qua USB rồi mở app
.\gradlew installSideloadDebug
adb shell am start -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.MainActivity

# Nhật ký: tệp trên máy (guzz.log, guzz.log.1, …) và logcat của app. Dấu "/." để chạy lại nhiều lần vẫn chép đè vào
# đúng thư mục đó, không sinh thư mục con logs\
adb pull /sdcard/Android/data/io.github.cozycode890.guzz.debug/files/logs/. "$env:TEMP\guzz-logs"
adb logcat -d -s Guzz

# Tạo lại dữ liệu golden (cần Guzz desktop ở D:\Guzz hoặc $env:GUZZ_DESKTOP_DIR), xong thì xem git diff testdata/golden
# và testdata/media (tệp thử tổng hợp cho test trên máy)
.\tools\golden\regenerate.ps1

# Golden trên bản ghi thật (Stage 2, D46): bài B1–B5 ở $env:GUZZ_SPIKE_DATA (ngoài repo). Chạy desktop một lần, rồi test
# Kotlin; thiếu dữ liệu thì test tự bỏ qua. --rerun vì Gradle không biết dữ liệu ngoài repo đã đổi.
D:\Guzz\runtime\python\python.exe -B -s tools\golden\real_golden.py
.\gradlew :core:audio:test --tests "*RealRecordingGoldenTest" --rerun -i

# Client Gemini (Stage 3): test JVM (golden, HTTP giả bằng MockWebServer, chuỗi lỗi 17/09); tạo lại riêng golden Gemini
.\gradlew :core:gemini:test
D:\Guzz\runtime\python\python.exe -B -s tools\golden\gen_gemini.py

# Test thiết bị của :app (lưu key bằng Keystore). HyperOS huỷ cài APK test qua Gradle (và Gradle gỡ luôn app): cài tay
# cả hai APK lúc máy mở khoá rồi chạy thẳng
.\gradlew assembleSideloadDebug :app:assembleSideloadDebugAndroidTest
adb install -r -t app\build\outputs\apk\sideload\debug\app-sideload-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\sideload\debug\app-sideload-debug-androidTest.apk
adb shell am instrument -w io.github.cozycode890.guzz.debug.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall io.github.cozycode890.guzz.debug.test

# Gửi thử Gemini từ bản debug (GỬI THẬT lên Google: hỏi người dùng trước). Key nhập trên máy ở trang Google AI (nút ở
# màn hình chính); thư mục bài là kết quả của audio_debug.ps1. Kết quả: files/debug-gemini/*.json
.\tools\device\gemini_debug.ps1 -ListModels
.\tools\device\gemini_debug.ps1 -Folder <bài> -Model gemini-3.5-transcribe -Mode words   # smart|verbatim|words|diarize

# Máy ảo guzz_15t giống Xiaomi 15T (Stage 4, D70): tạo nếu chưa có, khởi động, cài APK debug, miễn tối ưu pin (D69),
# đẩy tệp thử từ D:\GuzzMobileData\stage-04. Rồi đặt ANDROID_SERIAL như script in ra và dùng các script dưới đây.
.	ools\device\emulator.ps1 -Push B1_1m.m4a,L1_20m.m4a
$env:ANDROID_SERIAL = 'emulator-5556'

# Hàng đợi thật qua adb (Stage 4, bản debug): Gemini giả (không gọi mạng), thêm tệp ở files/input, chạy, chờ xong
.	ools\device\queue_debug.ps1 -Fake on
.	ools\device\queue_debug.ps1 -Add L1_20m.m4a -ChunkDelayMin 1 -Start -ScreenOff -Wait
.	ools\device\queue_debug.ps1 -Stop ; .	ools\device\queue_debug.ps1 -Kill ; .	ools\device\queue_debug.ps1 -Status

# Test và golden của pipeline (Stage 4)
.\gradlew :core:pipeline:test
D:\Guzzuntime\python\python.exe -B -s tools\golden\gen_pipeline.py

# Đo tốc độ resampler trên PC (chỉ chạy khi đặt GUZZ_BENCH=1)
$env:GUZZ_BENCH = '1'; .\gradlew :core:audio:test --tests "*SincResamplerBenchmark" --rerun -i

# Test trên máy của :core:media (giải mã, chọn luồng, FLAC), điện thoại nối adb
.\gradlew :core:media:connectedDebugAndroidTest
# HyperOS hay huỷ việc cài APK test (gói mới) qua adb không dây: INSTALL_FAILED_USER_RESTRICTED. Khi đó build APK test,
# cài tay (máy mở khoá, bấm đồng ý nếu hỏi) rồi chạy thẳng, xong gỡ:
.\gradlew :core:media:assembleDebugAndroidTest
adb install -r -t core\media\build\outputs\apk\androidTest\debug\media-debug-androidTest.apk
adb shell am instrument -w io.github.cozycode890.guzz.core.media.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall io.github.cozycode890.guzz.core.media.test

# Màn hình thử lõi âm thanh (chỉ bản debug, D49): tệp adb push vào files/input/ rồi chạy, chờ xong, in kết quả.
# -ScreenOff tắt màn hình ngay sau khi bắt đầu; -PullPcm kéo goc.pcm về để so với ffmpeg / Python.
adb push <tệp> /sdcard/Android/data/io.github.cozycode890.guzz.debug/files/input/
.\tools\device\audio_debug.ps1 -Path <tên tệp> -ScreenOff
D:\Guzz\runtime\python\python.exe -B -s tools\device\compare_ffmpeg.py --pcm <goc.pcm> --src <tệp gốc>

# App spike của Stage 1 (build Gradle riêng, app không phụ thuộc vào). Lệnh đẩy dữ liệu, chạy bench, adb qua Wi-Fi và
# script đo trên PC: spikes/README.md. Dữ liệu ngoài repo ở $env:GUZZ_SPIKE_DATA (D:\GuzzMobileData\stage-01).
.\gradlew -p spikes\android :app:installDebug
.\gradlew -p spikes\android :app:testDebugUnitTest
```

### Cài môi trường một lần

1. JDK 21 (Temurin) và đặt `JAVA_HOME` mức User trỏ tới JDK đó. Daemon Gradle luôn chạy JDK 21
   (`gradle/gradle-daemon-jvm.properties`), nhưng `gradlew` cần `JAVA_HOME` hợp lệ để khởi động.
2. Tải "Command line tools only" bản Windows ở developer.android.com/studio, giải nén sao cho có
   `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`.
3. Biến môi trường mức User:

   ```powershell
   [Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot', 'User')
   [Environment]::SetEnvironmentVariable('ANDROID_HOME', "$env:LOCALAPPDATA\Android\Sdk", 'User')
   ```

   Thêm `%ANDROID_HOME%\cmdline-tools\latest\bin` vào `Path` của User bằng hộp thoại Environment Variables của Windows.
   Không dùng `SetEnvironmentVariable` cho `Path`: nó ghi lại kiểu `REG_SZ` với giá trị đã mở rộng, làm mất các mục
   `%...%`. Đổi biến xong thì mở terminal mới. Nếu chạy trong app Claude thì khởi động lại app, vì app giữ biến cũ.
4. Giấy phép và gói SDK. Giấy phép thì **người dùng tự đọc và đồng ý**:

   ```powershell
   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
   ```

5. Điện thoại: bật Gỡ lỗi USB và "Cài đặt qua USB" trong Tuỳ chọn nhà phát triển (HyperOS đòi đăng nhập tài khoản
   Xiaomi). `adb devices` phải thấy máy ở trạng thái `device`.

## Môi trường máy (kiểm ngày 2026-11-01)

- JDK: Gradle chạy bằng Temurin 21.0.13 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`). `JAVA_HOME` mức
  User trỏ tới đó; daemon chốt JDK 21 bằng `gradle/gradle-daemon-jvm.properties` (D26). `PATH` vẫn có `java` 25.0.5,
  không ảnh hưởng build.
- Android SDK: `ANDROID_HOME` (User) = `%LOCALAPPDATA%\Android\Sdk`; cmdline-tools 22.0 (`sdkmanager` báo deprecated),
  platform-tools 37.0.1, `platforms;android-37.0`, build-tools 37.0.0. Đã đồng ý `android-sdk-license`.
- adb: bản trên `PATH` là gói winget `Google.PlatformTools`; Gradle dùng bản trong SDK. Hai bản cùng `37.0.1-15733141`
  (A5). Cập nhật bản này mà không cập nhật bản kia thì adb báo "server version doesn't match".
- App Claude giữ biến môi trường từ lúc mở: đổi `JAVA_HOME` / `ANDROID_HOME` / `PATH` xong phải khởi động lại app.
- GitHub CLI `gh` đã đăng nhập tài khoản CozyCode890 (SSH). Repo desktop: `CozyCode890/Guzz` (public).
- Máy thật: Xiaomi 15T, Android 16, HyperOS 3.0, đã bật Gỡ lỗi USB (chi tiết ở D22).
- adb không dây qua Tailscale: `adb connect 100.71.61.23:5555`, rồi `$env:ANDROID_SERIAL = '100.71.61.23:5555'`. Máy
  khởi động lại thì cổng 5555 đóng: người dùng cắm USB và chạy `adb tcpip 5555`. Guzz (debug) đã được đặt pin "No
  restrictions" (2026-11-11); thiếu cài đặt này HyperOS tắt wake lock khi màn hình tắt và việc nền đứng im.
- Python và runtime của Guzz desktop (dùng cho golden test): `D:\Guzz\runtime\python\python.exe`; pyannote:
  `D:\Guzz\runtime\nguoi_noi\python.exe`; ffmpeg: `D:\Guzz\runtime\ffmpeg\ffmpeg.exe`.
- `D:\GuzzMobile` là git repo, nhánh `main`, remote `origin` = `CozyCode890/GuzzMobile` (private, SSH).
