# Stage 4 — Pipeline + hàng đợi nền + UI tối thiểu (MVP)

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 2, 3

## Mục tiêu

**Bản đầu tiên dùng được hằng ngày:** chọn bài giảng 1–2 giờ → ra .txt; chạy được khi tắt màn hình; dừng rồi làm tiếp thì
không gửi lại các đoạn đã chép xong. Bản này chưa có nhận diện người nói.

## Phạm vi

### Làm

- `:core:pipeline`, port từ `transcribe.py`:
  - `transcribe_single_file` (chưa có bước người nói), `transcribe_chunks`;
  - mã băm cache (`audio_hash`, kết quả từng đoạn) để làm tiếp; thư mục tạm riêng cho mỗi tệp, nằm trong `filesDir` vì
    `cacheDir` có thể bị hệ thống xoá;
  - `assemble_transcript`, `format_header`, mẫu tên tệp (`{name}` `{recorded}` `{date}` `{time}` `{model}`), xử lý khi
    trùng tên, kiểu xuống dòng, encoding;
  - lỗi theo loại: dừng cả hàng đợi (key sai, ràng buộc model…) hay làm tiếp tệp sau.
- `:feature:queue`:
  - Room lưu hàng đợi: tệp, trạng thái (chờ / đang chạy / xong / lỗi / dừng), tiến độ, đường dẫn kết quả;
  - foreground service (Android 15+: loại `mediaProcessing`; bản cũ hơn: `dataSync`, kiểm lại theo targetSdk), thông báo
    tiến độ có nút Dừng;
  - tiến trình bị giết thì lần mở sau làm tiếp từ đoạn đang dở.
- Lưu kết quả: thư mục mặc định, hoặc cùng thư mục với tệp nguồn nếu có quyền, hoặc thư mục đã cấp qua SAF.
- UI:
  - màn hình Hàng đợi: thêm tệp bằng trình chọn tệp, tiến độ từng tệp, mở bản chép lời, Chạy lại, Bỏ khỏi danh sách;
  - cài đặt nhanh: model, cắt khoảng lặng;
  - xem bản chép lời.

### Không làm

- Nhận diện người nói (Stage 5). Intent, Chia sẻ tới Guzz (Stage 6). Làm sạch đầy đủ (Stage 7).

## Nguồn tham khảo trong Guzz desktop

- `transcribe.py` (toàn bộ, trừ phần người nói)
- `gui/pages/transcribe.py`, `gui/threads.py`: hành vi hàng đợi, Dừng / Bắt đầu lại
- `tests/test_transcribe.py`
- README desktop §4, §9

## Kết quả bàn giao

- APK dùng thật được từ đầu đến cuối.

## Tiêu chí hoàn thành

- [ ] Test pipeline trên âm thanh tổng hợp với Gemini giả (như `test_transcribe.py`).
- [ ] Chạy thật một bài 1 giờ trên máy thật, tắt màn hình suốt lúc chạy.
- [ ] Bấm Dừng giữa chừng rồi Bắt đầu lại: các đoạn đã chép không bị gửi lại (xem trong log).
- [ ] Giết app (`adb shell am kill`) giữa chừng: mở lại thì làm tiếp được.

## Câu hỏi cần chốt khi plan chi tiết

- Lưu bản chép lời mặc định ở đâu: `Documents/Guzz`, cạnh tệp nguồn, hay thư mục người dùng chọn?
- Chạy lần lượt từng tệp (như desktop) hay song song nhiều tệp?
- Xem bản chép lời ngay trong app, hay mở bằng app khác?
- Có nút chia sẻ bản chép lời không?

## Rủi ro

- Giới hạn thời gian chạy của foreground service và cách mỗi hãng giết app nền → dựa vào cache từng đoạn để làm tiếp,
  hướng dẫn tắt tối ưu pin.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 1, 2026-11-10) Hai phép thử chạy nền của Stage 1 chuyển sang đây, làm trên foreground service thật (D42):
  - **Chạy dài** (G4 cũ): màn hình tắt suốt lúc chạy, nhật ký ghi nhịp liền ≥ 60 phút, không hở quá 2 phút, service không
    bị giết; ghi lại lúc chạy có mạng hay không (gọi Gemini trong Doze). Gộp vào tiêu chí "Chạy thật một bài 1 giờ".
  - **`onTimeout`** (G5 cũ): người dùng tự chạy
    `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 120000`, chạy một bài; nhật ký có
    `onTimeout` vào khoảng phút thứ 2, service dừng trong ≤ 1 s, `adb logcat -d -b crash` trống. Hoàn tác:
    `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.
- (Từ Stage 1, 2026-11-10) Điều đã biết từ app spike:
  - FGS không giữ CPU thức lúc màn hình tắt → service giữ partial wake lock (`WAKE_LOCK`) trong lúc chạy;
  - `BackupManagerService` giết process app để sao lưu đầy đủ khi máy sạc và rảnh, dù đang có FGS → đặt
    `android:allowBackup="false"` (khớp Phát sinh của Stage 3 về sao lưu API key);
  - Android 16: job (kể cả WorkManager) chạy song song với FGS bị tính vào quota → không chạy việc dài bằng WorkManager
    cùng lúc với service.
  - Tham khảo `spikes/android/.../bench/BenchService.kt` và `automation/HeartbeatService.kt`.
- (Từ plan Stage 2, 2026-11-10) Việc Stage 2 để lại cho stage này:
  - `[VIDEO] save_extracted` (D47): chép tiếng của video ra cạnh bản chép lời. Mobile có sẵn `goc.pcm` trong thư mục
    tạm, chỉ cần mã hoá ra FLAC khi lưu kết quả;
  - `[VIDEO] skip_silent`: Stage 2 ném `NoAudioError`, stage này quyết bỏ qua tệp hay báo lỗi;
  - mã băm cache (`audio_hash`) cho thư mục tạm của Stage 2 (`goc.pcm`, `sach.pcm`, `doan_*.flac`,
    `danh_sach_doan.json`, chưa có khoá `ma`); bài 2 giờ chiếm khoảng 660 MB tạm, cần chính sách dọn;
  - màn hình thử và foreground service chỉ có ở bản debug của Stage 2 (D49): thay bằng màn hình Hàng đợi và service
    thật.
