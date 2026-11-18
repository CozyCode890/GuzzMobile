# Stage 9 — Đóng gói & phát hành

**Trạng thái:** 📝 đã plan chi tiết (2026-11-18) · **Cần xong trước:** 8

## Mục tiêu

Có APK release đã ký, phát hành qua GitHub Releases. Trước khi phát hành, rà lỗ hổng bảo mật và tinh gọn phần dư thừa.
Đã đo dung lượng, tốc độ, pin, nhiệt; chạy được trên máy thật Xiaomi và máy ảo Android cũ.

## Phạm vi

### Làm

- Nâng AGP / Gradle / thư viện lên bộ mới nhất mà Kotlin đã phủ (D134).
- Rà lỗ hổng: checklist Android + đọc kỹ mặt tấn công, quét bí mật trong lịch sử git, quét lỗ hổng thư viện (D132).
- Tinh gọn: code chết, tài nguyên và phụ thuộc thừa, hết cảnh báo lint; code chỉ cho debug không lọt vào release (D133).
- R8 / minify (kèm keep rules cho JNI của sherpa-onnx); chỉ ABI `arm64-v8a`; bỏ `.so` thừa; nén `.so`; APK ≤ 50 MB (D128).
- Model trong assets: đã kiểm lúc plan, không nén hai lần (xem "Ghi chú kỹ thuật"); giữ đọc thẳng từ assets.
- Ký release bằng keystore ngoài repo, build tay trên PC (D122, D129); số phiên bản 1.0.0 (D130).
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

- Mất keystore → sao lưu vào trình quản lý mật khẩu ngay khi tạo (D129), kiểm bằng cách khôi phục thử (E2).
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

- [ ] A1. Tra bảng tương thích Kotlin (kotlinlang.org/docs/gradle-configure-project.html), release notes AGP và
      gradle.org/releases: chọn bộ Kotlin / AGP / Gradle mới nhất đã được phủ; ghi nguồn và ngày vào Nhật ký. Kiểm: bảng
      phiên bản cũ → mới trong Nhật ký, kèm checksum Gradle khớp gradle.org/release-checksums.
- [ ] A2. Nâng `gradle/libs.versions.toml` và `gradle-wrapper.properties` (cả thư viện lint báo: appcompat,
      kotlinx-serialization…, chỉ bản ổn định). Kiểm: `.\gradlew test lint` xanh cả hai flavor; lint hết cảnh báo
      `AndroidGradlePluginVersion` / `GradleDependency` / `NewerVersionAvailable`. Hỏng khó gỡ thì quay về bộ cũ của
      phần hỏng, ghi Nhật ký.
- [ ] A3. Test thiết bị sau khi nâng, trên máy ảo `guzz_15t`: `:core:media`, `:core:diarization` và bộ của `:app`
      (cài tay hai APK như CLAUDE.md). Kiểm: tất cả qua. Commit riêng "Stage 9 A: nâng …".

### B. Rà lỗ hổng bảo mật (D132)

- [ ] B1. Checklist Android, đọc kỹ từng mục trên code và `AndroidManifest.xml` đã gộp (`apkanalyzer manifest print` trên
      APK `sideloadRelease` chưa ký, build với `-x lint` nếu cần). Mỗi mục ghi kết quả (đạt / đã sửa / chấp nhận + lý do)
      vào Nhật ký:
  - [ ] thành phần `exported`: liệt kê đủ; mỗi cái đúng ý định (`CommandReceiver`, `CommandActivity`, Chia sẻ tới Guzz,
        `MainActivity`), không có provider / service exported ngoài ý muốn;
  - [ ] lệnh từ app khác: thiếu / sai mã bí mật thì từ chối; so mã không lộ qua log; extra lạ hoặc quá dài không làm
        chết app;
  - [ ] đường dẫn do app khác truyền vào (TRANSCRIBE, `output_dir`, Chia sẻ): không ghi ra ngoài chỗ được phép (path
        traversal `..`, symlink), không đọc được tệp riêng của app (`/data/data/…`) rồi gửi lên Gemini;
  - [ ] sự kiện phát ngược (FILE_DONE…): gửi tới ai, có mang nội dung bản chép lời / đường dẫn ra cho mọi app không;
  - [ ] API key: chỉ lưu bằng Keystore (Stage 3), không vào log, không vào `config.txt` xuất ra (D112), không nằm trong
        URL log của OkHttp; `grep` log thật trên máy (`guzz.log*`) không thấy `AIza`;
  - [ ] mạng: chỉ HTTPS; minSdk 26 cho phép cleartext mặc định ở API 26–27 → đặt `android:usesCleartextTraffic="false"`
        nếu chưa có;
  - [ ] `PendingIntent` đều `FLAG_IMMUTABLE` (trừ chỗ cần mutable, ghi lý do); FileProvider (nếu có) không mở rộng hơn
        thư mục cần;
  - [ ] `allowBackup="false"` + `dataExtractionRules` còn trên bản release; `debuggable` không bật;
  - [ ] log ở `Android/data/<package>/files/logs/` không chứa nội dung bản chép lời hay key.
      Kiểm: checklist đủ dấu; mỗi chỗ sửa có test JVM hoặc test thiết bị nếu thử được (vd đường dẫn `..` bị từ chối).
- [ ] B2. Quét bí mật trong lịch sử git bằng gitleaks (hỏi trước khi tải bản Windows từ GitHub Releases của gitleaks; giải
      nén vào thư mục riêng ngoài repo). Chạy trên toàn lịch sử mọi nhánh: `gitleaks git --log-opts="--all" -v`, cộng
      liệt kê blob lớn (`git rev-list --objects --all` + `git cat-file --batch-check`) để chắc không có bản ghi thật.
      Kiểm: 0 phát hiện thật (phát hiện giả ghi lý do vào Nhật ký, thêm `.gitleaksignore` nếu cần). Có bí mật thật: dừng,
      hỏi người dùng (thu hồi key + cách xử lý lịch sử) trước khi làm tiếp G.
- [ ] B3. Quét lỗ hổng thư viện bằng osv-scanner (hỏi trước khi tải). Tạo tạm `gradle/verification-metadata.xml`
      (`.\gradlew --write-verification-metadata sha256 help`) làm danh sách phụ thuộc, `osv-scanner scan source -r .`,
      xong xoá tệp tạm. Phần không có trên Maven (AAR sherpa-onnx 2.0.1, ONNX Runtime trong AAR) tra tay advisory của
      `k2-fsa/sherpa-onnx` và `microsoft/onnxruntime` bằng `gh api`. Kiểm: không còn High / Critical chưa xử lý; nâng
      được thì nâng (chạy lại `test`), không nâng được thì ghi lý do chấp nhận vào Nhật ký.
- [ ] B4. Gộp các chỗ sửa của B1–B3. Kiểm: `.\gradlew test lint` xanh; test thiết bị liên quan (`AutomationIntentTest`,
      `ContentSourceTest`) qua trên máy ảo. Commit "Stage 9 B: …".

### C. Tinh gọn (D133)

- [ ] C1. Sửa cảnh báo lint còn lại: `toUri` (KTX), `plurals` cho các chuỗi `%d` + danh từ (cả bản tiếng Anh),
      `mutableIntStateOf`, `fullBackupContent` cho API < 31, `SdCardPath` trong PathMapping (dùng `Environment` hoặc tắt
      tại chỗ kèm lý do nếu `/sdcard` là chuỗi người dùng gõ), `UsableSpace`, `ExportedReceiver` (cố ý cho MacroDroid,
      đã có mã bí mật: `tools:ignore` kèm comment). Kiểm: `.\gradlew lint` 0 cảnh báo ở mọi module; `check_strings.ps1`
      sạch; test JVM của `:feature:automation` qua.
- [ ] C2. Tài nguyên và code thừa: bật tạm `UnusedResources` / `UnusedIds` ở mọi module, xoá phần lint báo; class /
      hàm public không ai gọi (tìm bằng `grep` tên, kèm báo cáo `usage.txt` của R8 ở D3). Kiểm: lint không báo; `test`
      xanh.
- [ ] C3. Phụ thuộc thừa: từng module, so `dependencies {}` với import thật (`grep`), bỏ dòng không dùng (vd
      `appcompat` ở module không đổi ngôn ngữ). Kiểm: `.\gradlew assembleSideloadDebug assemblePlayDebug test` xanh.
      Commit "Stage 9 C: …".

### D. Cấu hình release (D122, D128, D129, D130)

- [ ] D1. Số phiên bản (D130): `guzzVersion = "1.0.0"` trong `libs.versions.toml`, convention plugin tính
      `versionCode`; cho phép ghi đè bằng `-Pguzz.version=1.0.1` (dùng ở F2). Kiểm: `apkanalyzer manifest version-code`
      ra `10000`, `version-name` ra `1.0.0`; với `-Pguzz.version=1.0.1` ra `10001`.
- [ ] D2. Ký (D129): `signingConfigs.release` đọc `GUZZ_KEYSTORE`, `GUZZ_KEYSTORE_PASSWORD`, `GUZZ_KEY_ALIAS`,
      `GUZZ_KEY_PASSWORD` từ biến môi trường; thiếu thì ký bằng khoá debug và in cảnh báo rõ "chỉ để thử" (để Claude build
      thử F mà không cần mật khẩu). Kiểm: build không biến → `apksigner verify --print-certs` ra chứng chỉ debug; script
      phát hành (D5) từ chối bản ký debug.
- [ ] D3. R8 + tài nguyên: `isMinifyEnabled = true`, `isShrinkResources = true` cho `release`; keep rules cho
      `com.k2fsa.sherpa.onnx.**` (JNI tìm class / field / method theo tên) và lớp callback tiến độ của
      `:core:diarization` (JNI gọi `invoke`); kiểm rule đi kèm OkHttp / kotlinx.serialization đủ. Đọc
      `build/outputs/mapping/sideloadRelease/usage.txt` cho C2. Kiểm: `assembleSideloadRelease` ra APK; F1 chạy được tách
      giọng và gửi Gemini trên bản này.
- [ ] D4. Gói (D128): `packaging.jniLibs.excludes` cho `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`;
      `useLegacyPackaging = true`; release chỉ `arm64-v8a`, thêm `-Pguzz.emulatorAbi=true` để build release có thêm
      `x86_64` chỉ cho máy ảo F3 (không phát hành bản này). Kiểm: `unzip -v` thấy `.so` dạng `Defl`, không còn hai `.so`
      thừa; `apkanalyzer apk file-size` / `download-size` ≤ 50 MB; ghi số đo từng phần (model, .so, dex, res) vào Nhật ký.
- [ ] D5. `tools/release.ps1 -Version 1.0.0`: kiểm cây git sạch và đang ở `main` trùng `origin/main`; chạy
      `.\gradlew test lint assembleSideloadRelease -Pguzz.version=…`; từ chối nếu thiếu biến ký hoặc APK ký bằng khoá
      debug; `apksigner verify`; chép ra `dist\guzz-<version>.apk` (thêm `dist/` vào `.gitignore`); in SHA-256 của APK và
      vân tay chứng chỉ. Không tự tạo tag hay Release. Kiểm: chạy không biến ký → dừng với thông báo rõ; chạy trong cây
      bẩn → dừng.
- [ ] D6. Code chỉ cho debug không lọt release: liệt kê class / receiver / activity của bản debug (màn hình thử âm thanh,
      receiver adb của hàng đợi, Gemini giả, extra `fake_gemini`, `route` mở thẳng trang…); bản release không có hoặc
      không chạy được. Kiểm: `apkanalyzer manifest print` + `apkanalyzer dex packages` trên APK release không thấy các
      class / thành phần đó; `adb shell am broadcast` lệnh debug tới bản release không có tác dụng. Commit "Stage 9 D: …".
- [ ] D7. Flavor `play`: `assemblePlayRelease` build được, manifest không có `MANAGE_EXTERNAL_STORAGE`. Kiểm:
      `apkanalyzer manifest permissions`.

### E. Nội dung phát hành (D125, D126, D127, D129)

- [ ] E1. Icon (D127): vẽ lại `D:\Guzz\gui\assets\icon.png` thành vector — nền gradient xanh (`ic_launcher_background`),
      sóng âm + hai vạch chữ trắng trong vùng an toàn 66 dp (`ic_launcher_foreground`), lớp `monochrome`; bản debug giữ
      cùng hình, khác màu nền để phân biệt. Kiểm: ảnh chụp launcher trên máy ảo (tròn, vuông, icon theo chủ đề Android
      13+) và trên Xiaomi 15T; người dùng xem ảnh và đồng ý.
- [ ] E2. Keystore (D129): soạn sẵn lệnh `keytool -genkeypair` (RSA 4096, hạn 10000 ngày, alias `guzz`) ghi ra
      `D:\GuzzMobileData\keys\guzz-release.jks` để **người dùng tự chạy và tự gõ mật khẩu**; người dùng đính kèm tệp + mật
      khẩu vào trình quản lý mật khẩu. Kiểm: người dùng tải lại tệp từ trình quản lý mật khẩu ra thư mục khác,
      `keytool -list -keystore <bản tải lại>` ra cùng vân tay SHA-256 với bản gốc.
- [ ] E3. Trang Giới thiệu: phiên bản, link GitHub và trang Releases (D126); "Giấy phép" mở danh sách, mỗi mục bấm vào
      xem toàn văn (tệp trong `assets/licenses/`): sherpa-onnx (Apache-2.0), ONNX Runtime (MIT, © Microsoft, tự thêm vì
      AAR không kèm), pyannote segmentation-3.0 (MIT, © 2022 CNRS, lấy `models/segmentation-3.0.LICENSE` trong cache
      guzz-deps), 3D-Speaker CAM++ (Apache-2.0), AndroidX / Compose, OkHttp, kotlinx (Apache-2.0); chính Guzz Mobile (MIT).
      Chuỗi vi / en. Kiểm: `ui_check.ps1` chụp trang ở vi / en, tối / sáng; `check_strings.ps1` sạch; lint sạch.
- [ ] E4. Ghi chú quyền riêng tư (trang Giới thiệu + README): âm thanh được gửi lên Google AI Studio bằng key của người
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

- [ ] F1. Xiaomi 15T, cài mới bản 1.0.0: thẻ Thiết lập ban đầu (quyền thông báo, Truy cập mọi tệp, miễn tối ưu pin —
      người dùng tự bấm), người dùng tự nhập API key. Luồng chính: chọn tệp âm thanh, tệp video, Chia sẻ tới Guzz, một lệnh
      MacroDroid (bật công tắc + mã bí mật trên bản release), tách giọng bật. **Gửi thật: hỏi trước.** Kiểm: các tệp ra
      `.txt` đúng, MacroDroid nhận FILE_DONE; log không có lỗi R8 (`ClassNotFoundException`, `NoSuchMethodError`,
      `UnsatisfiedLinkError`).
- [ ] F2. Cài đè: có cài đặt đã đổi, key, sổ hạn mức, hàng đợi có tệp, lịch sử → build `-Pguzz.version=1.0.1`,
      `adb install -r`. Kiểm: cài đặt, key, hạn mức, hàng đợi, lịch sử còn nguyên; log ghi `last_version_code`
      10000 → 10001; hàng đợi làm tiếp được.
- [ ] F3. Máy ảo API 26 và API 30 (`x86_64`, build `-Pguzz.emulatorAbi=true`; hỏi trước khi tải system image, nêu dung
      lượng): cài mới, mở mọi tab, chọn tệp `B1_1m.m4a`, chạy với tách giọng bật (người dùng nhập key; gửi thật: hỏi
      trước), đổi ngôn ngữ. Kiểm: ra `.txt`; không crash (`adb logcat -b crash` trống).
- [ ] F4. Đo bài 2 giờ trên bản release (D135, Phát sinh Stage 4): Xiaomi 15T rút cáp, `L1_120m.m4a`, tách giọng bật,
      màn hình tắt (gửi thật: hỏi trước). Ghi thời gian từng bước (giải mã, làm sạch, tách giọng, gửi) từ log, pin đầu /
      cuối, nhiệt cao nhất (`measure_diarize.ps1 -Package …`). Kiểm: số đo vào Nhật ký, so với mốc debug (xử lý âm thanh
      346 s / PSS 114 MB ở Stage 2; tách giọng 22,9 phút, −8 % pin, 41,1 °C ở Stage 1). Nóng / tốn pin bất thường
      (vd > 45 °C) thì hỏi người dùng, không tự thêm tính năng.

### G. Công khai và phát hành (D123, D124)

- [ ] G1. Dọn tệp hiện tại trước khi public (D124): `git grep` IP Tailscale, tên tài khoản Windows, email, đường dẫn
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
