# Stage 10 — (Tuỳ chọn) Plugin Tasker/Locale

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 6

## Mục tiêu

Guzz hiện trong danh sách plugin của MacroDroid và Tasker: có màn hình cấu hình (chọn tệp, model, cách nhận diện) thay vì
phải tự gõ action và extra, và trả kết quả ra biến (vd `%guzz_output`).

## Phạm vi

### Làm

- Hành động (action plugin) theo chuẩn Locale: activity `EDIT_SETTING` để cấu hình, receiver `FIRE_SETTING` để thực thi —
  dùng lại phần hàng đợi của Stage 6.
- Trả biến cho Tasker (TaskerPlugin): đường dẫn bản chép lời, thời lượng, lỗi.
- Sự kiện / điều kiện (event / condition plugin): "Guzz chép xong một tệp", nếu MacroDroid hỗ trợ.
- Thử thật với MacroDroid và Tasker.

### Không làm

- Bỏ API intent của Stage 6: plugin chỉ là lớp vỏ bọc bên ngoài.

## Nguồn tham khảo

- Stage 6, `docs/intents.md`.

## Kết quả bàn giao

- Plugin dùng được trong MacroDroid (và Tasker nếu hợp).

## Tiêu chí hoàn thành

- [ ] Tạo macro chỉ bằng giao diện plugin, không gõ tên action: chạy được.
- [ ] Biến kết quả đọc được trong macro.

## Câu hỏi cần chốt khi plan chi tiết

- Dùng thư viện plugin có sẵn (vd TaskerPluginLibrary — kiểm giấy phép) hay tự viết theo chuẩn Locale?
- MacroDroid hỗ trợ plugin dạng trigger / điều kiện tới đâu (cần thử thật)?

## Rủi ro

- Mỗi app tự động hoá hỗ trợ chuẩn Locale một kiểu → ưu tiên MacroDroid, Tasker làm sau.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh
