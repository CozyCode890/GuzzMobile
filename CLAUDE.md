# Guzz Mobile

Port **Guzz** (app desktop Windows chuyển âm thanh / video thành văn bản, Python + PySide6) sang **Android**.
Bản desktop nằm ở `D:\Guzz`: chỉ dùng làm bản tham chiếu khi port, **không sửa**.

Kế hoạch tổng: [plans/README.md](plans/README.md). Mỗi stage có một tệp `plans/stage-NN-*.md`.

## Đang ở đâu

**Stage hiện tại:** Stage 0 (đang làm).
**Việc tiếp theo:** làm tiếp Stage 0 theo mục "Kế hoạch chi tiết" của [stage-00-setup.md](plans/stage-00-setup.md):
chờ người dùng đồng ý giấy phép SDK (A3), rồi A4, A5, A7 và các bước còn `[ ]` (E, F3, G3, C5, B3–B4, I, J, K). Mã của
`:app` đã viết nhưng chưa build lần nào.

| Stage | Tên | Trạng thái | Tệp |
| --- | --- | --- | --- |
| 0 | Khởi tạo dự án | 🔨 | [stage-00-setup.md](plans/stage-00-setup.md) |
| 1 | Spike kỹ thuật | ⬜ | [stage-01-spike.md](plans/stage-01-spike.md) |
| 2 | Lõi âm thanh tối giản | ⬜ | [stage-02-audio-core.md](plans/stage-02-audio-core.md) |
| 3 | Client Gemini, hạn mức, đổi model | ⬜ | [stage-03-gemini-client.md](plans/stage-03-gemini-client.md) |
| 4 | Pipeline + hàng đợi nền — **MVP** | ⬜ | [stage-04-pipeline-mvp.md](plans/stage-04-pipeline-mvp.md) |
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

# Nhật ký: tệp trên máy (guzz.log, guzz.log.1, …) và logcat của app
adb pull /sdcard/Android/data/io.github.cozycode890.guzz.debug/files/logs "$env:TEMP\guzz-logs"
adb logcat -d -s Guzz

# Tạo lại dữ liệu golden (cần Guzz desktop ở D:\Guzz hoặc $env:GUZZ_DESKTOP_DIR), xong thì xem git diff testdata/golden
.\tools\golden\regenerate.ps1
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
- Python và runtime của Guzz desktop (dùng cho golden test): `D:\Guzz\runtime\python\python.exe`; pyannote:
  `D:\Guzz\runtime\nguoi_noi\python.exe`; ffmpeg: `D:\Guzz\runtime\ffmpeg\ffmpeg.exe`.
- `D:\GuzzMobile` là git repo, nhánh `main`.
