# Stage 6 — Intent cho MacroDroid

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 4 (và kết quả mục 5 của Stage 1)

## Mục tiêu

App tự động hoá (MacroDroid, Tasker, Automate) điều khiển được Guzz bằng intent và nhận lại kết quả. Ví dụ: ghi âm xong →
tự chép lời → thông báo hoặc mở bản chép lời.

## Phạm vi

### Làm

- **API intent** (bản nháp, chốt tên khi plan chi tiết; `<id>` là `applicationId`):

  | Chiều | Action | Extra chính |
  | --- | --- | --- |
  | gửi tới Guzz | `<id>.action.TRANSCRIBE` | `path` / `paths` / `uri`, `request_id`; tuỳ chọn ghi đè: `model`, `speakers` (`off` / `device` / `gemini` / `hybrid`), `denoise`, `output_dir` |
  | gửi tới Guzz | `<id>.action.START`, `<id>.action.STOP` | — |
  | gửi tới Guzz | `<id>.action.STATUS` | `request_id` |
  | Guzz phát ra | `<id>.event.FILE_STARTED` | `request_id`, `source` |
  | Guzz phát ra | `<id>.event.FILE_DONE` | `request_id`, `source`, `output_path`, `duration_sec`, `chars`, `models` |
  | Guzz phát ra | `<id>.event.FILE_FAILED` | `request_id`, `source`, `error`, `error_kind` |
  | Guzz phát ra | `<id>.event.QUEUE_FINISHED` | `done`, `failed`, `stopped` |
  | Guzz phát ra | `<id>.event.STATUS` | trạng thái hàng đợi |

- **Cách nhận lệnh:** theo kết quả Stage 1 — BroadcastReceiver exported và/hoặc activity trong suốt. Nhận lệnh thì thêm
  vào hàng đợi và khởi động foreground service.
- **Hai chế độ đọc tệp** (D6):
  - có quyền "Truy cập mọi tệp" (`MANAGE_EXTERNAL_STORAGE`, chỉ flavor `sideload`) → nhận đường dẫn tuyệt đối;
  - không có → nhận content:// URI có kèm cờ cấp quyền, hoặc tệp nằm trong thư mục đã cấp qua SAF (persisted URI
    permission). Đường dẫn không đọc được thì phát `FILE_FAILED` kèm lý do rõ ràng.
- **Bảo mật:** công tắc "Cho phép app khác điều khiển Guzz" (mặc định tắt); mã bí mật tuỳ chọn gửi kèm trong extra; tuỳ
  chọn có đưa đường dẫn tệp vào sự kiện hay không; ghi log mọi lệnh nhận được.
- **Chia sẻ tới Guzz:** `ACTION_SEND` / `ACTION_SEND_MULTIPLE` cho `audio/*` và `video/*` (từ app ghi âm, trình quản lý tệp).
- **Tài liệu:** `docs/intents.md` (bảng action / extra / sự kiện) + 2–3 macro MacroDroid mẫu: ghi âm xong → chép lời →
  thông báo; theo dõi thư mục Recordings; chép xong thì mở tệp.
- **Màn hình trong app:** liệt kê action, nút sao chép, nút "Gửi thử sự kiện".

### Không làm

- Plugin Tasker/Locale (Stage 10).

## Nguồn tham khảo trong Guzz desktop

- `gui/main.py`: desktop đã có cách nhận tệp từ lần mở app thứ hai (thêm vào cửa sổ đang mở) — ý tưởng tương tự.
- README desktop §4: hàng đợi, các trạng thái tệp.

## Kết quả bàn giao

- MacroDroid gửi lệnh được và nhận được sự kiện, cả lúc màn hình tắt.
- `docs/intents.md` + macro mẫu xuất từ MacroDroid.

## Tiêu chí hoàn thành

- [ ] Unit test đọc extra (thiếu extra, sai kiểu, mã bí mật sai).
- [ ] Instrumented test: gửi intent → tệp vào hàng đợi → nhận sự kiện `FILE_DONE`.
- [ ] Thử thật với MacroDroid ở cả hai chế độ quyền tệp, lúc màn hình tắt.

## Câu hỏi cần chốt khi plan chi tiết

- Tên action cuối cùng (phụ thuộc `applicationId` chốt ở Stage 0)?
- Có phát sự kiện tiến độ (`FILE_PROGRESS`, thưa, vd mỗi đoạn) không?
- Mã bí mật: bắt buộc hay tuỳ chọn?
- Extra được phép ghi đè những cài đặt nào?
- Nhận lệnh qua broadcast, activity trong suốt, hay cả hai?

## Rủi ro

- Hạn chế chạy nền của Android (README rủi ro 3) — đã thử ở Stage 1, nhưng mỗi bản Android và mỗi hãng một khác → ghi rõ
  trong tài liệu những máy đã thử.
- Broadcast ngầm (implicit) gửi ra ngoài: chỉ receiver đăng ký lúc chạy mới nhận được (MacroDroid đăng ký kiểu này) →
  kiểm tra thật.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh
