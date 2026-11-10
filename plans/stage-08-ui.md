# Stage 8 — Hoàn thiện giao diện

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 5, 6, 7

## Mục tiêu

Mỗi stage trước chỉ làm màn hình tối thiểu. Stage này làm đủ các trang như desktop, rút gọn cho vừa màn hình điện thoại,
có tiếng Việt / tiếng Anh, giao diện sáng / tối.

## Phạm vi

### Làm

- Các trang:
  - **Chuyển đổi**: hàng đợi, cài đặt nhanh;
  - **Âm thanh, video & cắt đoạn**: phần còn thiếu sau Stage 7;
  - **Google AI Studio**: model, chế độ, ngôn ngữ, từ vựng, prompt, Tự đổi model;
  - **Người nói**;
  - **Hạn mức & sử dụng**: Bây giờ (đếm ngược), Các model, Hạn mức từng model, Lịch sử;
  - **Cài đặt**: Bản chép lời, Hàng đợi, Hệ thống, Thông tin;
  - **Nhật ký**;
  - **Tự động hoá**: phần làm ở Stage 6, hoàn thiện thêm.
- i18n: chuyển nội dung `gui/i18n.py` sang `strings.xml` vi / en, theo văn phong đã chuẩn hoá trong REFACTOR_PLAN của
  desktop.
- Chủ đề sáng / tối / theo hệ thống.
- Hướng dẫn lần đầu mở app: API key, quyền đọc tệp, tắt tối ưu pin, quyền thông báo.
- Xuất / nhập cài đặt.

### Không làm

- Tính năng mới.

## Nguồn tham khảo trong Guzz desktop

- `gui/pages/*`, `gui/i18n.py`, `gui/widgets.py`, `gui/model_ui.py`
- `REFACTOR_PLAN.md`: quy tắc văn phong giao diện

## Kết quả bàn giao

- Đủ các trang; mọi chuỗi trên giao diện nằm trong `strings.xml`.

## Tiêu chí hoàn thành

- [ ] Không còn chuỗi giao diện viết cứng trong code (lint `HardcodedText` sạch).
- [ ] Đổi ngôn ngữ và chủ đề không cần khởi động lại app.
- [ ] Màn hình nhỏ (360 dp) và cỡ chữ lớn không bị vỡ bố cục.

## Câu hỏi cần chốt khi plan chi tiết

- Điều hướng: thanh dưới (bottom navigation) hay menu ngăn kéo?
- Màu: Material You (lấy màu theo hình nền) hay màu nhấn riêng của Guzz?
- Có nhập `config.txt` của desktop không?
- Có làm bố cục riêng cho máy tính bảng không?

## Rủi ro

- Desktop có rất nhiều tuỳ chỉnh → cần chọn mục nào đưa ra màn hình chính, mục nào giấu vào "Nâng cao".

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 1, 2026-11-10) Đoạn gửi Gemini mặc định là FLAC (D41), 9,6 MB / 10 phút. Nếu muốn có tuỳ chọn "tiết kiệm dữ
  liệu di động": OGG-Opus 32 kbps chỉ 3 MB / 10 phút nhưng mã hoá mất 46 s (FLAC 4–6 s), và phải thử xem bản chép lời có
  kém đi không trước khi đưa ra (`spikes/formats/gemini_compare.py`, bước F3 cũ của Stage 1; gửi Gemini thì hỏi người
  dùng trước).
