# Stage 6 — Intent cho MacroDroid

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 4 (phép thử chạy nền của Stage 1 chuyển vào đây, D42)

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

- Hạn chế chạy nền của Android (README rủi ro 3): Stage 1 chỉ thử lúc màn hình bật, phần thử lúc màn hình tắt làm ở
  stage này (D42). Mỗi bản Android và mỗi hãng một khác → ghi rõ trong tài liệu những máy đã thử.
- Broadcast ngầm (implicit) gửi ra ngoài: chỉ receiver đăng ký lúc chạy mới nhận được (MacroDroid đăng ký kiểu này) →
  kiểm tra thật.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 4, 2026-11-12, D69) Android 12+ không cho receiver ở nền khởi động foreground service khi app chưa được
  miễn tối ưu pin; trên Xiaomi 15T, "No restrictions" của HyperOS không đưa app vào `deviceidle whitelist`
  (`ForegroundServiceStartNotAllowedException`). Hướng dẫn cài đặt cho MacroDroid phải có bước "Tối ưu pin → Không tối
  ưu" (hoặc nút xin `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ở bản sideload), và receiver nên báo lỗi rõ khi bị chặn.

- (Từ Stage 1, 2026-11-10) Phép thử MacroDroid của Stage 1 chuyển sang đây, rút gọn (D42). Thử trên receiver thật của
  Guzz, hoặc trên app spike (`spikes/android`) nếu muốn biết trước khi viết code:
  - tạo macro theo `spikes/README.md`, mục "Macro MacroDroid" (đổi tên package / class sang của Guzz);
  - **chỉ một đêm**, máy đã cài đặt (pin "No restrictions", Autostart, miễn tối ưu pin), rút cáp, app bị vuốt khỏi đa
    nhiệm: mỗi cách (broadcast, activity trong suốt, WorkManager) ≥ 10 lần với màn hình tắt và Doze. Đạt D35 khi ít nhất
    một cách được 10/10. Bỏ đêm "chưa cài gì";
  - sự kiện gửi ngược về MacroDroid: macro bắt được DONE (ở đây là `FILE_DONE`) cho ≥ 3 lần chạy lúc màn hình tắt,
    `request_id` khớp.
- (Từ Stage 1, 2026-11-10) Điều đã biết:
  - bằng adb lúc màn hình bật, cả ba cách đều lên FGS (app spike, G1);
  - cài qua adb xong thì app đã được miễn tối ưu pin dù chưa bấm xin → muốn thử trạng thái chưa miễn thì gỡ bằng
    `adb shell dumpsys deviceidle whitelist -<package>`;
  - MacroDroid cũng phải được đặt pin "No restrictions", Autostart và quyền "Display over other apps", thiếu thì lỗi
    nằm ở phía gửi;
  - adb qua Tailscale: app VPN chạy trên máy có thể ảnh hưởng Doze và pin; đêm thử thì `adb disconnect`.
- (Từ Stage 2, 2026-11-11) `MediaInput.Content` từng hỏng với URI của trình chọn tệp vì provider trả độ dài −1 (đã sửa,
  xem Nhật ký Stage 2, D4). Test thiết bị của `:core:media` chỉ thử đường dẫn: khi nhận content:// từ app khác (Chia sẻ
  tới Guzz, intent có URI), thêm test thiết bị đọc qua một ContentProvider thật (vd MediaStore Downloads hoặc FileProvider
  trả độ dài không rõ).
- (Từ plan Stage 4, 2026-11-11) Stage 4 lưu bản chép lời vào thư mục chọn qua SAF (D59). **Lưu cạnh tệp nguồn** (như
  `output_dir` rỗng của desktop) làm ở đây: chỉ khi có quyền "Truy cập mọi tệp" và nguồn là đường dẫn tuyệt đối; nguồn
  content:// thì lùi về thư mục SAF. Extra `output_dir` của lệnh TRANSCRIBE cũng theo quy tắc này.
- (Từ plan Stage 4, 2026-11-11) Cửa vào hàng đợi là `QueueController` của `:feature:queue` (thêm, Bắt đầu, Dừng, trạng
  thái). Bản debug của Stage 4 có receiver adb + `tools/device/queue_debug.ps1` để thử hàng đợi lúc tắt màn hình: receiver
  thật của stage này thay thế hoặc dùng lại phần đọc extra của nó.
