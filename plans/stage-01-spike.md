# Stage 1 — Spike kỹ thuật

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 0

## Mục tiêu

Lấy số đo trên máy thật cho những câu hỏi có thể làm đổ kế hoạch, **trước khi** viết code thật. Code spike để trong
`spikes/`, không đưa vào app.

## Phạm vi

### Làm

1. **Tách giọng — độ chính xác (đo trên PC cho nhanh).** Chạy sherpa-onnx (gói pip `sherpa-onnx`, bản Python) trên 2–3 bản
   ghi lớp học thật mà desktop đã có kết quả pyannote community-1 (`nguoi_noi.*.json` trong thư mục tạm, hoặc
   `<tên>.nguoi_noi.txt`). So: số người nói, tỷ lệ thời gian gán sai, số lượt nói sai sau khi chạy qua bước làm mượt của
   `speaker.py`. Thử 2–3 model embedding (vd 3D-Speaker ERes2Net, CAM++) và vài ngưỡng phân cụm; thử trên âm thanh gốc và
   trên âm thanh đã làm sạch.
2. **Tách giọng — tốc độ và RAM trên điện thoại.** App spike tối thiểu, hoặc app demo diarization của sherpa-onnx, chạy bài
   1 giờ và 2 giờ: thời gian, RAM đỉnh, máy nóng tới đâu, tốn bao nhiêu pin.
3. **Giải mã.** MediaExtractor + MediaCodec giải mã m4a / mp3 / opus / mp4 dài 1–2 giờ ra PCM 16 kHz mono: mất bao lâu,
   dùng resampler nào.
4. **Định dạng đoạn gửi Gemini.** Máy có bộ mã hoá FLAC của MediaCodec không, hay dùng WAV / OGG-Opus; mỗi định dạng
   upload bao nhiêu MB một đoạn 10 phút.
5. **Intent → việc chạy nền.** MacroDroid gửi intent lúc màn hình tắt và Guzz không mở, thử từng cách:
   (a) broadcast, Guzz đã được miễn tối ưu pin;
   (b) activity trong suốt (MacroDroid có quyền hiện trên ứng dụng khác);
   (c) WorkManager expedited.
   Ghi lại cách nào khởi động được foreground service trên bản Android của máy thử.
6. **Giấy phép.** Kiểm lại giấy phép của từng tệp model định đóng gói.

### Không làm

- Code dùng lại cho app thật. Spike chỉ để đo rồi bỏ.

## Nguồn tham khảo trong Guzz desktop

- `diarization_worker.py`: định dạng JSON kết quả mà desktop dùng.
- `speaker.py`: `process_turns`, `smooth`, `merge_turns`.
- `runtime\nguoi_noi\python.exe`: chạy lại pyannote trên PC để có kết quả tham chiếu nếu cần.

## Kết quả bàn giao

- `plans/stage-01-results.md`: bảng số đo, model embedding và ngưỡng mặc định đã chọn, resampler, định dạng đoạn, cơ chế
  intent dùng được.
- Quyết định **đi tiếp / đổi hướng** cho sherpa-onnx. Nếu đổi hướng: tự ghép VAD + embedding trên ONNX Runtime, hoặc thu
  hẹp phạm vi tách giọng.
- Bảng quyết định trong [README §2](README.md#2-quyết-định-đã-chốt) đã cập nhật.

## Tiêu chí hoàn thành

- [ ] Cả 6 mục trên đều có số đo hoặc kết luận trên máy thật.
- [ ] Đã chọn được mặc định cho các stage 2, 5, 6.

## Câu hỏi cần chốt khi plan chi tiết

- Dùng bản ghi nào để đo (người dùng cung cấp, nên có sẵn kết quả của desktop)? Để ở đâu ngoài repo?
- Ngưỡng chấp nhận: bài 2 giờ phải xong trong bao lâu? Độ chính xác được kém desktop tới mức nào?
- Đo tốc độ bằng app spike tự viết, hay dùng app demo của sherpa-onnx?

## Rủi ro

- Không có kết quả desktop để so → chạy lại pyannote bằng `runtime\nguoi_noi` trên PC.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh
