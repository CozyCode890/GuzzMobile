# Stage 9 — Đóng gói & phát hành

**Trạng thái:** 🔨 đang làm (plan 2026-11-18) · **Cần xong trước:** 8

## Mục tiêu

Có APK release đã ký, phát hành qua GitHub Releases. Trước khi phát hành, rà lỗ hổng bảo mật và tinh gọn phần dư thừa.
Đã đo dung lượng, tốc độ, pin, nhiệt; chạy được trên máy thật Xiaomi và máy ảo Android cũ.

## Phạm vi

### Làm

- Nâng AGP / Gradle / thư viện lên bộ mới nhất mà Kotlin đã phủ (D134); lúc làm, AGP / Gradle đã ở mức đó, chỉ nâng thư
  viện (D136).
- Rà lỗ hổng: checklist Android + đọc kỹ mặt tấn công, quét bí mật trong lịch sử git, quét lỗ hổng thư viện (D132).
- Tinh gọn: code chết, tài nguyên và phụ thuộc thừa, hết cảnh báo lint; code chỉ cho debug không lọt vào release (D133).
- R8 / minify (kèm keep rules cho JNI của sherpa-onnx); chỉ ABI `arm64-v8a`; bỏ `.so` thừa; nén `.so`; APK ≤ 50 MB (D128).
- Model trong assets: đã kiểm lúc plan, không nén hai lần (xem "Ghi chú kỹ thuật"); giữ đọc thẳng từ assets.
- Ký release bằng khoá Morphe có sẵn, chuyển sang PKCS12, để ngoài repo; build tay trên PC (D122, D129, D137); số phiên
  bản 1.0.0 (D130).
- Icon chính thức từ icon desktop (D127); trang Giới thiệu: giấy phép đủ toàn văn, ghi chú quyền riêng tư, link Releases
  (D126).
- Repo chuyển public: LICENSE MIT, README, CHANGELOG; giữ lịch sử git, chỉ sửa tệp hiện tại (D123–D125).
- Thử trên Xiaomi 15T + máy ảo API 26 và API 30; đo bài 2 giờ trên bản release: thời gian, pin, nhiệt (D131, D135).
- Kiểm flavor `play` vẫn build được (chưa phát hành).

### Không làm

- Phát hành lên Google Play.
- GitHub Actions (D122); app tự kiểm tra bản mới (D126).
- Cảnh báo / tạm dừng khi pin yếu (D135).
- Review toàn bộ code bằng agent; dọn `spikes/` và script cũ; `/simplify` toàn repo (D132, D133).
- Đổi lệnh `sdkmanager` trong CLAUDE.md sang `android sdk` (Phát sinh Stage 0): chỉ làm khi `sdkmanager` thôi chạy.

## Nguồn tham khảo trong Guzz desktop

- README desktop §12 (chuẩn bị bản cài đặt)
- `D:\Guzz\gui\assets\icon.png` (icon, D127)

## Kết quả bàn giao

- Bản phát hành đầu tiên `v1.0.0` trên GitHub Releases của repo public `CozyCode890/GuzzMobile`.
- `tools/release.ps1`, `CHANGELOG.md`, `README.md`, `LICENSE` (MIT).
- Kết quả rà lỗ hổng (gitleaks, osv-scanner, checklist) ghi trong Nhật ký.

## Tiêu chí hoàn thành

- [ ] Cài APK release lên máy chưa từng cài Guzz: chạy được hết luồng chính.
- [ ] Cài đè bản mới lên bản cũ: giữ nguyên dữ liệu và cài đặt.
- [ ] Dung lượng APK ≤ 50 MB (D128).
- [ ] gitleaks không còn phát hiện thật trong lịch sử; osv-scanner không còn lỗ hổng High / Critical chưa xử lý (hoặc đã
      ghi lý do chấp nhận); checklist bảo mật (B1) đủ dấu.
- [ ] `.\gradlew lint` 0 cảnh báo (cảnh báo cố ý thì tắt tại chỗ kèm lý do); APK release không chứa class / receiver chỉ
      dành cho debug.
- [ ] Repo public có `LICENSE`, `README.md`, `CHANGELOG.md`; không còn IP / đường dẫn cá nhân trong các tệp hiện tại.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt ngày 2026-11-18, xem "Quyết định".

- Build release bằng GitHub Actions hay build tay trên máy? → D122
- App có tự kiểm tra bản mới (qua GitHub API) không? → D126
- Tên hiển thị và icon chính thức? → D127
- Mức dung lượng APK chấp nhận được? → D128

## Rủi ro

- Mất keystore → sao lưu vào trình quản lý mật khẩu ngay sau khi chuyển sang PKCS12 (D129, D137), kiểm bằng cách khôi
  phục thử (E2). Khoá Morphe cũng ký các app Morphe đã vá: lộ khoá thì ảnh hưởng cả hai.
- R8 xoá / đổi tên class mà JNI của sherpa-onnx tìm theo tên → app chết lúc tách giọng chỉ ở bản release. → Keep rules,
  và thử tách giọng trên bản release (D4, F1).
- Nâng AGP / Gradle làm hỏng plugin `guzz.deps` hoặc convention plugin → nâng ở commit riêng đầu stage, hỏng khó gỡ thì
  quay về bộ cũ và ghi Nhật ký (A2).
- Chuyển public làm lộ thứ đã lỡ commit → gitleaks trên toàn lịch sử trước (B2); có bí mật thật thì dừng, hỏi người dùng.

## Quyết định

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D122 | Build release ở đâu | **Build tay trên PC** bằng `tools/release.ps1`: kiểm cây git sạch, `test lint`, build `sideloadRelease`, ký, `apksigner verify`, in SHA-256; không CI | Keystore không rời máy; ít việc thiết lập nhất. |
| D123 | Repo khi phát hành (D21) | **Chuyển `CozyCode890/GuzzMobile` sang public**, hỏi người dùng ngay trước khi bấm chuyển | Ai cũng tải được APK ở Releases. |
| D124 | Lịch sử git khi public | **Giữ lịch sử**; quét gitleaks, không có bí mật thật thì chỉ thay IP Tailscale / đường dẫn cá nhân ở tệp hiện tại (đưa sang `CLAUDE.local.md` không commit); có bí mật thật thì dừng, hỏi | Email tác giả đã công khai qua repo desktop; IP Tailscale chỉ dùng được trong tailnet. Không đổi mã commit. |
| D125 | Giấy phép mã nguồn | **MIT** | Ngắn, hợp với mọi phụ thuộc (Apache-2.0, MIT). |
| D126 | Tự kiểm tra bản mới | **Không**; trang Giới thiệu có link tới trang Releases | Không thêm kết nối mạng ngầm. |
| D127 | Tên và icon | **"Guzz" + icon desktop** vẽ lại thành adaptive icon vector (nền gradient xanh, sóng âm + vạch chữ trắng), thêm lớp monochrome cho Android 13+ | Hai app trông giống nhau. |
| D128 | Dung lượng APK | **≤ 50 MB, nén `.so`** (`packaging.jniLibs.useLegacyPackaging = true`), bỏ `libsherpa-onnx-c-api.so` / `-cxx-api.so` | Tải qua GitHub nhẹ hơn (ước tính ~45 MB); đổi lại tốn thêm ~27 MB bộ nhớ máy khi cài. |
| D129 | Keystore | **Ngoài repo** (`D:\GuzzMobileData\keys\`) + **bản sao trong trình quản lý mật khẩu** (tệp .jks đính kèm, mật khẩu); Gradle đọc đường dẫn và mật khẩu từ biến môi trường. Người dùng tự chạy `keytool` và tự gõ mật khẩu | Mất keystore là không cài đè được nữa; mật khẩu không nằm trong tệp nào. |
| D130 | Số phiên bản | **SemVer, bản đầu 1.0.0, tag `v1.0.0`**; `versionCode = MAJOR×10000 + MINOR×100 + PATCH`, khai một chỗ trong `gradle/libs.versions.toml` | versionCode luôn tăng theo số, không sửa tay. |
| D131 | Thử máy | **Xiaomi 15T + máy ảo API 26 và API 30**; đo bài 2 giờ trên bản release ngay trong lúc thử | Chỉ có một máy thật; máy ảo phủ minSdk 26 và Android 11. |
| D132 | Rà lỗ hổng | **Checklist Android + đọc kỹ mặt tấn công** (thành phần exported, extra, đường dẫn, key, log, mạng), **gitleaks** trên lịch sử git, **osv-scanner** cho phụ thuộc; không review toàn bộ code bằng agent | Phủ phần app khác gọi vào được và thứ sắp công khai, tốn ít token. |
| D133 | Tinh gọn | **Code chết, tài nguyên, phụ thuộc thừa** (lint, báo cáo R8) + **hết cảnh báo lint** + **code chỉ cho debug không lọt release**; không dọn `spikes/`, không `/simplify` | Theo công cụ, ít rủi ro sửa nhầm. |
| D134 | Nâng AGP / Gradle / thư viện | **Nâng lên bộ mới nhất Kotlin đã phủ**, commit riêng đầu stage, chạy lại test + lint + test thiết bị | Theo Phát sinh Stage 0; phát hành trên bộ build mới. |
| D135 | Pin và nhiệt | **Không thêm tính năng, chỉ đo** bài 2 giờ trên bản release (giữ ô số luồng hiện có); bất thường thì hỏi lại | Người dùng ít thời gian; đã có ô số luồng. |
| D136 | Nâng AGP / Gradle khi Kotlin 2.4.21 (ổn định mới nhất, 2.5.0 mới RC) chỉ phủ tới AGP 9.3.1 / Gradle 9.7.0 (A1) | **Giữ AGP 9.3.3 / Gradle 9.7.1**, chỉ nâng appcompat 1.8.0 và kotlinx-serialization 1.13.0; tắt lint `AndroidGradlePluginVersion` kèm lý do | Đúng D134: bộ hiện tại đã là mức cao nhất Kotlin phủ. |
| D137 | Khoá ký (thay phần tạo khoá mới của D129) | **Dùng khoá Morphe có sẵn** (`Downloads\Morphe.keystore`, dạng BKS của Morphe Manager): người dùng tự chạy lệnh chuyển sang PKCS12 (cần thư viện BouncyCastle cho `keytool`) ra `D:\GuzzMobileData\keys\`, tự gõ mật khẩu; sao lưu như D129 | Người dùng chọn, không tạo khoá mới. Gradle / apksigner không đọc BKS. |
| D138 | Mã bí mật mặc định trống (B1): khi bật lệnh từ app khác, mọi app trên máy gửi được TRANSCRIBE | **Tự tạo mã khi bật công tắc mà ô đang trống**; vẫn cho xoá trắng | Không phải app nào trên máy cũng dùng được key và hạn mức của người dùng; người cần thì vẫn bỏ được. |
| D139 | Thử máy ảo (F3, thay một phần D131) | **Chỉ máy ảo API 26 (Android 8.0, image AOSP `default`), chỉ giao diện**: cài bản release, mở 3 tab + 9 trang Cài đặt + "Nâng cao" của Google AI; không gửi Gemini (phải dán key vào máy ảo), bỏ API 30 | Người dùng chọn, đỡ RAM laptop; giải mã / tách giọng / gửi đã thử thật trên Xiaomi (Android 16). |

## Kế hoạch chi tiết

Thứ tự: nâng build (A) → rà lỗ hổng (B) và tinh gọn (C) trên code hiện tại → cấu hình release (D) → nội dung phát hành
(E) → thử trên máy (F) → công khai và phát hành (G). Mỗi nhóm một hoặc vài commit tiếng Việt trên `main`.

Quy tắc giữ suốt stage:

- Không commit keystore, mật khẩu ký, API key, token, bản ghi thật. `.gitignore` đã chặn `*.jks`, `*.keystore`, `*.apk`.
- Claude không gõ mật khẩu keystore, không nhập API key vào app: người dùng tự làm.
- Tải công cụ (gitleaks, osv-scanner, system image máy ảo): hỏi người dùng trước, nêu tên tệp, nguồn, dung lượng.
- Gửi thật lên Gemini: hỏi ngay trước khi gửi. Chuyển repo public, push tag, tạo Release: hỏi ngay trước khi làm.
- Lệnh đổi trạng thái hệ thống của điện thoại: soạn sẵn lệnh và lệnh hoàn tác để người dùng tự chạy.
- Đặt `ANDROID_SERIAL` trước mọi lệnh `gradlew install*` / `adb`.

### Ghi chú kỹ thuật (kiểm lúc plan, 2026-11-18, APK debug)

- Model trong assets **không nén hai lần**: `.onnx` là protobuf thô, APK nén Deflate chỉ được 7–9 %
  (`campplus_zh_en_advanced.onnx` 28,3 → 25,9 MB, `segmentation-3.0.onnx` 6,0 → 5,6 MB). Giữ nén (nhẹ hơn ~2,8 MB) và
  đọc thẳng qua `AssetManager` như Stage 5; không giải nén ra `filesDir`.
- `.so` đang để `Stored` (không nén, mặc định AGP): `libonnxruntime.so` 22,2 MB, `libsherpa-onnx-jni.so` 4,6 MB, cộng
  `libsherpa-onnx-c-api.so` 4,3 MB + `-cxx-api.so` 0,3 MB không dùng.
- Ước tính APK release: model ~31,4 + onnxruntime nén ~7,9 + jni nén ~2 + dex sau R8 vài MB → khoảng 45 MB.
- Lint lần cuối (Stage 8, 2026-11-18): `:app` 16 cảnh báo + 2 gợi ý (6 "có bản mới", 5 `UseKtx` toUri, 3
  `PluralsCandidate`, 2 `AutoboxingStateCreation`, `ExportedReceiver`, `DataExtractionRules`), `:feature:automation` 6
  (4 `UseKtx`, `PluralsCandidate`, `SdCardPath`), `:feature:queue` 1 (`PluralsCandidate`), `:core:media` 1
  (`UsableSpace`).

### A. Nâng bộ build (D134)

- [x] A1. Tra bảng tương thích Kotlin (kotlinlang.org/docs/gradle-configure-project.html), release notes AGP và
      gradle.org/releases: chọn bộ Kotlin / AGP / Gradle mới nhất đã được phủ; ghi nguồn và ngày vào Nhật ký. Kiểm: bảng
      phiên bản cũ → mới trong Nhật ký, kèm checksum Gradle khớp gradle.org/release-checksums.
- [x] A2. Nâng `gradle/libs.versions.toml` và `gradle-wrapper.properties` (cả thư viện lint báo: appcompat,
      kotlinx-serialization…, chỉ bản ổn định). Kiểm: `.\gradlew test lint` xanh cả hai flavor; lint hết cảnh báo
      `AndroidGradlePluginVersion` / `GradleDependency` / `NewerVersionAvailable`. Hỏng khó gỡ thì quay về bộ cũ của
      phần hỏng, ghi Nhật ký.
- [x] A3. Test thiết bị sau khi nâng, trên máy ảo `guzz_15t`: `:core:media`, `:core:diarization` và bộ của `:app`
      (cài tay hai APK như CLAUDE.md). Kiểm: tất cả qua. Commit riêng "Stage 9 A: nâng …".

### B. Rà lỗ hổng bảo mật (D132)

- [x] B1. Checklist Android, đọc kỹ từng mục trên code và `AndroidManifest.xml` đã gộp (`apkanalyzer manifest print` trên
      APK `sideloadRelease` chưa ký, build với `-x lint` nếu cần). Mỗi mục ghi kết quả (đạt / đã sửa / chấp nhận + lý do)
      vào Nhật ký:
  - [x] thành phần `exported`: liệt kê đủ; mỗi cái đúng ý định (`CommandReceiver`, `CommandActivity`, Chia sẻ tới Guzz,
        `MainActivity`), không có provider / service exported ngoài ý muốn;
  - [x] lệnh từ app khác: thiếu / sai mã bí mật thì từ chối; so mã không lộ qua log; extra lạ hoặc quá dài không làm
        chết app;
  - [x] đường dẫn do app khác truyền vào (TRANSCRIBE, `output_dir`, Chia sẻ): không ghi ra ngoài chỗ được phép (path
        traversal `..`, symlink), không đọc được tệp riêng của app (`/data/data/…`) rồi gửi lên Gemini;
  - [x] sự kiện phát ngược (FILE_DONE…): gửi tới ai, có mang nội dung bản chép lời / đường dẫn ra cho mọi app không;
  - [x] API key: chỉ lưu bằng Keystore (Stage 3), không vào log, không vào `config.txt` xuất ra (D112), không nằm trong
        URL log của OkHttp; `grep` log thật trên máy (`guzz.log*`) không thấy `AIza`;
  - [x] mạng: chỉ HTTPS; minSdk 26 cho phép cleartext mặc định ở API 26–27 → đặt `android:usesCleartextTraffic="false"`
        nếu chưa có;
  - [x] `PendingIntent` đều `FLAG_IMMUTABLE` (trừ chỗ cần mutable, ghi lý do); FileProvider (nếu có) không mở rộng hơn
        thư mục cần;
  - [x] `allowBackup="false"` + `dataExtractionRules` còn trên bản release; `debuggable` không bật;
  - [x] log ở `Android/data/<package>/files/logs/` không chứa nội dung bản chép lời hay key.
      Kiểm: checklist đủ dấu; mỗi chỗ sửa có test JVM hoặc test thiết bị nếu thử được (vd đường dẫn `..` bị từ chối).
- [x] B2. Quét bí mật trong lịch sử git bằng gitleaks (hỏi trước khi tải bản Windows từ GitHub Releases của gitleaks; giải
      nén vào thư mục riêng ngoài repo). Chạy trên toàn lịch sử mọi nhánh: `gitleaks git --log-opts="--all" -v`, cộng
      liệt kê blob lớn (`git rev-list --objects --all` + `git cat-file --batch-check`) để chắc không có bản ghi thật.
      Kiểm: 0 phát hiện thật (phát hiện giả ghi lý do vào Nhật ký, thêm `.gitleaksignore` nếu cần). Có bí mật thật: dừng,
      hỏi người dùng (thu hồi key + cách xử lý lịch sử) trước khi làm tiếp G.
- [x] B3. Quét lỗ hổng thư viện bằng osv-scanner (hỏi trước khi tải). Tạo tạm `gradle/verification-metadata.xml`
      (`.\gradlew --write-verification-metadata sha256 help`) làm danh sách phụ thuộc, `osv-scanner scan source -r .`,
      xong xoá tệp tạm. Phần không có trên Maven (AAR sherpa-onnx 2.0.1, ONNX Runtime trong AAR) tra tay advisory của
      `k2-fsa/sherpa-onnx` và `microsoft/onnxruntime` bằng `gh api`. Kiểm: không còn High / Critical chưa xử lý; nâng
      được thì nâng (chạy lại `test`), không nâng được thì ghi lý do chấp nhận vào Nhật ký.
- [x] B4. Gộp các chỗ sửa của B1–B3. Kiểm: `.\gradlew test lint` xanh; test thiết bị liên quan (`AutomationIntentTest`,
      `ContentSourceTest`) qua trên máy ảo. Commit "Stage 9 B: …".

### C. Tinh gọn (D133)

- [x] C1. Sửa cảnh báo lint còn lại: `toUri` (KTX), `plurals` cho các chuỗi `%d` + danh từ (cả bản tiếng Anh),
      `mutableIntStateOf`, `fullBackupContent` cho API < 31, `SdCardPath` trong PathMapping (dùng `Environment` hoặc tắt
      tại chỗ kèm lý do nếu `/sdcard` là chuỗi người dùng gõ), `UsableSpace`, `ExportedReceiver` (cố ý cho MacroDroid,
      đã có mã bí mật: `tools:ignore` kèm comment). Kiểm: `.\gradlew lint` 0 cảnh báo ở mọi module; `check_strings.ps1`
      sạch; test JVM của `:feature:automation` qua.
- [x] C2. Tài nguyên và code thừa: bật tạm `UnusedResources` / `UnusedIds` ở mọi module, xoá phần lint báo; class /
      hàm public không ai gọi (tìm bằng `grep` tên, kèm báo cáo `usage.txt` của R8 ở D3). Kiểm: lint không báo; `test`
      xanh.
- [x] C3. Phụ thuộc thừa: từng module, so `dependencies {}` với import thật (`grep`), bỏ dòng không dùng (vd
      `appcompat` ở module không đổi ngôn ngữ). Kiểm: `.\gradlew assembleSideloadDebug assemblePlayDebug test` xanh.
      Commit "Stage 9 C: …".

### D. Cấu hình release (D122, D128, D129, D130)

- [x] D1. Số phiên bản (D130): `guzzVersion = "1.0.0"` trong `libs.versions.toml`, convention plugin tính
      `versionCode`; cho phép ghi đè bằng `-Pguzz.version=1.0.1` (dùng ở F2). Kiểm: `apkanalyzer manifest version-code`
      ra `10000`, `version-name` ra `1.0.0`; với `-Pguzz.version=1.0.1` ra `10001`.
- [ ] D2. Ký (D129): `signingConfigs.release` đọc `GUZZ_KEYSTORE`, `GUZZ_KEYSTORE_PASSWORD`, `GUZZ_KEY_ALIAS`,
      `GUZZ_KEY_PASSWORD` từ biến môi trường; thiếu thì ký bằng khoá debug và in cảnh báo rõ "chỉ để thử" (để Claude build
      thử F mà không cần mật khẩu). Kiểm: build không biến → `apksigner verify --print-certs` ra chứng chỉ debug; script
      phát hành (D5) từ chối bản ký debug.
- [x] D3. R8 + tài nguyên: `isMinifyEnabled = true`, `isShrinkResources = true` cho `release`; keep rules cho
      `com.k2fsa.sherpa.onnx.**` (JNI tìm class / field / method theo tên) và lớp callback tiến độ của
      `:core:diarization` (JNI gọi `invoke`); kiểm rule đi kèm OkHttp / kotlinx.serialization đủ. Đọc
      `build/outputs/mapping/sideloadRelease/usage.txt` cho C2. Kiểm: `assembleSideloadRelease` ra APK; F1 chạy được tách
      giọng và gửi Gemini trên bản này.
- [x] D4. Gói (D128): `packaging.jniLibs.excludes` cho `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`;
      `useLegacyPackaging = true`; release chỉ `arm64-v8a`, thêm `-Pguzz.emulatorAbi=true` để build release có thêm
      `x86_64` chỉ cho máy ảo F3 (không phát hành bản này). Kiểm: `unzip -v` thấy `.so` dạng `Defl`, không còn hai `.so`
      thừa; `apkanalyzer apk file-size` / `download-size` ≤ 50 MB; ghi số đo từng phần (model, .so, dex, res) vào Nhật ký.
- [ ] D5. `tools/release.ps1 -Version 1.0.0`: kiểm cây git sạch và đang ở `main` trùng `origin/main`; chạy
      `.\gradlew test lint assembleSideloadRelease -Pguzz.version=…`; từ chối nếu thiếu biến ký hoặc APK ký bằng khoá
      debug; `apksigner verify`; chép ra `dist\guzz-<version>.apk` (thêm `dist/` vào `.gitignore`); in SHA-256 của APK và
      vân tay chứng chỉ. Không tự tạo tag hay Release. Kiểm: chạy không biến ký → dừng với thông báo rõ; chạy trong cây
      bẩn → dừng.
- [x] D6. Code chỉ cho debug không lọt release: liệt kê class / receiver / activity của bản debug (màn hình thử âm thanh,
      receiver adb của hàng đợi, Gemini giả, extra `fake_gemini`, `route` mở thẳng trang…); bản release không có hoặc
      không chạy được. Kiểm: `apkanalyzer manifest print` + `apkanalyzer dex packages` trên APK release không thấy các
      class / thành phần đó; `adb shell am broadcast` lệnh debug tới bản release không có tác dụng. Commit "Stage 9 D: …".
- [x] D7. Flavor `play`: `assemblePlayRelease` build được, manifest không có `MANAGE_EXTERNAL_STORAGE`. Kiểm:
      `apkanalyzer manifest permissions`.

### E. Nội dung phát hành (D125, D126, D127, D129)

- [x] E1. Icon (D127): vẽ lại `D:\Guzz\gui\assets\icon.png` thành vector — nền gradient xanh (`ic_launcher_background`),
      sóng âm + hai vạch chữ trắng trong vùng an toàn 66 dp (`ic_launcher_foreground`), lớp `monochrome`; bản debug giữ
      cùng hình, khác màu nền để phân biệt. Kiểm: ảnh chụp launcher trên máy ảo (tròn, vuông, icon theo chủ đề Android
      13+) và trên Xiaomi 15T; người dùng xem ảnh và đồng ý.
- [ ] E2. Keystore (D129, D137): hỏi trước khi tải `bcprov-jdk18on` (BouncyCastle, Maven Central) vào thư mục ngoài repo;
      soạn sẵn lệnh `keytool -importkeystore` từ `Downloads\Morphe.keystore` (`-srcstoretype BKS -providerpath <bcprov>
      -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider`) ra `D:\GuzzMobileData\keys\guzz-release.p12`
      (`-deststoretype PKCS12`) để **người dùng tự chạy và tự gõ mật khẩu** (không lấy mật khẩu từ chat). Người dùng đính
      kèm tệp `.p12` + mật khẩu vào trình quản lý mật khẩu. Kiểm: `keytool -list -v` trên bản `.p12` và trên bản tải lại
      từ trình quản lý mật khẩu ra cùng vân tay SHA-256; alias ghi vào Nhật ký cho `GUZZ_KEY_ALIAS`.
- [x] E3. Trang Giới thiệu: phiên bản, link GitHub và trang Releases (D126); "Giấy phép" mở danh sách, mỗi mục bấm vào
      xem toàn văn (tệp trong `assets/licenses/`): sherpa-onnx (Apache-2.0), ONNX Runtime (MIT, © Microsoft, tự thêm vì
      AAR không kèm), pyannote segmentation-3.0 (MIT, © 2022 CNRS, lấy `models/segmentation-3.0.LICENSE` trong cache
      guzz-deps), 3D-Speaker CAM++ (Apache-2.0), AndroidX / Compose, OkHttp, kotlinx (Apache-2.0); chính Guzz Mobile (MIT).
      Chuỗi vi / en. Kiểm: `ui_check.ps1` chụp trang ở vi / en, tối / sáng; `check_strings.ps1` sạch; lint sạch.
- [x] E4. Ghi chú quyền riêng tư (trang Giới thiệu + README): âm thanh được gửi lên Google AI Studio bằng key của người
      dùng, theo điều khoản của Google; tách giọng và làm sạch âm thanh chạy trên máy; app không có phân tích / theo dõi;
      log và bản chép lời chỉ nằm trên máy. Kiểm: chụp trang; nội dung khớp code (không có thư viện analytics trong
      `apkanalyzer dex packages`).
- [ ] E5. `LICENSE` (MIT, năm 2026, tên chủ repo như repo desktop), `README.md` (tiếng Việt: Guzz Mobile là gì, cài APK
      từ Releases, cấp quyền, nhập key, MacroDroid → `docs/intents.md`, quyền riêng tư, build từ mã nguồn), `CHANGELOG.md`
      (mục `1.0.0`: tính năng chính theo Stage 2–8). Kiểm: xem bản hiển thị trên GitHub sau G3; link nội bộ không gãy.
      Commit "Stage 9 E: …".

### F. Thử trên máy (D131, D135)

Bản thử là `sideloadRelease` ký bằng khoá debug (D2), package `io.github.cozycode890.guzz` — chưa từng cài trên Xiaomi
15T (bản debug là `.debug`). Script trong `tools/device/` đang trỏ package `.debug`: thêm tham số `-Package` cho script
cần dùng.

- [x] F1. Xiaomi 15T, cài mới bản 1.0.0: thẻ Thiết lập ban đầu (quyền thông báo, Truy cập mọi tệp, miễn tối ưu pin —
      người dùng tự bấm), người dùng tự nhập API key. Luồng chính: chọn tệp âm thanh, tệp video, Chia sẻ tới Guzz, một lệnh
      MacroDroid (bật công tắc + mã bí mật trên bản release), tách giọng bật. **Gửi thật: hỏi trước.** Kiểm: các tệp ra
      `.txt` đúng, MacroDroid nhận FILE_DONE; log không có lỗi R8 (`ClassNotFoundException`, `NoSuchMethodError`,
      `UnsatisfiedLinkError`).
- [x] F2. Cài đè: có cài đặt đã đổi, key, sổ hạn mức, hàng đợi có tệp, lịch sử → build `-Pguzz.version=1.0.1`,
      `adb install -r`. Kiểm: cài đặt, key, hạn mức, hàng đợi, lịch sử còn nguyên; log ghi `last_version_code`
      10000 → 10001; hàng đợi làm tiếp được.
- [x] F3. Máy ảo API 26 và API 30 (`x86_64`, build `-Pguzz.emulatorAbi=true`; hỏi trước khi tải system image, nêu dung
      lượng): cài mới, mở mọi tab, chọn tệp `B1_1m.m4a`, chạy với tách giọng bật (người dùng nhập key; gửi thật: hỏi
      trước), đổi ngôn ngữ. Kiểm: ra `.txt`; không crash (`adb logcat -b crash` trống).
- [x] F4. Đo bài 2 giờ trên bản release (D135, Phát sinh Stage 4): Xiaomi 15T rút cáp, `L1_120m.m4a`, tách giọng bật,
      màn hình tắt (gửi thật: hỏi trước). Ghi thời gian từng bước (giải mã, làm sạch, tách giọng, gửi) từ log, pin đầu /
      cuối, nhiệt cao nhất (`measure_diarize.ps1 -Package …`). Kiểm: số đo vào Nhật ký, so với mốc debug (xử lý âm thanh
      346 s / PSS 114 MB ở Stage 2; tách giọng 22,9 phút, −8 % pin, 41,1 °C ở Stage 1). Nóng / tốn pin bất thường
      (vd > 45 °C) thì hỏi người dùng, không tự thêm tính năng.

### G. Công khai và phát hành (D123, D124)

- [x] G1. Dọn tệp hiện tại trước khi public (D124): `git grep` IP Tailscale, tên tài khoản Windows, email, đường dẫn
      riêng; IP và lệnh `adb connect` chuyển sang `CLAUDE.local.md` (thêm vào `.gitignore`), CLAUDE.md / plans thay bằng
      `<ip-tailscale>`. Kiểm: `git grep` các mẫu đó ra rỗng; gitleaks (B2) chạy lại trên cây hiện tại sạch. Commit.
- [ ] G2. Người dùng tạo keystore thật (E2) rồi tự chạy `.\tools\release.ps1 -Version 1.0.0` trong terminal của mình
      (đặt biến mật khẩu ở đó). Gỡ bản ký debug trên Xiaomi 15T (soạn lệnh `adb uninstall` để người dùng chạy), cài
      `dist\guzz-1.0.0.apk`. Kiểm: `apksigner verify --print-certs` ra vân tay của keystore thật; mở app, chạy một tệp
      ngắn (gửi thật: hỏi trước).
- [ ] G3. **Hỏi người dùng** rồi chuyển repo public: `gh repo edit CozyCode890/GuzzMobile --visibility public
      --accept-visibility-change-consequences`. Kiểm: `gh repo view --json visibility` ra `PUBLIC`; README, LICENSE hiện
      đúng trên GitHub.
- [ ] G4. **Hỏi người dùng** rồi tạo tag `v1.0.0`, push tag, `gh release create v1.0.0 dist\guzz-1.0.0.apk --title
      "Guzz 1.0.0" --notes-file <mục 1.0.0 của CHANGELOG>` (ghi SHA-256 vào ghi chú). Kiểm: tải APK từ trang Releases
      bằng trình duyệt điện thoại, SHA-256 khớp, cài đè lên bản G2 được.
- [ ] G5. Đóng stage: đánh dấu Tiêu chí hoàn thành; Stage 9 ✅ kèm ngày trong CLAUDE.md và README §5; CLAUDE.md thêm khối
      lệnh "Phát hành (Stage 9)" (`release.ps1`, biến ký, `-Pguzz.version`, `-Pguzz.emulatorAbi`, gitleaks /
      osv-scanner); "Việc tiếp theo" ghi Stage 10 (tuỳ chọn) hoặc dừng. Kiểm: `git status` sạch sau commit, đã push.

## Nhật ký

- (2026-11-18, A1) Tra phiên bản (Google Maven, Maven Central, services.gradle.org, kotlinlang.org
  /docs/gradle-configure-project.html, GitHub releases):

  | Thành phần | Đang dùng | Mới nhất ổn định | Kotlin 2.4.20–2.4.21 phủ tới | Chọn |
  | --- | --- | --- | --- | --- |
  | Kotlin | 2.4.21 | 2.4.21 (2.5.0-RC ra 2026-11-11) | — | giữ |
  | AGP | 9.3.3 | 9.5.1 (nhánh 9.3: 9.3.3) | 9.3.1 | giữ (D136) |
  | Gradle | 9.7.1 | 9.9.1 (2026-11-10) | 9.7.0 | giữ (D136) |
  | appcompat | 1.7.1 | 1.8.0 (2026-08-12; minSdk mặc định 23) | — | **1.8.0** |
  | kotlinx-serialization | 1.12.0 | 1.13.0 (2026-11-11; bỏ thay đổi phá vỡ của 1.12.0, dựa Kotlin 2.4.10) | — | **1.13.0** |

  Các thư viện khác đã là bản ổn định mới nhất: Compose BOM 2026.09.00, activity-compose 1.13.0, datastore 1.3.0,
  navigation-compose 2.10.2, material-icons-core 1.7.8, coroutines 1.11.0, OkHttp 5.5.1, JUnit 6.1.3, junit 4.13.2,
  androidx.test runner 1.7.0, ext-junit 1.3.0. Gradle không đổi nên không cần kiểm checksum wrapper mới.
- (2026-11-18) Người dùng chọn ký bằng khoá Morphe có sẵn thay vì tạo khoá mới (D137); E2 đổi theo.
- (2026-11-18, A2) Nâng appcompat 1.8.0, kotlinx-serialization 1.13.0; tắt lint `AndroidGradlePluginVersion` ở `:app`
  (D136). `.\gradlew test lint lintPlayDebug` xanh; lint hết cảnh báo "có bản mới" (`:app` còn 10 cảnh báo + 2 gợi ý,
  `:feature:automation` 6, `:feature:queue` 1, `:core:media` 1: để C1).
- (2026-11-18, A3) Máy ảo `guzz_15t` (API 36): `:core:media` và `:core:diarization` `connectedDebugAndroidTest` qua
  (9 + 3); `:app` 11/11 qua. Lần chạy đầu `AutomationIntentTest.transcribeThenFileDoneWithSameRequestId` trượt ba lần liền
  vì hàng đợi của máy ảo còn sót bài `L1_20m.m4a` (20 phút) từ lần thử trước, chạy trước tệp 8 s của test nên quá 90 s;
  `queue_debug.ps1 -Stop`, `-Clear` rồi chạy lại cả bộ thì qua. Không liên quan bản nâng.
- (2026-11-18, B1) Checklist, đọc code + manifest (`app`, `feature/automation`, `feature/queue`, `core/diarization`,
  `core/gemini`):
  - exported ở main: `MainActivity` (launcher), `CommandReceiver`, `CommandActivity`, `ShareActivity`, đều cố ý. Provider
    (FileProvider, `files/output`, `files/logs` ngoài) và service (`QueueService`, `DiarizeService`, service ngôn ngữ)
    không exported. Chỉ bản debug: `AudioDebugActivity`, `AudioDebugStopReceiver` (exported, không quyền → C1 thêm quyền
    `DUMP`), `QueueDebugReceiver` (quyền `DUMP`). Kiểm lại trên APK release ở B4.
  - Mã bí mật so bằng `MessageDigest.isEqual`, không vào log. **Tìm ra:** mã mặc định trống → D138 (tự tạo khi bật).
  - **Tìm ra:** Chia sẻ tới Guzz nhận cả `file://` (data / ClipData): `resolveUri` mở bằng quyền của Guzz rồi chép vào
    `inbox` → chỉ nhận `content://`. Lệnh TRANSCRIBE: đường dẫn có `..` bị từ chối (`ExtrasReader`, test JVM);
    đường dẫn trong thư mục dữ liệu trong của Guzz (sau `canonicalPath`) bị từ chối (`no_permission`, test thiết bị
    `privateDataPathIsRejected`); `appOwnedPath` chỉ còn thư mục riêng trên bộ nhớ ngoài. `output_dir`: cũng chặn `..` và
    thư mục dữ liệu trong.
  - **Tìm ra:** extra `route` của `MainActivity` (exported) có tác dụng cả ở release; route lạ làm Navigation ném lỗi →
    chỉ bản debug đọc.
  - Sự kiện phát bằng broadcast ngầm: app nào đăng ký cũng nghe được tên tệp, `request_id`, và đường dẫn khi bật "Kèm
    đường dẫn" (D97). **Chấp nhận**: MacroDroid / Tasker không giữ được quyền riêng; tài liệu đã nói rõ, có công tắc tắt
    đường dẫn. Lệnh bị từ chối vẫn phát ERROR (không chứa gì riêng).
  - API key: chỉ ở header `x-goog-api-key`, thông điệp lỗi che key (`KeyLeakTest`); không có logging interceptor của
    OkHttp; log không ghi nội dung bản chép lời (chỉ độ dài, thời gian nói). Xuất `config.txt` không có key (D112).
  - Mạng: thêm `android:usesCleartextTraffic="false"` (Android 8–8.1 mặc định vẫn cho HTTP).
  - `PendingIntent` (3 chỗ) đều `FLAG_IMMUTABLE`. `allowBackup="false"` + `dataExtractionRules` có ở manifest main.
  - Bản release bỏ extra `fake_gemini` (`Automation.handle`) và cờ `gemini_gia` (`BuildConfig.DEBUG` trong
    `AppContainer`).
- (2026-11-18, B2) gitleaks 8.30.1 (tải từ GitHub Releases, SHA-256 khớp, để ở `D:\GuzzMobileData\tools`), 81 commit mọi
  nhánh: 1 phát hiện `generic-api-key` ở `testdata/golden/gemini/requests.json`, là key giả `TestKey-…` của golden hàm
  che key → `.gitleaksignore`; quét lại: 0. Quét thư mục làm việc: thêm 8 phát hiện trong `spikes/android/app/build/`
  (không commit), bỏ qua. Blob lớn nhất trong lịch sử là golden JSON 1,7 MB; tệp âm thanh / video trong lịch sử đều là tệp
  tổng hợp của `testdata/` (sinh bằng `tools/golden`, seed cố định), không có bản ghi thật.
- (2026-11-18, B3) osv-scanner 2.7.1 (SHA-256 khớp). Danh sách phụ thuộc: `.\gradlew --write-verification-metadata
  sha256` kèm `test lint assembleSideloadRelease` và `dependencies` của mọi module (655 thành phần), chép ra
  `D:\GuzzMobileData\stage-09\` rồi xoá khỏi repo; osv-scanner chỉ nhận tệp này khi nằm trong thư mục `gradle/`. Kết quả:
  14 gói có lỗ hổng (netty 4.1.93 / 4.1.110, BouncyCastle 1.79, guava 32–33, jdom2 2.0.6, httpclient 4.5.6, jose4j 0.9.5,
  commons-lang3 3.16.0, kotlin-gradle-plugin 2.4.0 kéo theo), **không gói nào nằm trong `sideloadReleaseRuntimeClasspath`
  của `:app`** (141 gói) → đều là công cụ build của AGP / lint / Kotlin, chạy trên PC với mã của chính repo. **Chấp nhận**:
  chỉ hết khi nâng AGP (D136). Phần không có trên Maven: ONNX Runtime trong AAR là 1.28.2 (chuỗi phiên bản trong
  `libonnxruntime.so`), sherpa-onnx 2.0.1: GitHub Advisory Database và OSV (PyPI) không có lỗ hổng nào.
- (2026-11-18, B4) Manifest APK `sideloadRelease` (chưa ký, `apkanalyzer manifest print`): exported chỉ `MainActivity`,
  `CommandReceiver`, `CommandActivity`, `ShareActivity` (+ `ProfileInstallReceiver` của AndroidX, quyền `DUMP`); không có
  thành phần debug; `allowBackup="false"`, có `dataExtractionRules`, không `debuggable`, `usesCleartextTraffic="false"`.
  Sau các chỗ sửa: `.\gradlew test lint lintPlayDebug` xanh (`:feature:automation` thêm `SecretOnEnableTest`, ca `..` trong
  `ExtrasReaderTest`); máy ảo `guzz_15t`: test thiết bị của `:app` 12/12 (thêm `privateDataPathIsRejected`).
- (2026-11-18, C1) Lint 0 cảnh báo ở mọi module: `Uri.parse` → `toUri()` (9 chỗ); `google_ai_check_ok`, `share_added`
  thành `<plurals>`; `system_import_summary`, `queue_done_text` tiếng Anh viết lại cho khỏi cần số nhiều; `mutableIntStateOf`;
  thêm `backup_rules.xml` (`fullBackupContent`, Android 8–11, loại trừ mọi thứ như `data_extraction_rules.xml`);
  `AudioDebugStopReceiver` (chỉ bản debug) thêm quyền `DUMP`; tắt tại chỗ kèm lý do: `SdCardPath` ở `PathMapping` (phải
  nhận `/sdcard/…` người dùng gõ), `UsableSpace` ở `StorageCheck`.
- (2026-11-18, C2) Tạm bật `checkDependencies` + `UnusedResources` / `UnusedIds` cho `:app`: không còn tài nguyên thừa.
  `usage.txt` của R8 quá nhiễu (R8 inline rồi bỏ cả lớp continuation, hằng số) nên tìm code thừa bằng script: khai báo mà tên
  không có ở tệp nào khác (kể cả test, XML). Bỏ: `InMemoryApiKeyStore`, `CleanSettings.anyStep`, `Loudness.boostAndNormalize`
  (golden "boost_and_normalize" vẫn chạy qua `gainFor` + `GainSource`), `ErrorKind.fromCode`, `DiarizationJson.toTurns`,
  `PieceJson.readList`. Giữ: `Cx.minus` (toán tử, gọi bằng `-`), `PipelineConfig.CONFLICT_OVERWRITE` (liệt kê đủ giá trị của
  `on_conflict`).
- (2026-11-18, C3) `:core:diarization` bỏ plugin + thư viện kotlinx-serialization (không dùng). `:feature:automation` bỏ
  `activity-compose` (không import gì) — nó là đường đến gián tiếp của `androidx.core` (`toUri`), nên khai báo thẳng
  `androidx.core:core` 1.19.1 (từ 1.19 `core-ktx` đã gộp vào `core`; lint báo 1.19.1 là bản ổn định mới nhất, ra
  2026-09-23). `:app` → `:core:media` giữ: code debug và androidTest dùng. Kiểm (máy chạy bó RAM: 2 worker, `-Xmx2g`,
  Kotlin in-process): `test lint lintPlayDebug assembleSideloadDebug assembleSideloadRelease assemblePlayRelease` xanh,
  `check_strings.ps1` sạch, máy ảo `guzz_15t`: test thiết bị của `:app` 9/9 + 3/3 Compose UI. Lần chạy đầu
  `NavigationTest` treo như ghi ở Stage 8 (chạy trên APK cũ), lần sau qua: test giao diện chạy riêng một lượt.
- (2026-11-18, D) `guzz = "1.0.0"` trong `libs.versions.toml`, versionCode tính ở `app/build.gradle.kts` (`apkanalyzer`:
  `1.0.0` / `10000`); `-Pguzz.version` thử ở F2. Ký: thiếu `GUZZ_KEYSTORE` thì Gradle in cảnh báo "CHỈ ĐỂ THỬ" và ký bằng
  khoá debug (`apksigner`: `CN=Android Debug`). R8 + shrink resources bật cho release; keep rules ở
  `core/diarization/consumer-rules.pro` (AAR sherpa-onnx có `proguard.txt` rỗng): `mapping.txt` giữ nguyên tên 104 class
  `com.k2fsa.sherpa.onnx.*` và `ProgressCallback.invoke(int,int,long)`; chạy thật tách giọng trên bản release ở F1.
  `-Pguzz.emulatorAbi=true` thêm `x86_64` (PowerShell: phải để tham số trong ngoặc kép, không thì tách ở dấu chấm).
- (2026-11-18, D4) APK `sideloadRelease` arm64: **43,9 MB** (41,9 MiB; tải về 43,4 MB) ≤ 50 MB (D128). Trong đó model
  `campplus_zh_en_advanced.onnx` 25,8 MB nén / `segmentation-3.0.onnx` 5,5 MB, `libonnxruntime.so` 8,0 MB nén (22,2 MB),
  `libsherpa-onnx-jni.so` 1,9 MB nén, `classes.dex` 2,0 MB nén (3,9 MB), `resources.arsc` 0,5 MB; không còn
  `libsherpa-onnx-c-api.so` / `-cxx-api.so`. `playRelease` 43,9 MB; bản thêm x86_64 cho máy ảo 55,0 MB.
- (2026-11-18, D5) `tools/release.ps1`: thử 4 nhánh từ chối, đều thoát mã 1 với thông báo rõ: số phiên bản sai dạng, cây git
  bẩn, thiếu `GUZZ_KEYSTORE`, không thấy tệp keystore. Nhánh "APK ký bằng khoá debug" và lần chạy đủ thử ở G2.
- (2026-11-18, D6, D7) `mapping.txt` của release không có class `…guzz.debug.*`, `FakeTranscriber`, `AudioDebug*`,
  `QueueDebugReceiver`, `UnknownLengthProvider`; manifest release không có thành phần debug (B4), nên lệnh adb của bản debug
  không tới được bản release. `playRelease` build được, không có `MANAGE_EXTERNAL_STORAGE` /
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `READ_EXTERNAL_STORAGE`.
- (2026-11-18, E1) Icon vector vẽ lại từ `icon.png` desktop (đo màu và vị trí nét bằng PIL, tỉ lệ 0,27 dp/px, căn giữa
  phần nét): 6 cột sóng âm, mũi tên xuống, vạch chữ dài + vạch ngắn mờ 70 %; nền gradient `#0497B1` → `#206BE2`; lớp
  monochrome dùng chung lớp trước. Bản debug: cùng hình, nền `#E8710A` → `#B3261E`. Bỏ màu `ic_launcher_background` cũ.
  Ảnh trên máy ảo (mặt nạ tròn, màn hình chờ bản debug): `D:\GuzzMobileData\stage-09\e1\`. Người dùng duyệt icon
  (2026-11-18); ảnh trên Xiaomi 15T chụp kèm F1.
- (2026-11-18, E3, E4) Trang Thông tin: nút "Mã nguồn", "Bản mới (GitHub)" (mở trình duyệt); phần "Quyền riêng tư"; giấy
  phép thành danh sách 8 mục, bấm vào mở hộp toàn văn từ `assets/licenses/` (Apache-2.0 dùng chung một tệp; dòng cắt cứng
  được nối lại theo đoạn khi hiển thị). Toàn văn: Apache-2.0 từ LICENSE của `k2-fsa/sherpa-onnx` (trùng từng chữ với
  OkHttp); MIT của segmentation-3.0 từ cache guzz-deps; ONNX Runtime: repo `microsoft/onnxruntime` trên GitHub trả 404 ngày
  2026-11-18 và AAR / Maven không kèm LICENSE, nên dựng thân MIT chuẩn với câu "Copyright (c) Microsoft Corporation" (pom
  Maven và PyPI ghi "MIT License"; Stage 1 đã ghi MIT © Microsoft). Ảnh máy ảo (tiếng Anh) ở `…\stage-09\e1\`. APK
  release không có thư viện analytics / crash report (`mapping.txt`: không có firebase, gms.measurement, crashlytics,
  sentry…).
- (2026-11-18, E2) Đã tải `bcprov-jdk18on-1.87.jar` (Maven Central, SHA-1 khớp, OSV không có lỗ hổng) vào
  `D:\GuzzMobileData\tools\bcprov`; lệnh chuyển BKS → PKCS12 đã soạn, chờ người dùng tự chạy.
- (2026-11-18, E2) Người dùng tự chạy `keytool -importkeystore`: lần đầu lỗi `KeyStore integrity check failed` vì
  `Morphe.keystore` không có mật khẩu cấp tệp ("Morphe" là mật khẩu của khoá). Lần hai bỏ trống mật khẩu nguồn, gõ
  mật khẩu khoá khi được hỏi: nhập được 1 khoá → `D:\GuzzMobileData\keys\guzz-release.p12` (mật khẩu mới người dùng tự
  đặt; PKCS12 dùng chung mật khẩu đó cho khoá, nên không cần `GUZZ_KEY_PASSWORD`). Khoá: alias `Morphe`, `CN=Morphe`,
  RSA 4096, SHA256withRSA, hạn tới 2218, SHA-256 chứng chỉ
  `3A:28:19:DB:0F:0E:62:F1:C4:55:21:ED:0D:94:4D:3C:40:C4:9C:42:E3:E2:D0:43:B7:D9:F1:7E:F0:5D:F7:72`. Còn: người dùng cất
  `.p12` + mật khẩu vào trình quản lý mật khẩu và thử khôi phục (vân tay phải trùng số trên).
- (2026-11-18, E5) `LICENSE` MIT ghi "Copyright (c) 2026 CozyCode890" (tên tài khoản GitHub; repo desktop không có LICENSE
  để theo); `README.md`, `CHANGELOG.md` (mục 1.0.0). Kiểm hiển thị trên GitHub ở G3.
- (2026-11-19, F1) Xiaomi 15T qua USB (Tailscale không vào được, Gỡ lỗi không dây chưa bật). Bản thử
  `sideloadRelease` ký khoá debug, package `io.github.cozycode890.guzz` chưa từng cài trên máy: `adb install` bị HyperOS huỷ
  (`INSTALL_FAILED_USER_RESTRICTED`), người dùng cài bằng tay từ `Download/GuzzThu`. **Crash** khi mở "Nâng cao" của trang
  Google AI: `Thiếu resource prompt.txt trong :core:gemini` — `DefaultPrompt` nạp resource theo tên tương đối, R8 dời class
  sang package khác. Người dùng cho tách session [fix-prompt-r8]: `f79b795` đọc bằng đường dẫn tuyệt đối, thêm
  `app/proguard-rules.pro` giữ tên các class lỗi của Guzz (`-keepnames … extends Throwable`, để log / sự kiện ở bản release
  không ghi tên kiểu `ke1`). Cài đè bản đã sửa, gửi thật (người dùng đồng ý 4 đoạn ngắn cắt từ bản ghi ở
  `D:\GuzzMobileData\stage-09\f\`): `B1_1m.m4a` qua trình chọn tệp, `V1_3m.mp4` (video 3 phút), `L1_2m_chiase.m4a` qua Chia
  sẻ tới Guzz, `L1_1m_lenh.m4a` qua lệnh TRANSCRIBE bằng `adb shell am broadcast` (thay MacroDroid; người dùng tự chạy vì
  có mã bí mật). Đều ra `.txt` (`gemini-3.1-flash-lite`). Lần đầu chưa bật tách giọng và chưa cấp "Truy cập mọi tệp" cho bản
  release (quyền cấp riêng từng package): lệnh bị từ chối đúng cách (`FILE_FAILED`, `request_id`, lý do rõ). Lần hai:
  tách giọng trên bản R8 chạy được (process `:diarize`, sherpa-onnx: `B1_1m` 8 s / 9 lượt, `L1_1m_lenh` 12 s / 6 lượt),
  lệnh ra `FILE_STARTED` → `FILE_DONE` cùng `request_id f1-lenh` → `QUEUE_FINISHED`; trùng tên tự đánh số
  (`B1_1m (2).txt`). Không còn crash. Stack trace bản release bị R8 đổi tên → `release.ps1` chép thêm `mapping.txt` vào
  `dist\`. Icon trên màn hình chính HyperOS (mặt nạ vuông bo) đúng: `…\stage-09\f\e1_xiaomi_icon.png`.
- (2026-11-19, F2) Có sẵn: tệp chờ `L1_2m_chiase.m4a` (chưa bấm Bắt đầu), key, cách người nói "trên máy", tự động hoá bật +
  mã bí mật, quyền "Truy cập mọi tệp", sổ hạn mức. `adb install -r` bản `-Pguzz.version=1.0.1`: log ghi "Đã cập nhật từ
  versionCode 10000 lên 10001"; tệp chờ, thư mục lưu, model, preset, người nói, key (`AQ.A…gmkw`), công tắc + mã bí mật,
  quyền tệp, hạn mức (`gemini-3.1-flash-lite`: 5 yêu cầu, 8.754 token) còn nguyên (máy tự bấm qua giao diện bằng
  uiautomator theo lời người dùng). Đạt tiêu chí "cài đè giữ nguyên dữ liệu và cài đặt" với khoá debug; G2 cài bản ký khoá
  thật sẽ phải gỡ bản thử (khác chữ ký).
- (2026-11-19, F4) Xiaomi 15T, bản release 1.0.1 (khoá debug), `L1_120m.m4a` (2 giờ) nạp bằng Chia sẻ tới Guzz qua adb
  (`am start … ShareActivity --eu android.intent.extra.STREAM content://media/external/file/<id>`; tệp được chép vào
  `inbox`, D103), cách người nói "trên máy", preset "Moving speaker", `gemini-3.1-flash-lite`; gửi thật 9 đoạn (người dùng
  đồng ý). adb qua Wi-Fi (`adb tcpip 5555`, `<ip-lan>:5555`) + `dumpsys battery unplug` (người dùng bảo tự chạy), xong
  `dumpsys battery reset` + `adb usb`. Script mới `tools/device/measure_release.ps1` (không cần receiver debug) +
  bộ ghi nhiệt riêng. Kết quả: **41,3 phút** tổng; xử lý âm thanh + cắt đoạn **339 s** (đủ chuỗi làm sạch Stage 7; mốc bản
  debug Stage 2 chỉ cắt lặng + chuẩn hoá: 346 s), tách giọng **1568 s = 26,1 phút** (1135 lượt, 2 người; mốc Stage 1:
  22,9 phút), 9 đoạn Gemini 22–77 s mỗi đoạn; PSS cao nhất app 169 MB, `:diarize` **507 MB** (mốc 556 MB); nhiệt
  (`dumpsys thermalservice`) pin / bề mặt 37,6 → **39,6 °C**, CPU tối đa 70 °C (mốc 41,1 °C). Ra
  `L1_120m (2).txt` (81 KB). **Pin không đo được**: cáp USB vẫn cắm (log: 76 → 72 → 73 %), và `dumpsys battery` bị đóng
  băng khi đang `unplug` (76 % / 33,4 °C suốt lượt) → script đổi sang đọc nhiệt ở `thermalservice`, ghi chú phải rút cáp
  thật. Không nóng / tốn RAM bất thường nên không hỏi thêm (D135). Màn hình bật suốt lượt (người dùng dùng máy).
- (2026-11-19, F3) D139: tải `system-images;android-26;default;x86_64` (người dùng đồng ý; 3,2 GB trên đĩa), tạo máy ảo
  `guzz_api26` (RAM 2 GB), tắt `guzz_15t` trước. Bản release `-Pguzz.emulatorAbi=true` từ HEAD (có bản sửa `f79b795`):
  cài được trên Android 8.0.0; bấm qua 3 tab, 9 trang Cài đặt (nhãn "Output" thật là "Transcripts"), phần "Nâng cao" của
  Transcripts và của Google AI (chỗ crash ở F1): app sống, `logcat -b crash` trống. Ảnh: `…\stage-09\f3_api26_b\`. Không
  gửi Gemini trên máy ảo, không thử API 30 (D139). Tắt máy ảo sau khi thử.
- (2026-11-19, G1) IP Tailscale, IP LAN và serial USB của điện thoại ở 6 tệp (CLAUDE.md, stage-00, stage-01, stage-09,
  `spikes/README.md`, `measure_diarize.ps1`) thay bằng `<ip-tailscale>`, `<ip-lan>`, `<serial>`; giá trị thật chuyển sang
  `CLAUDE.local.md` (thêm vào `.gitignore`, Claude Code vẫn tự đọc). Lịch sử git giữ nguyên (D124). `git grep` các mẫu đó
  rỗng; gitleaks trên cây hiện tại chỉ còn 8 phát hiện trong `spikes/android/app/build/` (không commit) và key giả của
  golden; lịch sử: không có.

## Phát sinh

- (Từ Stage 0, 2026-10-31) Stage 0 dùng AGP 9.3.3 + Gradle 9.7.1 + Kotlin 2.4.21 vì Kotlin 2.4.21 mới được kiểm tới AGP
  9.3.1 / Gradle 9.7.0 (D24), dù đã có AGP 9.5.0 và Gradle 9.8.2. Trước khi phát hành, nâng lên bộ mới nhất mà bảng
  tương thích của Kotlin (kotlinlang.org/docs/gradle-configure-project.html) đã phủ. → A1–A3 (D134).
- (Từ Stage 0, 2026-10-31) `sdkmanager` (cmdline-tools 22.0) báo deprecated, khuyên dùng Android CLI (`android sdk …`,
  cùng thư mục cmdline-tools). Lệnh cài SDK trong CLAUDE.md vẫn dùng `sdkmanager`; đổi khi `sdkmanager` thôi chạy. → Không làm ở Stage 9.
- (Từ Stage 0, 2026-11-01) Mỗi lần mở app, `GuzzApp` ghi versionCode vào `APP.last_version_code` (D27).
  `Settings.recordAppVersion` trả về versionCode của lần mở trước, với 0 nghĩa là lần mở đầu tiên. Chỗ này dùng được để
  chuyển dữ liệu cũ khi cài đè bản mới, ứng với tiêu chí "Cài đè bản mới lên bản cũ: giữ nguyên dữ liệu và cài đặt". → Kiểm ở F2.
- (Từ Stage 1, 2026-11-10) Dung lượng thư viện tách giọng (arm64-v8a, [stage-01-results.md](stage-01-results.md) §2):
  `libonnxruntime.so` 22,2 MB (tải về 7,9 MB), `libsherpa-onnx-jni.so` 4,6 MB. AAR có thêm `libsherpa-onnx-c-api.so` và
  `libsherpa-onnx-cxx-api.so` mà JNI không dùng → bỏ bằng `packaging.jniLibs.excludes`. → D4.
- (Từ Stage 1, 2026-11-10) Giấy phép: AAR sherpa-onnx không kèm LICENSE / NOTICE của ONNX Runtime (MIT) → tự thêm vào màn
  hình giấy phép, cùng câu bản quyền MIT của segmentation-3.0. Model đã chọn không có CC-BY, không cần ghi công riêng. → E3.
- (Từ Stage 1, 2026-11-10) `android:allowBackup="false"`: sao lưu đầy đủ làm hệ thống giết process đang chạy FGS (xem
  Phát sinh của Stage 4). → Đã làm ở Stage 3 (A3, D55), kèm `dataExtractionRules` chặn chuyển máy; Stage 9 chỉ cần
  kiểm lại trên bản release. → B1.
- (Từ plan Stage 4, 2026-11-11) Đo lại tốc độ xử lý âm thanh trên bản release (không debuggable), chạy qua service hàng
  đợi thật (Phát sinh Stage 2 chuyển sang Stage 4, người dùng không muốn bước đo riêng ở Stage 4 nên dời tới đây). Mốc so:
  bài 2 giờ trên bản debug mất 346 s, PSS 114 MB (Stage 2, D45 / D50). → F4.
- (Từ Stage 5, 2026-11-15) Màn hình giấy phép / NOTICE cho phần tách giọng đóng gói trong APK (bảng đủ ở
  [stage-01-results.md](stage-01-results.md) §6): pyannote segmentation-3.0 (MIT, © 2022 CNRS: giữ câu bản quyền; tệp
  `LICENSE` có sẵn trong cache của plugin guzz.deps, `models/segmentation-3.0.LICENSE`), 3D-Speaker CAM++ zh_en advanced
  (Apache-2.0), sherpa-onnx 2.0.1 (Apache-2.0), ONNX Runtime trong AAR (MIT, © Microsoft; AAR không kèm LICENSE nên tự
  thêm). Model và AAR không nằm trong repo: plugin `guzz.deps` tải theo `gradle/guzz-deps.txt` (D79), nên CI phát hành cần
  mạng hoặc cache `guzz-deps`. Tách ABI (APK debug đang giữ đủ 4 ABI cho máy ảo `x86_64`). → E3 (giấy phép), D4 (ABI); không có CI
  (D122) nên build tay dùng cache `guzz-deps` sẵn có.
- (Từ Stage 6, 2026-11-18, E2) Còn 6 cảnh báo lint (KTX `toUri`, plurals cho `share_added`, `/sdcard` trong
  PathMapping), Stage 8 chưa xét. Lúc plan Stage 9, lint cả repo còn 24 cảnh báo (xem "Ghi chú kỹ thuật"). → C1 (A2 bỏ
  các cảnh báo "có bản mới").
- (Plan Stage 9, 2026-11-18) Người dùng yêu cầu rà lỗ hổng và tinh gọn phần dư thừa trước khi phát hành. → Nhóm B, C
  (D132, D133).
