# Stage 0 — Khởi tạo dự án

**Trạng thái:** ✅ xong 2026-11-01 (bắt đầu 2026-10-31; plan chi tiết 2026-10-31) · **Cần xong trước:** —

## Mục tiêu

Dựng sẵn khung dự án: build được, test được, cài lên máy thật được. Các stage sau chỉ việc thêm code.

## Phạm vi

### Làm

- `git init`, `.gitignore` cho Android, `.editorconfig`.
- Môi trường build: Android Studio hoặc cmdline-tools + SDK platform + build-tools. Máy đang có JDK 25, Gradle/AGP có thể
  chưa hợp nên dùng JBR của Android Studio hoặc Temurin 21.
- Gradle wrapper, version catalog (`gradle/libs.versions.toml`), Kotlin, AGP, Compose BOM, Material 3.
- Các module theo [README §3](README.md#3-kiến-trúc-dự-kiến), tạo hết ngay hoặc tạo dần khi cần.
- App rỗng: một màn hình Compose "Guzz", icon tạm.
- Hai flavor: `sideload` (mặc định, có quyền `MANAGE_EXTERNAL_STORAGE`) và `play` (không có quyền đó).
- Khung Settings trên DataStore. Tên key bám theo `config.default.txt` (`section.key`), để sau này đối chiếu và có thể
  nhập / xuất cài đặt.
- Log ra tệp trong app (stage sau thêm màn hình xem nhật ký).
- Công cụ golden test: `tools/golden/` chứa script Python chạy bằng `D:\Guzz\runtime\python\python.exe`, import thẳng
  module của Guzz và xuất kết quả ra JSON vào `testdata/golden/`. Có một golden test mẫu chạy được (vd `find_split_points`
  trên tín hiệu tổng hợp).
- Điền mục "Lệnh thường dùng" trong [CLAUDE.md](../CLAUDE.md): build, test, cài lên máy, tạo lại dữ liệu golden.

### Không làm

- Chưa có tính năng nào.

## Nguồn tham khảo trong Guzz desktop

- `config.default.txt`, `config.py`: danh sách các mục cài đặt.
- `tests/`: cách desktop dựng âm thanh tổng hợp cho test.
- `README.md` §8: dữ liệu nằm ở đâu.

## Kết quả bàn giao

- Repo git có commit đầu tiên.
- `gradlew assembleSideloadDebug` ra APK, cài lên máy thật và mở được.
- `gradlew test` chạy, có ít nhất một golden test so với Python.

## Tiêu chí hoàn thành

- [x] Build sạch từ một bản clone mới, chỉ theo các lệnh ghi trong CLAUDE.md. (J2, 2026-11-01)
- [x] APK chạy trên máy thật. (E5, Xiaomi 15T, 2026-11-01)
- [x] Golden test mẫu: sửa số trong Kotlin thì test đỏ, khôi phục lại thì test xanh. (H6, 2026-10-31)

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-10-31, xem mục [Quyết định](#quyết-định): applicationId → D9, tạo module → D11, DI → D13,
log → D14, máy thật → D22, GitHub → D21.

## Quyết định

Hỏi người dùng ngày 2026-10-31. Mã số tiếp theo bảng ở [README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D9 | `applicationId` | **`io.github.cozycode890.guzz`**; package Kotlin cùng gốc (`io.github.cozycode890.guzz.core.audio`…) | Theo tài khoản GitHub CozyCode890 (repo desktop cũng ở đó), khớp với phát hành qua GitHub Releases; là namespace người dùng sở hữu thật, lên Play sau này vẫn dùng được. |
| D10 | Bản debug có hậu tố applicationId không | **Có**: `io.github.cozycode890.guzz.debug`, tên app "Guzz (debug)" | Bản debug và release cài song song, dữ liệu riêng. Macro MacroDroid thử trên bản debug phải trỏ package `.debug`. |
| D11 | Tạo module | **Tạo dần**: Stage 0 chỉ có `:app`, `:core:common`, `:core:audio` | Stage 1 có thể còn chỉnh danh sách module. `:core:audio` cần ngay để chứa golden test mẫu `find_split_points`. |
| D12 | Cấu hình build chung | **Convention plugin** trong `build-logic/` | `build.gradle.kts` mỗi module chỉ vài dòng; thêm module mới nhanh, cấu hình không lệch nhau. |
| D13 | Dependency injection | **Tạo tay**: một `AppContainer` trong `Application` | Không thêm thư viện, không sinh mã; module JVM thuần chỉ nhận tham số qua constructor. App ít màn hình nên đủ dùng. |
| D14 | Thư viện log | **Tự viết**: interface trong `:core:common` (JVM thuần); `:app` ghi ra Logcat + tệp xoay vòng như desktop | Module JVM thuần không dùng được Timber (thư viện Android). Không thêm thư viện. |
| D15 | Chỗ để tệp log | **Thư mục riêng của app trên bộ nhớ ngoài**: `Android/data/<package>/files/logs/` | Không cần quyền; lấy được bằng `adb pull` hoặc cắm USB, kể cả bản release. Gỡ app thì mất theo. |
| D16 | Cách lưu Settings | **Preferences DataStore, giá trị có kiểu** (bool / int / double / string), key `SECTION.key` đúng như `config.default.txt` | Sai kiểu lộ ra lúc biên dịch. `datastore-preferences-core` là thư viện JVM nên Settings nằm được ở `:core:common`, test trên máy tính. |
| D17 | Khung test | **JUnit 5 + kotlin.test** ở module JVM thuần; **JUnit 4** ở module Android | Golden test cần test tham số hoá (mỗi ca một test). Test thiết bị / Robolectric vẫn cần JUnit 4. |
| D18 | Dữ liệu golden | **Commit vào repo** (`testdata/golden/`): đầu vào tổng hợp dạng WAV 16-bit nhỏ + JSON kết quả | `gradlew test` chạy được trên mọi bản clone, không cần Guzz desktop; chỉ khi tạo lại dữ liệu mới cần Python. |
| D19 | JDK cho Gradle | **Temurin 21 có sẵn** (`C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`): `JAVA_HOME` của tài khoản trỏ về đó; `gradle/gradle-daemon-jvm.properties` ghim JDK 21 | `JAVA_HOME` hiện trỏ tới `jdk-25.0.4.101`, thư mục này không còn, nên `gradlew` sẽ báo lỗi ngay. JDK 21 đã cài sẵn, không phải tải thêm. |
| D20 | Cài Android SDK | **Chỉ cmdline-tools**, SDK ở `%LOCALAPPDATA%\Android\Sdk`, biến `ANDROID_HOME` | Mọi bước chạy bằng lệnh, ghi lại được trong CLAUDE.md, nhẹ. Android Studio cài sau nếu cần. |
| D21 | GitHub | **Repo private `CozyCode890/GuzzMobile`**; có thể chuyển public ở Stage 9 | An toàn khi đang làm (lỡ commit key / bản ghi cũng không lộ); Releases vẫn tải được khi đăng nhập. |
| D22 | Máy thật để thử | **Xiaomi 15T** (`25069PTEBG`, mã `goya`): MediaTek MT6899, CPU 8 nhân (xung tối đa 2,1 / 3,0 / 3,25 GHz), RAM 12 GB (`MemTotal` 11,1 GiB), Android 16 (API 36), HyperOS 3.0 (`OS3.0.303.0.WOEMIXM`), ABI chỉ `arm64-v8a`. Đọc bằng adb ngày 2026-10-31. | Số liệu thật để đặt mục tiêu hiệu năng ở Stage 1. Xiaomi là hãng giết app nền mạnh (README §6, rủi ro 4) nên thử chạy nền trên máy này có ý nghĩa. |
| D23 | `SYSTEM.old_log_count = 0` | **Đầy thì xoá trắng `guzz.log`, không giữ tệp cũ** | `RotatingFileHandler` của Python với `backupCount = 0` thì không bao giờ xoay, tệp lớn mãi; trên điện thoại dung lượng log phải luôn bị chặn. Khác desktop ở đúng trường hợp này; ghi lý do trong KDoc. |
| D24 | Bộ phiên bản Gradle / AGP (hỏi lúc làm C1) | **AGP 9.3.3 + Gradle 9.7.1**, Kotlin 2.4.21 | Kotlin 2.4.21 (bản ổn định mới nhất) mới được JetBrains kiểm tới AGP 9.3.1 và Gradle 9.7.0; AGP 9.3 vẫn hỗ trợ API 37. AGP mới nhất (9.5.0) để nâng sau khi Kotlin 2.5 ra. |
| D25 | Bản JUnit cho module JVM (hỏi lúc làm C1) | **JUnit 6.1.3** (`junit-bom`), vẫn dùng `kotlin-test-junit5` | JUnit 6 là dòng đang phát triển, cùng API Jupiter nên tinh thần D17 giữ nguyên; cần Java 17, đã có. Trục trặc thì lùi về 5.14. |
| D26 | Ghim JDK 21 cho daemon (hỏi lúc làm C4) | **Viết tay** `gradle/gradle-daemon-jvm.properties` chỉ có `toolchainVersion=21`, không thêm plugin foojay | `updateDaemonJvm` của Gradle 9.7 đòi khai báo kho tải JDK. Gradle tự tìm JDK 21 đã cài; máy mới cài JDK 21 trước (ghi trong CLAUDE.md), đúng tinh thần D19. Không thêm phụ thuộc. |
| D27 | Kiểm F3 khi Stage 0 chưa ghi cài đặt nào (hỏi lúc làm F3, 2026-11-01) | **Lưu versionCode lúc mở app**: mục chỉ có trên mobile `APP.last_version_code` (`sameAsDesktop = false`), nằm trong `SettingKeys.appState`, không trong `SettingKeys.all` nên không xuất / nhập; `Settings.recordAppVersion` ghi khi khác lần trước | DataStore chỉ tạo tệp ở lần ghi đầu nên kiểm F3 trên máy không ra tệp. Ghi versionCode thì tệp có ngay lần mở đầu, và Stage 9 dùng nó để biết vừa cài đè bản mới mà chuyển dữ liệu cũ. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Kotlin biên dịch bằng toolchain JDK 21, bytecode JVM 17 cho mọi module (D8 của Android chắc chắn đọc được).
- Nhánh chính `main`. Flavor dimension tên `distribution`. `versionCode 1`, `versionName "0.1.0"`.
- Stage 0 chỉ khai báo các mục Settings mà log cần: `SYSTEM.log_level`, `SYSTEM.log_size_mb`, `SYSTEM.old_log_count`.
- Định dạng dòng log giống desktop (`transcribe.py`): `2026-10-31 14:05:00 | INFO    | nội dung`.
- Script golden chạy bằng `python.exe -B -s`: `-B` để không ghi `__pycache__` vào `D:\Guzz`, `-s` để chỉ dùng thư viện
  của runtime (bỏ site-packages của tài khoản Windows).

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Lệnh chạy trong PowerShell 7 tại `D:\GuzzMobile`.

### A. Môi trường máy

- [x] **A1. Sửa `JAVA_HOME`.** Xem `JAVA_HOME` đang đặt ở mức nào:
  `[Environment]::GetEnvironmentVariable('JAVA_HOME','User')` và `...('JAVA_HOME','Machine')`. Đặt mức User trỏ tới
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot` (biến mức User đè biến mức Machine cùng tên, không cần quyền
  admin). `PATH` vẫn để `java` 25, không sao vì `gradlew` dùng `JAVA_HOME`.
  **Kiểm:** mở shell mới, `& "$env:JAVA_HOME\bin\java.exe" -version` báo `21.0.13`.
- [x] **A2. Tải cmdline-tools.** Lấy link "Command line tools only" bản Windows mới nhất trên
  developer.android.com/studio, tải vào scratchpad, so SHA-256 với số ghi trên trang. Giải nén sao cho có
  `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`. Đặt `ANDROID_HOME` (mức User) =
  `%LOCALAPPDATA%\Android\Sdk`; thêm `%ANDROID_HOME%\cmdline-tools\latest\bin` vào `PATH` của User.
  **Kiểm:** shell mới, `sdkmanager --version` in ra số phiên bản.
- [x] **A3. Giấy phép SDK.** Chạy `sdkmanager --licenses`; **người dùng tự đọc và bấm đồng ý** (chấp nhận điều khoản
  phải do người dùng quyết).
  **Kiểm:** có tệp `%ANDROID_HOME%\licenses\android-sdk-license`.
- [x] **A4. Cài gói SDK.** `sdkmanager "platform-tools" "platforms;android-<API>" "build-tools;<bản>"`, với `<API>` là
  bản ổn định mới nhất (= `compileSdk` / `targetSdk`, chốt ở C1) và build-tools mới nhất cùng đời.
  **Kiểm:** `sdkmanager --list_installed` có đủ ba gói.
- [x] **A5. Một bản adb.** Máy đã có adb của gói winget `Google.PlatformTools`; A4 cài thêm một bản trong SDK. So
  `adb version` của hai bản. Khác nhau thì **hỏi người dùng**: gỡ gói winget, hay đưa `%ANDROID_HOME%\platform-tools`
  lên đầu `PATH`.
  **Kiểm:** `(Get-Command adb).Source` chỉ ra một bản; `adb devices` không báo "server version doesn't match".
- [x] **A6. Đọc thông tin máy thật** (làm luôn lúc plan, 2026-10-31): Gỡ lỗi USB đã bật, `adb devices -l` thấy
  `<serial> … model:25069PTEBG device:goya`; kết quả `getprop` và `/proc/meminfo` ghi ở D22.
- [x] **A7. Cho phép cài qua USB trên HyperOS.** Trong Tuỳ chọn nhà phát triển bật "Cài đặt qua USB" (và "Gỡ lỗi USB
  (Cài đặt bảo mật)" nếu cần cấp quyền qua adb). HyperOS thường đòi đăng nhập tài khoản Xiaomi để bật mục này — người dùng
  tự làm trên máy.
  **Kiểm:** ở E5, `installSideloadDebug` không báo `INSTALL_FAILED_USER_RESTRICTED`.

### B. Repo git

- [x] **B1.** `git init -b main`.
  **Kiểm:** `git status` báo `On branch main`.
- [x] **B2. `.gitignore`:** `.gradle/`, `build/`, `.kotlin/`, `local.properties`, `.idea/`, `*.iml`, `captures/`,
  `.cxx/`, `.externalNativeBuild/`, `*.jks`, `*.keystore`, `*.apk`, `*.aab`, `__pycache__/`.
  **Kiểm:** `git check-ignore -v local.properties app/build/x .gradle/x` báo cả ba bị bỏ qua.
- [x] **B3. `.gitattributes`:** `* text=auto eol=lf`; `*.bat`, `*.cmd` → `eol=crlf`; `*.wav`, `*.jar`, `*.png`,
  `*.webp` → `binary`.
  **Kiểm:** sau commit đầu, `git ls-files --eol gradlew gradlew.bat` báo `i/lf` cho cả hai, `w/crlf` cho `gradlew.bat`.
- [x] **B4. `.editorconfig`:** UTF-8, LF, thụt 4 dấu cách (Kotlin, Python, Gradle KTS), 2 cho YAML / JSON / TOML,
  `max_line_length = 120` như desktop; `[*.{bat,cmd}]` dùng CRLF.
  **Kiểm:** `git ls-files .editorconfig` có tệp sau commit đầu.

### C. Gradle

- [x] **C1. Chốt phiên bản.** Tra bản ổn định mới nhất của Gradle, AGP, Kotlin, Compose BOM, activity-compose,
  lifecycle, DataStore, kotlinx-coroutines, kotlinx-serialization, JUnit 5 (junit-bom); đối chiếu bảng tương thích
  AGP ↔ Gradle ↔ JDK ↔ Kotlin trên developer.android.com. Xem AGP bản đó còn cần plugin `org.jetbrains.kotlin.android`
  không hay đã có Kotlin dựng sẵn.
  **Kiểm:** bảng phiên bản đã chọn ghi vào mục Nhật ký, kèm nguồn.
- [x] **C2. `settings.gradle.kts`** tối thiểu (`rootProject.name = "GuzzMobile"`) để Gradle nhận ra đây là một build.
  **Kiểm:** tệp có, C3 chạy được.
- [x] **C3. Gradle wrapper.** Tải `gradle-<v>-bin.zip` từ services.gradle.org vào scratchpad, so SHA-256 với
  gradle.org/release-checksums, giải nén ở scratchpad, rồi tại `D:\GuzzMobile` chạy
  `<scratchpad>\gradle-<v>\bin\gradle.bat wrapper --gradle-version <v> --distribution-type bin --gradle-distribution-sha256-sum <sha>`.
  **Kiểm:** có `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`; `.\gradlew --version` báo đúng
  `<v>`, launcher JVM 21; `gradle-wrapper.properties` có `distributionSha256Sum`.
- [x] **C4. Ghim JVM của daemon.** `.\gradlew updateDaemonJvm --jvm-version=21` → `gradle/gradle-daemon-jvm.properties`.
  **Kiểm:** trong shell đặt tạm `$env:JAVA_HOME` = thư mục JDK 25.0.5, `.\gradlew --version` vẫn báo daemon JVM 21.
- [x] **C5. `gradle.properties`:** `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `org.gradle.caching=true`,
  `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, `android.useAndroidX=true`,
  `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.
  **Kiểm:** build ở E1 lần hai báo "Reusing configuration cache".
- [x] **C6. Version catalog** `gradle/libs.versions.toml`: phiên bản từ C1, thư viện, plugin (kể cả ID của convention
  plugin ở C7).
  **Kiểm:** `.\gradlew help` chạy được.
- [x] **C7. Convention plugin.** Included build `build-logic/` (`pluginManagement { includeBuild("build-logic") }` trong
  `settings.gradle.kts`), module `build-logic/convention`, các plugin:
  - `guzz.jvm.library`: Kotlin JVM, toolchain 21, bytecode 17, JUnit Platform + `kotlin-test-junit5`; task `test` có
    system property `guzz.testdata` = `<gốc repo>/testdata` và khai báo thư mục đó là `inputs` (đổi dữ liệu golden thì
    test chạy lại).
  - `guzz.android.library`: `com.android.library`, `compileSdk`, `minSdk 26`, bytecode 17, JUnit 4 cho unit test.
  - `guzz.android.application`: `com.android.application`, `targetSdk`; ABI release `arm64-v8a`, debug thêm `x86_64`
    (README §2); debug `applicationIdSuffix ".debug"` (D10).
  - `guzz.android.compose`: plugin Compose compiler của Kotlin, Compose BOM, Material 3, `ui-tooling` cho debug.
  **Kiểm:** `.\gradlew projects` liệt kê đúng các module ở D; `.\gradlew -p build-logic build` xanh.

### D. Module (D11)

- [x] **D1. `:core:common`** (`guzz.jvm.library`), package `io.github.cozycode890.guzz.core.common`. Bật
  `java-test-fixtures` để chứa công cụ đọc dữ liệu golden dùng chung (H3).
  **Kiểm:** `.\gradlew :core:common:build` xanh.
- [x] **D2. `:core:audio`** (`guzz.jvm.library`), package `io.github.cozycode890.guzz.core.audio`, phụ thuộc
  `:core:common`, test dùng `testFixtures(project(":core:common"))`.
  **Kiểm:** `.\gradlew :core:audio:build` xanh.
- [x] **D3. `:app`** (`guzz.android.application` + `guzz.android.compose`), namespace và applicationId
  `io.github.cozycode890.guzz` (D9), phụ thuộc `:core:common`. Nội dung ở E.
  **Kiểm:** `.\gradlew :app:help` chạy được.

### E. App rỗng và flavor

- [x] **E1. Màn hình "Guzz".** `GuzzApp : Application` dựng `AppContainer` (D13). `MainActivity` (ComponentActivity +
  `setContent`) dùng theme Material 3 (màu động trên Android 12+), hiện chữ "Guzz" và một dòng
  `<versionName> · <flavor> · <buildType>`.
  **Kiểm:** `.\gradlew assembleSideloadDebug` ra `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`.
- [x] **E2. Chuỗi giao diện.** `values/strings.xml` tiếng Việt (mặc định), `values-en/strings.xml` tiếng Anh; tên app
  bản debug "Guzz (debug)" (D10). Không có chuỗi viết cứng trong code Compose.
  **Kiểm:** `.\gradlew :app:lintSideloadDebug` không báo `MissingTranslation` hay `HardcodedText`.
- [x] **E3. Icon tạm.** Adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`), foreground là
  vector chữ "G", nền một màu. Icon chính thức để Stage 9.
  **Kiểm:** lint không báo thiếu icon; trên máy thật (E5) icon hiện ở màn hình chính.
- [x] **E4. Hai flavor.** Dimension `distribution`: `sideload` (`isDefault = true`) và `play`.
  `app/src/sideload/AndroidManifest.xml` thêm `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />`
  (kèm `tools:ignore="ScopedStorage"`); `buildConfigField("boolean", "ALL_FILES_ACCESS", ...)`: `true` ở sideload,
  `false` ở play.
  **Kiểm:** `.\gradlew assembleSideloadDebug assemblePlayDebug` xanh;
  `apkanalyzer manifest permissions <apk>`: APK sideload có `MANAGE_EXTERNAL_STORAGE`, APK play không có.
- [x] **E5. Cài lên máy thật.** `.\gradlew installSideloadDebug`, rồi
  `adb shell am start -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.MainActivity`.
  **Kiểm:** máy hiện "Guzz" và dòng `0.1.0 · sideload · debug`; `adb shell pidof io.github.cozycode890.guzz.debug` ra
  PID; `adb logcat -d -b crash` không có dòng nào của Guzz.

### F. Settings (D16)

- [x] **F1. Khai báo mục cài đặt** trong `:core:common`, package `...core.common.settings`: `SettingKey<T>` gồm
  `section`, `key`, `default`, `id = "$section.$key"` và cờ `sameAsDesktop` (mặc định `true`; `false` = mục chỉ có trên
  mobile hoặc cố ý khác mặc định của desktop). Các loại: bool, int (có chặn dưới / trên), double, string, choice (chuỗi
  trong một tập cho phép, như `_choice` của `config.py`). Stage 0 khai báo ba mục, ràng buộc lấy đúng theo `config.py`:
  `SYSTEM.log_level` ∈ {INFO, DEBUG}, không phân biệt hoa thường, mặc định INFO; `SYSTEM.log_size_mb` ≥ 1, mặc định 2;
  `SYSTEM.old_log_count` ≥ 0, mặc định 5. Danh sách mọi mục nằm ở một chỗ (`SettingKeys.all`) để đối chiếu và nhập / xuất
  sau này.
  **Kiểm:** test: `id` đúng dạng `SECTION.key`; không có hai mục trùng `id`.
- [x] **F2. `Settings`** bọc `DataStore<Preferences>` (`androidx.datastore:datastore-preferences-core`, chạy trên JVM):
  `get(key)`, `flow(key)`, `set(key, value)`, `reset(key)`. Giá trị lưu vi phạm ràng buộc (vd `log_size_mb = 0`, choice
  lạ) thì đọc ra giá trị đã chặn / mặc định, giống cách `config.py` đọc.
  **Kiểm:** `.\gradlew :core:common:test` xanh với các ca: chưa có gì → mặc định; `set` rồi `get`; đóng rồi mở lại
  DataStore trên cùng tệp (thư mục tạm của JUnit) vẫn còn giá trị; giá trị vi phạm ràng buộc → giá trị đã chặn.
- [x] **F3. Nối vào app.** `AppContainer` tạo DataStore ở `filesDir/datastore/settings.preferences_pb`.
  **Kiểm:** sau khi mở app trên máy, `adb shell run-as io.github.cozycode890.guzz.debug ls files/datastore` thấy tệp.

### G. Log (D14, D15)

- [x] **G1. Lõi log** trong `:core:common`, package `...core.common.log`: `LogLevel` (DEBUG, INFO, WARNING, ERROR, tên
  in ra như logging của Python), `LogSink`, interface `Logger` và đối tượng toàn cục `GuzzLog : Logger` chuyển bản ghi
  tới các sink đã gắn (tránh đặt tên `Log` cho khỏi trùng `android.util.Log`). Class nào cần log thì nhận `Logger` qua
  constructor, mặc định `GuzzLog`. Có `MemorySink` cho test.
  **Kiểm:** test: dòng in ra đúng mẫu `yyyy-MM-dd HH:mm:ss | INFO    | nội dung` (mức căn trái 7 ký tự, như
  `transcribe.py`); bản ghi dưới mức đang đặt bị bỏ; lỗi kèm stack trace.
- [x] **G2. `RotatingFileSink`** (JVM thuần, `:core:common`): cùng cách xoay với `RotatingFileHandler` mà desktop dùng —
  ghi thêm một bản ghi mà vượt `maxBytes` thì đổi `guzz.log.(N-1)` → `guzz.log.N`, …, `guzz.log` → `guzz.log.1`, giữ
  `old_log_count` tệp cũ. Riêng `old_log_count = 0`: đầy thì xoá trắng `guzz.log`, không giữ tệp cũ (D23, khác desktop).
  Ghi UTF-8, an toàn khi nhiều luồng cùng ghi.
  **Kiểm:** test với `maxBytes` nhỏ: đúng số tệp, đúng thứ tự (tệp `.1` mới nhất), tiếng Việt có dấu đọc lại đúng;
  `old_log_count = 0` thì chỉ còn một tệp và không bao giờ vượt `maxBytes`; nhiều luồng ghi song song không mất / lẫn dòng.
- [x] **G3. Nối vào app.** `LogcatSink` (tag `Guzz`) và `RotatingFileSink` ở `getExternalFilesDir(null)/logs/guzz.log`
  (`null` thì lùi về `filesDir/logs`). Gắn sink ngay trong `GuzzApp.onCreate` với giá trị mặc định, rồi cập nhật mức log /
  dung lượng theo `Settings.flow` trong scope của app (không đọc DataStore chặn luồng chính). Bắt lỗi chưa xử lý
  (`Thread.setDefaultUncaughtExceptionHandler`): ghi ERROR kèm stack trace rồi chuyển cho handler cũ. Lúc khởi động ghi
  một dòng `Guzz <versionName> (<flavor>/<buildType>) khởi động trên <hãng> <model>, Android <bản> (API <số>)`.
  **Kiểm:** mở app trên máy;
  `adb pull /sdcard/Android/data/io.github.cozycode890.guzz.debug/files/logs/guzz.log` có dòng khởi động đúng mẫu;
  `adb logcat -d -s Guzz` có cùng dòng; `adb shell am crash io.github.cozycode890.guzz.debug` rồi pull lại thì tệp có dòng
  ERROR kèm stack trace.

### H. Golden test (D18)

- [x] **H1. Khung `tools/golden/`.**
  - `README.md`: cách chạy, quy ước tên tệp, thêm một bộ golden mới thế nào.
  - `_common.py`: tìm Guzz desktop (biến môi trường `GUZZ_DESKTOP_DIR`, mặc định `D:\Guzz`) và thêm vào `sys.path`; ghi
    WAV 16-bit mono bằng `wave` (thư viện chuẩn, không cần ffmpeg); ghi JSON (UTF-8, thụt 2, khoá sắp xếp) kèm `meta`:
    tên script, commit của Guzz desktop (`git rev-parse HEAD`, có cờ khi thư mục có sửa đổi chưa commit), phiên bản Python
    và numpy. Lưu ý: Python embeddable có tệp `._pth` nên **không** tự thêm thư mục của script vào `sys.path` và bỏ qua
    `PYTHONPATH` → mỗi `gen_*.py` tự `sys.path.insert(0, <thư mục của nó>)` trước khi `import _common`.
  - `regenerate.ps1`: chạy lần lượt mọi `gen_*.py` bằng `<GUZZ_DESKTOP_DIR>\runtime\python\python.exe -B -s`, dừng ở lỗi
    đầu tiên.
  **Kiểm:** `.\tools\golden\regenerate.ps1` chạy hết không lỗi; không đụng vào desktop: ghi `$start = Get-Date` trước khi
  chạy, sau đó
  `Get-ChildItem D:\Guzz -Recurse -File | ? { $_.FullName -notlike 'D:\Guzz\runtime\*' -and $_.LastWriteTime -gt $start }`
  trống (gồm cả `__pycache__`, thứ `git status` không thấy); chạy lần hai thì `git diff --exit-code testdata/golden`
  trống (kết quả tất định).
- [x] **H2. Dữ liệu chunker** (`tools/golden/gen_chunker.py` → `testdata/golden/chunker/`). Tín hiệu tổng hợp có seed cố
  định, tần số lấy mẫu thấp (vd 1000 Hz, một khối 50 ms = 50 mẫu) và tham số nhỏ (`chunk_minutes` 0,1–0,5, `search_seconds`
  vài giây) để WAV gọn. Script ghi WAV, **đọc lại** ra float32 (`int16 / 32768`) rồi mới gọi `chunker.find_split_points`,
  để hai bản nhận đúng một mảng đầu vào. Các ca:
  (a) tệp ngắn hơn `length + min_last` → `[0, n]`, kể cả đúng bằng;
  (b) nhiều đoạn, mỗi cửa sổ tìm có một chỗ lặng rõ;
  (c) `search_seconds = 0` → cắt đúng mốc;
  (d) đoạn cuối ngắn hơn `min_last_chunk_seconds` → gộp vào đoạn trước;
  (e) chỗ lặng sâu hơn nằm ngoài cửa sổ tìm → không được chọn;
  (f) ồn liên tục không có chỗ lặng.
  Python tính năng lượng bằng float32, Kotlin sẽ tính bằng double → script tự kiểm, trong mỗi cửa sổ, giá trị nhỏ nhất và
  nhì của năng lượng đã làm mượt cách nhau tương đối > 1e-4; không đủ thì đổi seed. JSON:
  `{meta, cases: [{name, wav, sr, chunk_minutes, search_seconds, min_last_chunk_seconds, expected}]}`.
  **Kiểm:** các tệp nằm trong `testdata/golden/chunker/`, tổng dưới 1 MB; script in biên an toàn nhỏ nhất của từng ca.
- [x] **H3. Công cụ đọc golden** trong test fixtures của `:core:common`: tìm thư mục từ system property `guzz.testdata`
  (báo lỗi rõ ràng nếu thiếu), đọc JSON bằng kotlinx.serialization, đọc WAV 16-bit mono ra `FloatArray` (`/ 32768f`).
  **Kiểm:** test của chính công cụ: đọc một WAV nhỏ tạo sẵn → đúng số mẫu, đúng vài giá trị.
- [x] **H4. Port `find_split_points`** sang `:core:audio`, tệp `Chunker.kt`, KDoc ghi
  `Port của chunker.py: find_split_points` (kèm `_block_energy`, `_moving_average`). Chỗ dễ lệch:
  `int(round(x))` của Python làm tròn nửa về số chẵn → dùng `Math.rint`, không dùng `roundToInt`; `int(...)` cắt về 0;
  `//` chia lấy phần nguyên; `np.argmin` lấy chỉ số nhỏ nhất khi bằng nhau → so sánh `<`.
  **Kiểm:** biên dịch được; KDoc có dòng nguồn.
- [x] **H5. Golden test chunker:** `ChunkerGoldenTest`, mỗi ca trong JSON một test (`@TestFactory` hoặc
  `@ParameterizedTest`), tên test = tên ca; so **khớp tuyệt đối** danh sách điểm cắt.
  **Kiểm:** `.\gradlew :core:audio:test` xanh; báo cáo test liệt kê đủ sáu ca.
- [x] **H6. Thử test đỏ.** Đổi tạm `BLOCK_MS` 50 → 40 trong Kotlin → `:core:audio:test` đỏ ít nhất một ca; trả về 50 →
  xanh.
  **Kiểm:** ghi kết quả hai lần chạy (ca nào đỏ) vào Nhật ký. Đây là tiêu chí hoàn thành thứ ba.
- [x] **H7. Đối chiếu Settings với desktop.** `tools/golden/gen_config_keys.py` đọc `config.default.txt` bằng chính
  `config._read_parser` của desktop → `testdata/golden/config/default_config.json` (`{SECTION: {key: giá trị dạng chữ}}`).
  Test ở `:core:common`: mọi `SettingKey` có `sameAsDesktop = true` phải có `SECTION.key` trong JSON, và mặc định (viết ra
  chữ theo kiểu config.txt) trùng giá trị của desktop.
  **Kiểm:** test xanh; đổi tạm mặc định `SYSTEM.old_log_count` 5 → 6 thì đỏ, trả lại thì xanh.

### I. Lệnh và tài liệu

- [x] **I1. Điền "Lệnh thường dùng" trong CLAUDE.md:** build (`.\gradlew assembleSideloadDebug`, `assemblePlayDebug`), test
  (`.\gradlew test`), lint (`.\gradlew lint`), cài và mở trên máy (`installSideloadDebug` + `adb shell am start …`), lấy
  nhật ký (`adb pull …/files/logs`), tạo lại dữ liệu golden (`.\tools\golden\regenerate.ps1`), và việc cần làm một lần
  trên máy mới (JDK 21, `JAVA_HOME`, `ANDROID_HOME`, gói SDK).
  **Kiểm:** chạy lại từng lệnh đã ghi, lệnh nào cũng chạy được đúng như chép.
- [x] **I2. Cập nhật "Môi trường máy" trong CLAUDE.md:** JDK 21 cho Gradle, `JAVA_HOME`, `ANDROID_HOME`, adb nào được dùng,
  máy thật ở D22.
  **Kiểm:** đọc lại mục, khớp kết quả của A1–A6.
- [x] **I3. `tools/golden/README.md`** và KDoc đủ để session sau thêm golden test mới mà không cần hỏi lại.
  **Kiểm:** README có ví dụ thêm một bộ golden từng bước.

### J. Commit, GitHub, clone sạch

- [x] **J1. Commit đầu.** Trước khi commit: `git status` không có `local.properties`, `build/`, API key hay bản ghi thật;
  `git ls-files *.wav` chỉ ra tệp trong `testdata/golden/`. Commit message tiếng Việt, vd
  "Khởi tạo Guzz Mobile: khung Gradle, app rỗng, Settings, nhật ký, golden test mẫu".
  **Kiểm:** `git log --oneline` có commit; `git status` sạch.
- [x] **J2. Build từ bản clone sạch.** `git clone D:\GuzzMobile <scratchpad>\guzz-clone`, đặt tạm
  `$env:GUZZ_DESKTOP_DIR` trỏ tới một thư mục không tồn tại (chắc chắn test không đọc desktop), rồi chạy **đúng** các lệnh
  build / test đã ghi trong CLAUDE.md.
  **Kiểm:** `BUILD SUCCESSFUL`; báo cáo test có golden test chunker. Đây là tiêu chí hoàn thành thứ nhất.
- [x] **J3. Đẩy lên GitHub (D21).** Đẩy lên là việc ra ngoài máy, nên **hỏi lại người dùng** ngay trước khi chạy:
  `gh repo create CozyCode890/GuzzMobile --private --source . --remote origin --push`.
  **Kiểm:** `gh repo view CozyCode890/GuzzMobile --json visibility` ra `PRIVATE`; `git status -sb` báo
  `## main...origin/main`.

### K. Đóng stage

- [x] **K1.** Tick đủ "Tiêu chí hoàn thành"; CLAUDE.md: Stage 0 → ✅ kèm ngày, "Stage hiện tại" và "Việc tiếp theo" → plan
  chi tiết Stage 1 (Stage 3 làm song song được). Commit, push.
  **Kiểm:** `git log -1` có commit đóng stage; bảng trong CLAUDE.md ghi ✅.

## Rủi ro

- JDK 25 không hợp phiên bản Gradle/AGP → dùng JDK 21 cho Gradle (D19).
- AGP / Kotlin bản mới nhất có thể chưa hợp nhau → C1 đối chiếu bảng tương thích; vướng thì lùi một bản phụ và ghi
  Nhật ký.
- Golden lệch do sai số dấu phẩy động (Python float32, Kotlin double) → dữ liệu tổng hợp có biên an toàn (H2); Stage 2 so
  trên dữ liệu thật cho phép lệch một khối.
- Một vài ROM chặn `adb pull` từ `Android/data` → lùi về `adb shell run-as <package> cat …` (chỉ bản debug).
- HyperOS chặn cài APK qua adb khi chưa bật "Cài đặt qua USB" (A7).

## Nhật ký

- **2026-10-31, A1.** `JAVA_HOME` mức Machine vốn đã trỏ JDK 21; chỉ mức User trỏ tới JDK 25 đã gỡ (mức User đè mức
  Machine). Đặt mức User = `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`. Tiến trình của Claude giữ biến môi
  trường cũ, nên "shell mới" được kiểm bằng cách đọc giá trị từ registry rồi chạy `java -version` → `21.0.13`.
- **2026-10-31, A2.** Tải `commandlinetools-win-15859902_latest.zip` (cmdline-tools 22.0), SHA-256
  `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` khớp số trên developer.android.com/studio. Thêm
  `%ANDROID_HOME%\cmdline-tools\latest\bin` vào `PATH` của User (giữ kiểu `REG_EXPAND_SZ`). `sdkmanager` 22.0 in cảnh báo
  "deprecated, dùng Android CLI (`android sdk`)"; vẫn dùng `sdkmanager` như plan vì vẫn chạy bình thường.
  `sdkmanager --list` cho thấy kho đã có cmdline-tools 23.0 (trang tải vẫn ghi 22.0); chưa nâng.
- **2026-10-31, C1. Bảng phiên bản** (tra metadata Maven ngày 2026-10-31):

  | Thành phần | Mới nhất ổn định | Chọn | Nguồn / ghi chú |
  | --- | --- | --- | --- |
  | Gradle | 9.8.2 | **9.7.1** | services.gradle.org/versions; D24 |
  | AGP | 9.5.0 | **9.3.3** | dl.google.com maven `com.android.tools.build:gradle`; D24. AGP 9.3: Gradle ≥ 9.5.0, JDK ≥ 17, build-tools ≥ 36.0.0, API tối đa 37 (release notes AGP 9.3.0) |
  | Kotlin | 2.4.21 (2.5.0 đang Beta) | **2.4.21** | Maven Central `kotlin-gradle-plugin`. KGP 2.4.20–2.4.21 kiểm với Gradle 7.6.3–9.7.0, AGP 8.5.2–9.3.1 (kotlinlang.org/docs/gradle-configure-project.html) |
  | Compose BOM | 2026.09.00 | **2026.09.00** | dl.google.com maven |
  | activity-compose | 1.13.0 | **1.13.0** | dl.google.com maven |
  | lifecycle | 2.11.0 | chưa dùng | activity-compose kéo theo; khai báo khi cần |
  | DataStore | 1.3.0 | **1.3.0** | `datastore-preferences-core` |
  | kotlinx-coroutines | 1.11.0 | **1.11.0** | Maven Central |
  | kotlinx-serialization | 1.12.0 | **1.12.0** | Maven Central |
  | JUnit | 6.1.3 (dòng 5: 5.14.4) | **6.1.3** | `org.junit:junit-bom`; D25 |
  | JUnit 4 | 4.13.2 | **4.13.2** | cho module Android (D17) |
  | Android SDK | platform 37.0 / 37.1 / 37.2, build-tools 37.0.0, platform-tools 37.0.1 | **compileSdk = targetSdk = 37**, build-tools 37.0.0 | `sdkmanager --list`; chọn API 37 (bản chính), chưa dùng bản phụ 37.x |

  AGP 9 có Kotlin dựng sẵn (developer.android.com/build/migrate-to-built-in-kotlin): **không** áp
  `org.jetbrains.kotlin.android`; `jvmTarget` mặc định theo `compileOptions.targetCompatibility`. POM của AGP 9.3.3 khai
  KGP 2.2.10, phiên bản gốc `plugins { kotlin.jvm 2.4.21 apply false }` đè lên.
- **2026-10-31, C3.** SHA-256 của `gradle-9.7.1-bin.zip` (`acd53f1e…f804d20a`) và của `gradle-wrapper.jar`
  (`7a9ce74c…64262c5d`) đều khớp gradle.org/release-checksums. `.\gradlew --version`: Gradle 9.7.1, launcher JVM 21.0.13.
- **2026-10-31, C4.** `updateDaemonJvm --jvm-version=21` lỗi "Toolchain download repositories have not been configured"
  → hỏi người dùng, chọn viết tay (D26). Kiểm: đặt tạm `JAVA_HOME` = JDK 25.0.5, `gradlew --version` báo launcher 25 nhưng
  daemon "Compatible with Java 21 (from gradle/gradle-daemon-jvm.properties)"; `--info` cho thấy lệnh khởi động daemon là
  `jdk-21.0.13.9-hotspot\bin\java.exe`.
- **2026-10-31, C7.** Convention plugin phụ thuộc `compileOnly` vào AGP / KGP / Compose compiler plugin; phiên bản thật lấy
  từ `plugins { … apply false }` ở `build.gradle.kts` gốc. `guzz.testdata` truyền bằng `CommandLineArgumentProvider`
  (`TestdataArgumentProvider`, input `@InputFiles` + `PathSensitivity.RELATIVE`) thay vì `systemProperty`, để đường dẫn
  tuyệt đối không vào khoá build cache và module chưa có dữ liệu golden vẫn build được.
- **2026-10-31, A3.** Đã chạy `sdkmanager --licenses` trong một tab terminal cho người dùng tự đọc và đồng ý (8 giấy phép);
  người dùng đi ngủ trước khi trả lời, nên A4–A5 và mọi bước cần build `:app` (E, F3, G3, I1, J, K) chờ tới khi đồng ý.
  Người dùng dặn: câu hỏi trắc nghiệm phát sinh trong lúc vắng thì chọn phương án Recommended.
- **2026-10-31, F2.** Test dùng `runBlocking` + `@TempDir`, không cần `kotlinx-coroutines-test` (không thêm vào module).
  DataStore chỉ cho một instance mở một tệp: test huỷ scope (`cancelAndJoin`) trước khi mở lại.
- **2026-10-31, G1–G2.** Đã thử thật `RotatingFileHandler` của Python 3.12.10 trong runtime desktop: tệp rỗng không bao giờ
  xoay (một bản ghi dài hơn `maxBytes` vẫn được ghi trọn); điều kiện xoay so `len(msg)` theo **ký tự** (12 ký tự "ễ" =
  36 byte mà không xoay); `backupCount = 0` thì tệp lớn mãi (đúng lý do của D23). `RotatingFileSink` giữ hai điểm đầu
  nhưng so theo **byte** UTF-8 để dung lượng luôn bị chặn; ghi trong KDoc. Thêm `MemorySink` và `LogFormatter` (mẫu dòng
  dùng chung cho tệp và test).
- **2026-10-31, F1.** Thêm `formatPythonG` (port `f"{x:g}"` của Python) cho `DoubleSetting.toConfigText`; đáp án test lấy từ
  Python của desktop.
- **2026-10-31, H1–H3.** `_common.py` gọi git của desktop bằng `git --no-optional-locks` để `git status` không ghi lại
  `.git/index` của `D:\Guzz`. Thêm `gen_wav_io.py` (một WAV 11 mẫu đã biết, gồm hai đầu thang int16) làm "WAV nhỏ tạo
  sẵn" cho test của công cụ đọc ở H3, để định dạng Python ghi và Kotlin đọc được kiểm cùng nhau. Kiểm H1: chạy
  `regenerate.ps1`, không có tệp nào trong `D:\Guzz` (trừ `runtime\`, tính cả `.git`) có giờ sửa sau lúc bắt đầu; `git add`
  rồi chạy lần hai, `git diff --exit-code testdata/golden` trống. Desktop có sửa đổi chưa commit (đang refactor) nên `meta`
  ghi `guzz_desktop_dirty: true`, commit `97e42598`.
- **2026-10-31, H2.** Bảy ca thay vì sáu: (a) tách thành "ngắn hơn" và "đúng bằng" `length + min_last`. Tổng
  `testdata/golden/chunker/` 170 KB. Biên an toàn nhỏ nhất: b 8,4e-3; d 1,2e-2; e 6,9e-3; f 1,5e-2 (a, c không có cửa sổ
  tìm).
- **2026-10-31, H6.** `BLOCK_MS` = 40 → `:core:audio:test` đỏ 4/7 ca: `b_silence_in_each_window` (ra
  `[0, 6380, 12300, 18620, 25000]`, cần `[0, 6425, 12175, 18675, 25000]`), `d_short_last_chunk_merged`,
  `e_deeper_silence_outside_window_ignored`, `f_continuous_noise`; ba ca a/c không phụ thuộc khối nên vẫn xanh. Trả về 50
  → xanh 7/7 (chạy `--rerun` để chắc không lấy kết quả từ build cache).
- **2026-10-31, H7.** Mặc định `old_log_count` = 6 → `DesktopDefaultsTest > SYSTEM.old_log_count` đỏ ("expected <5> but was
  <6>"); trả về 5 → xanh. Mỗi `SettingKey` là một dynamic test, tên = `SECTION.key`.
- **2026-11-01, A3–A4.** Người dùng không gõ được vào tab terminal chạy `sdkmanager --licenses`. Họ chọn trong chat cho
  Claude gõ `y` thay, sau khi xác nhận đã đọc và đồng ý Điều khoản Android SDK. Claude dừng lệnh `--licenses` (lệnh này
  hỏi đủ 8 giấy phép), rồi chạy thẳng lệnh A4 `sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"`.
  Lệnh này chỉ hỏi đúng một giấy phép các gói cần là `android-sdk-license`; Claude chấp nhận giấy phép đó và không chấp nhận
  giấy phép nào khác. Đã cài: build-tools 37.0.0, platform-tools 37.0.1, platforms;android-37.0 rev 2. Dòng "Lệnh thường
  dùng" ghi lệnh `sdkmanager --licenses` để người dùng tự chạy trên máy mới.
- **2026-11-01, A5.** Hai bản adb (winget `Google.PlatformTools` và `%ANDROID_HOME%\platform-tools`) cùng là
  `37.0.1-15733141`, nên không phải hỏi người dùng. Giữ nguyên `PATH`: chỉ bản winget có trên `PATH`
  (`Get-Command adb -All` ra đúng một đường dẫn). Gradle dùng bản trong SDK. Hai bản cùng phiên bản nên không bị
  "server version doesn't match". Khi một trong hai bản được cập nhật mà bản kia không, sẽ phải chọn lại.
- **2026-11-01, J1–J2.** Commit đầu là `196e6d3`. Bản clone sạch ở scratchpad, `GUZZ_DESKTOP_DIR` trỏ tới một thư mục
  không tồn tại. Chạy đúng bốn lệnh `assembleSideloadDebug`, `assemblePlayDebug`, `test`, `lint` của CLAUDE.md: đều
  `BUILD SUCCESSFUL`. Riêng task test lấy kết quả từ build cache dùng chung của máy (`FROM-CACHE`), nên chạy thêm
  `.\gradlew clean test --no-build-cache` để test chạy thật trên bản clone: 56 test xanh, có 7 ca của `ChunkerGoldenTest`.
  Máy mới không có build cache thì `test` tự chạy thật.
- **2026-11-01, J3.** Người dùng đồng ý trong chat, đã chạy `gh repo create … --private --source . --remote origin --push`:
  `PRIVATE`, `main...origin/main`.
- **2026-11-01, A7, E3, E5.** `installSideloadDebug` không báo `INSTALL_FAILED_USER_RESTRICTED`, nghĩa là "Cài đặt qua USB"
  đã bật. Ảnh chụp màn hình và `uiautomator dump` cho thấy "Guzz", "Turn audio and video into text" và
  `0.1.0 · sideload · debug`. Câu thứ hai là bản tiếng Anh vì điện thoại đang để tiếng Anh, tức `values-en` chạy đúng.
  `pidof` ra PID, `logcat -b crash` trống. Màn hình chính của HyperOS dùng ngăn ứng dụng, không đặt icon ra trang đầu;
  người dùng mở ngăn ứng dụng xác nhận icon "Guzz (debug)" hiện đúng.
- **2026-11-01, F3 (D27).** Lần kiểm đầu không có `files/datastore/`, vì DataStore chỉ tạo tệp ở lần ghi đầu mà Stage 0
  chỉ đọc. Hỏi người dùng, chọn D27: thêm `APP.last_version_code` và `Settings.recordAppVersion`. `GuzzApp` gọi hàm này
  ở nền lúc mở app, có 3 test JVM mới. Cài lại lên máy: có `settings.preferences_pb` (29 byte), log ghi "Lần mở đầu tiên
  trên máy này (versionCode 1)". `force-stop` rồi mở lại thì tệp không bị ghi lại, không có dòng log mới.
- **2026-11-01, G3, I1.** `am crash` → `guzz.log` có `ERROR | Lỗi chưa xử lý ở luồng main` kèm stack trace
  `CrashedByAdbException`. Handler cũ vẫn chạy: `AndroidRuntime: FATAL EXCEPTION`, tiến trình thoát. Lệnh `adb pull …/logs`
  chạy lần hai khi thư mục đích đã có thì chép vào thư mục con `logs\`, nên CLAUDE.md đổi sang `…/logs/.`; đã kiểm cả khi
  thư mục đích chưa có lẫn khi đã có. Các lệnh khác của "Lệnh thường dùng" đều đã chạy đúng như chép. Riêng
  `sdkmanager --licenses` không chạy lại, vì đó là việc người dùng tự quyết.
- **2026-10-31, F2.** `Settings.create` thêm `ReplaceFileCorruptionHandler { emptyPreferences() }`: tệp cài đặt hỏng thì mọi
  mục về mặc định và ghi tiếp được, thay vì app ném `CorruptionException` lúc đọc (giống `config.py` đọc lỗi thì dùng mặc
  định). Có test riêng.
- **2026-10-31, E3.** Icon đặt ở `mipmap-anydpi/` thay vì `mipmap-anydpi-v26/`: minSdk đã là 26 nên hậu tố `-v26` thừa
  (lint báo `ObsoleteSdkInt`). Có thêm lớp `monochrome` cho icon theo chủ đề của Android 13+.

## Phát sinh
