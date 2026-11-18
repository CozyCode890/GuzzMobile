# Guzz Mobile

Chuyển âm thanh / video thành văn bản trên điện thoại Android: **chọn tệp → bấm Bắt đầu → ra bản chép lời `.txt`**.
Bản Android của [Guzz](https://github.com/CozyCode890/Guzz) (app desktop Windows).

- Chép lời bằng Google AI Studio (Gemini), dùng API key của chính bạn; hết hạn mức thì tự đổi sang model dự phòng.
- Làm sạch âm thanh trên máy: cắt khoảng lặng, chuẩn hoá âm lượng, lọc dải, khử ồn, ghìm tiếng động đột ngột; bài dài
  được chia đoạn khoảng 10 phút ở chỗ lặng nhất.
- Nhận diện người nói ngay trên máy (sherpa-onnx), không cần tài khoản hay token nào khác.
- Hàng đợi chạy nền khi tắt màn hình; dừng rồi làm tiếp không gửi lại đoạn đã xong.
- Điều khiển được từ MacroDroid / Tasker bằng intent, và nhận tệp qua "Chia sẻ tới Guzz".
- Giao diện tiếng Việt / tiếng Anh, sáng / tối; nhập được `config.txt` của Guzz desktop.

## Cài đặt

1. Tải `guzz-<phiên bản>.apk` ở trang [Releases](https://github.com/CozyCode890/GuzzMobile/releases). Ghi chú của mỗi
   bản có mã SHA-256 để đối chiếu.
2. Mở tệp APK trên điện thoại, cho phép "Cài ứng dụng không rõ nguồn" cho trình duyệt / trình quản lý tệp khi được hỏi.
3. Cần Android 8.0 trở lên, chip 64-bit ARM (`arm64-v8a`, gần như mọi điện thoại từ 2017).

Cập nhật: tải bản mới và cài đè lên; cài đặt, API key và hàng đợi được giữ nguyên. App không tự kiểm tra bản mới: xem
trang Releases (có nút mở nhanh ở Cài đặt → Thông tin).

## Bắt đầu

1. Lấy API key miễn phí ở [Google AI Studio](https://aistudio.google.com/apikey), dán vào Cài đặt → Google AI.
2. Làm theo thẻ "Thiết lập ban đầu" trên màn hình chính: chọn thư mục lưu bản chép lời, cho phép thông báo, miễn tối ưu
   pin (để hàng đợi chạy được khi tắt màn hình).
3. Chọn tệp âm thanh hoặc video, bấm **Bắt đầu**.

Điều khiển từ MacroDroid / Tasker (lệnh, sự kiện, ví dụ macro): [docs/intents.md](docs/intents.md).

## Quyền riêng tư

- Âm thanh của tệp bạn chọn được gửi lên Google AI Studio bằng API key của bạn, theo điều khoản của Google. Guzz không
  gửi dữ liệu đi đâu khác.
- Làm sạch âm thanh và nhận diện người nói chạy ngay trên điện thoại.
- Không quảng cáo, không thu thập số liệu sử dụng. Bản chép lời, cài đặt và nhật ký chỉ nằm trên máy; API key được mã
  hoá bằng Android Keystore và không được sao lưu.

## Build từ mã nguồn

Cần JDK 21 và Android SDK (platform 37, build-tools 37.0.0). Lần build đầu, Gradle tự tải AAR sherpa-onnx và model nhận
diện người nói từ GitHub Releases của sherpa-onnx và kiểm SHA-256 (`gradle/guzz-deps.txt`).

```powershell
.\gradlew assembleSideloadDebug   # APK debug: app\build\outputs\apk\sideload\debug\
.\gradlew test lint
```

Flavor `sideload` (APK tự cài, có quyền "Truy cập mọi tệp" để nhận đường dẫn từ MacroDroid) và `play` (không có quyền
đó). Quy trình phát hành: `tools/release.ps1`.

## Giấy phép

Mã nguồn Guzz Mobile theo giấy phép [MIT](LICENSE). Phần mềm và model đóng gói kèm: sherpa-onnx (Apache-2.0),
ONNX Runtime (MIT), pyannote segmentation-3.0 (MIT), 3D-Speaker CAM++ (Apache-2.0), AndroidX / Jetpack Compose, OkHttp,
Kotlin và kotlinx (Apache-2.0). Toàn văn ở Cài đặt → Thông tin → Giấy phép.
