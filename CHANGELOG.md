# Nhật ký thay đổi

Các bản phát hành của Guzz Mobile. Số phiên bản theo [SemVer](https://semver.org/lang/vi/); bản mới nhất ở trên.

## 1.0.0

Bản phát hành đầu tiên.

### Chép lời

- Chọn tệp âm thanh hoặc video (lấy luồng âm thanh, chọn được luồng khi video có nhiều luồng) → bản chép lời `.txt`.
- Gửi từng đoạn lên Google AI Studio (Gemini) bằng API key của người dùng; 4 cách chép lời như Guzz desktop (thông minh,
  nguyên văn, mốc từng từ, tách người nói); từ vựng, ngôn ngữ, prompt sửa được.
- Hạn mức theo model, tự khoá model hết hạn mức và đổi sang model dự phòng; tab Hạn mức xem số đã dùng.
- Mẫu tên tệp, xử lý trùng tên, mã hoá / xuống dòng, đầu tệp, đánh dấu đoạn như desktop.

### Âm thanh

- Giải mã bằng MediaCodec của Android, không cần FFmpeg.
- Làm sạch đủ như desktop: chuẩn hoá âm lượng, cắt khoảng lặng, lọc dải, khử ồn, cân mức, ghìm tiếng động đột ngột;
  4 preset (gần giảng viên, gần người nói, giảng viên đi lại, nhiều người xung quanh). Kết quả khớp bản Python của desktop.
- Chia đoạn khoảng 10 phút ở chỗ lặng nhất; lưu được âm thanh tách từ video.

### Nhận diện người nói

- Chạy ngay trên máy bằng sherpa-onnx (pyannote segmentation-3.0 + 3D-Speaker CAM++), trong process riêng, dừng được giữa
  chừng; bài dài chia phần rồi nối người nói giữa các phần.
- 3 cách như desktop: trên máy + prompt, nhờ Gemini, kết hợp; đặt trước số người hoặc khoảng min–max.

### Chạy nền và tự động hoá

- Hàng đợi bền, chạy nền bằng foreground service khi tắt màn hình; dừng rồi làm tiếp không gửi lại đoạn đã xong; app bị
  hệ thống tắt thì mở lại là làm tiếp.
- Intent cho MacroDroid / Tasker: lệnh TRANSCRIBE / START / STOP / STATUS, sự kiện FILE_STARTED / FILE_DONE / FILE_FAILED
  / QUEUE_FINISHED / ERROR, có mã bí mật (tự tạo khi bật). Tài liệu: `docs/intents.md`.
- "Chia sẻ tới Guzz" từ app khác (cả Zalo); bản tự cài nhận đường dẫn tuyệt đối khi có quyền "Truy cập mọi tệp".

### Giao diện

- 3 tab Chuyển đổi · Hạn mức · Cài đặt; đủ trang cài đặt như desktop, mục ít dùng gập vào "Nâng cao".
- Tiếng Việt / tiếng Anh (chọn trong app), sáng / tối, Material You; thẻ "Thiết lập ban đầu".
- Xuất / nhập cài đặt dạng `config.txt` của Guzz desktop (không xuất API key); xem và chia sẻ nhật ký.

### Bảo mật

- API key mã hoá bằng Android Keystore, không sao lưu, không chuyển sang máy mới, không ghi vào nhật ký.
- Lệnh từ app khác không đọc / ghi được thư mục riêng của Guzz, không nhận đường dẫn có `..`; "Chia sẻ tới Guzz" chỉ nhận
  `content://`; chỉ kết nối HTTPS.
