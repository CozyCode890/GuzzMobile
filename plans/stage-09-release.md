# Stage 9 — Đóng gói & phát hành

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 8

## Mục tiêu

Có APK release đã ký, phát hành qua GitHub Releases. Đã đo và tối ưu dung lượng, pin, nhiệt; chạy được trên vài hãng
điện thoại.

## Phạm vi

### Làm

- R8 / minify (kèm keep rules cho JNI của sherpa-onnx); chỉ ABI `arm64-v8a`; đo dung lượng APK.
- Model trong assets: kiểm tra có bị nén hai lần không, giải nén ra `filesDir` một lần hay đọc thẳng từ assets.
- Ký release: tạo keystore, cất giữ an toàn (mất keystore là không cập nhật APK được nữa); quy tắc đánh số phiên bản.
- CHANGELOG, GitHub Releases.
- Thử trên 2–3 máy / hãng (ít nhất một máy Xiaomi hoặc Samsung, vì hai hãng này giết app nền mạnh).
- Pin và nhiệt: giới hạn số luồng CPU, cảnh báo khi pin yếu.
- Màn hình giấy phép mã nguồn mở: sherpa-onnx, ONNX Runtime, model, các thư viện AndroidX.
- Ghi chú quyền riêng tư: âm thanh được gửi lên Google AI Studio bằng key của người dùng; tách giọng chạy trên máy.
- Kiểm tra flavor `play` vẫn build được (chưa phát hành).

### Không làm

- Phát hành lên Google Play.

## Nguồn tham khảo trong Guzz desktop

- README desktop §12 (chuẩn bị bản cài đặt)

## Kết quả bàn giao

- Bản phát hành đầu tiên trên GitHub Releases.

## Tiêu chí hoàn thành

- [ ] Cài APK release lên máy chưa từng cài Guzz: chạy được hết luồng chính.
- [ ] Cài đè bản mới lên bản cũ: giữ nguyên dữ liệu và cài đặt.
- [ ] Dung lượng APK nằm trong mức đã chốt.

## Câu hỏi cần chốt khi plan chi tiết

- Build release bằng GitHub Actions hay build tay trên máy?
- App có tự kiểm tra bản mới (qua GitHub API) không?
- Tên hiển thị và icon chính thức?
- Mức dung lượng APK chấp nhận được?

## Rủi ro

- Mất keystore → sao lưu ngoài máy ngay khi tạo.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 0, 2026-10-31) Stage 0 dùng AGP 9.3.3 + Gradle 9.7.1 + Kotlin 2.4.21 vì Kotlin 2.4.21 mới được kiểm tới AGP
  9.3.1 / Gradle 9.7.0 (D24), dù đã có AGP 9.5.0 và Gradle 9.8.2. Trước khi phát hành, nâng lên bộ mới nhất mà bảng
  tương thích của Kotlin (kotlinlang.org/docs/gradle-configure-project.html) đã phủ.
- (Từ Stage 0, 2026-10-31) `sdkmanager` (cmdline-tools 22.0) báo deprecated, khuyên dùng Android CLI (`android sdk …`,
  cùng thư mục cmdline-tools). Lệnh cài SDK trong CLAUDE.md vẫn dùng `sdkmanager`; đổi khi `sdkmanager` thôi chạy.
- (Từ Stage 0, 2026-11-01) Mỗi lần mở app, `GuzzApp` ghi versionCode vào `APP.last_version_code` (D27).
  `Settings.recordAppVersion` trả về versionCode của lần mở trước, với 0 nghĩa là lần mở đầu tiên. Chỗ này dùng được để
  chuyển dữ liệu cũ khi cài đè bản mới, ứng với tiêu chí "Cài đè bản mới lên bản cũ: giữ nguyên dữ liệu và cài đặt".
- (Từ Stage 1, 2026-11-10) Dung lượng thư viện tách giọng (arm64-v8a, [stage-01-results.md](stage-01-results.md) §2):
  `libonnxruntime.so` 22,2 MB (tải về 7,9 MB), `libsherpa-onnx-jni.so` 4,6 MB. AAR có thêm `libsherpa-onnx-c-api.so` và
  `libsherpa-onnx-cxx-api.so` mà JNI không dùng → bỏ bằng `packaging.jniLibs.excludes`.
- (Từ Stage 1, 2026-11-10) Giấy phép: AAR sherpa-onnx không kèm LICENSE / NOTICE của ONNX Runtime (MIT) → tự thêm vào màn
  hình giấy phép, cùng câu bản quyền MIT của segmentation-3.0. Model đã chọn không có CC-BY, không cần ghi công riêng.
- (Từ Stage 1, 2026-11-10) `android:allowBackup="false"`: sao lưu đầy đủ làm hệ thống giết process đang chạy FGS (xem
  Phát sinh của Stage 4). → Đã làm ở Stage 3 (A3, D55), kèm `dataExtractionRules` chặn chuyển máy; Stage 9 chỉ cần
  kiểm lại trên bản release.
- (Từ plan Stage 4, 2026-11-11) Đo lại tốc độ xử lý âm thanh trên bản release (không debuggable), chạy qua service hàng
  đợi thật (Phát sinh Stage 2 chuyển sang Stage 4, người dùng không muốn bước đo riêng ở Stage 4 nên dời tới đây). Mốc so:
  bài 2 giờ trên bản debug mất 346 s, PSS 114 MB (Stage 2, D45 / D50).
