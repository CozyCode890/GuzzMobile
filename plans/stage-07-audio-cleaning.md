# Stage 7 — Làm sạch âm thanh đầy đủ

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 4

## Mục tiêu

Port đủ bước làm sạch âm thanh và các preset của desktop, để bản chép lời trên điện thoại tốt ngang bản desktop. Kết quả
từng bước phải khớp bản Python trong một sai số đã định.

## Phạm vi

### Làm

- Port từ `audio_processing.py`:
  - `bandpass_filter`: Butterworth dạng SOS, lọc theo khối như desktop. Phải tự thiết kế hệ số như `scipy.signal.butter`,
    hoặc tính sẵn cho các tham số preset dùng;
  - `reduce_noise`: spectral gating không dừng (non-stationary) của noisereduce — STFT, ước lượng sàn ồn, làm mượt mặt nạ;
  - `suppress_transients`: phân vị trượt, ghìm dải dưới 1 kHz trước rồi cả dải, hai lượt (trước và sau khử ồn);
  - `level_loudness`: cân bằng âm lượng khi giảng viên đi lại;
  - thứ tự các bước trong `clean_audio`.
- Preset: Ngồi gần giảng viên / Ngồi gần loa / Giảng viên đi lại / Loa nhiều hướng / Tuỳ chỉnh.
- Cache theo mã băm tham số: đổi preset thì xử lý lại, không đổi thì dùng lại các đoạn đã cắt.
- Lưu kèm âm thanh đã làm sạch (tuỳ chọn).
- Xử lý theo khối / dòng chảy để không tốn RAM (giống Stage 2).
- Golden test từng bước so với Python; đo thời gian bài 1 giờ trên máy thật.
- UI: trang Âm thanh, video & cắt đoạn.

### Không làm

- Khử ồn bằng model AI (RNNoise / DeepFilterNet): không chọn ở D7, chỉ cân nhắc lại nếu port DSP không đạt.

## Nguồn tham khảo trong Guzz desktop

- `audio_processing.py` (toàn bộ), `gui/presets.py`, `gui/pages/audio.py`
- `tests/test_audio_processing.py`, `tests/test_preset.py`
- README desktop §7 (preset Loa nhiều hướng và số đo)

## Kết quả bàn giao

- Làm sạch đầy đủ bật / tắt được, có preset.

## Tiêu chí hoàn thành

- [ ] Golden test từng bước: sai lệch so với Python dưới ngưỡng đã chốt.
- [ ] Bài 1 giờ: thời gian làm sạch trên máy thật dưới mức đã chốt.
- [ ] Nghe thử so với bản desktop trên cùng tệp: không nghe ra khác biệt.

## Câu hỏi cần chốt khi plan chi tiết

- FFT: JTransforms (Java thuần), KissFFT / pocketfft qua JNI, hay tự viết?
- Sai số chấp nhận khi so với Python (vd SNR giữa hai bản > 60 dB)?
- Có chạy nhiều luồng CPU không (nhanh hơn, nhưng nóng máy và tốn pin hơn)?
- Preset mặc định trên điện thoại (điện thoại thường đặt trên bàn, gần người ghi)?

## Rủi ro

- noisereduce có nhiều chi tiết ẩn (cách làm mượt mặt nạ, padding STFT) → đọc mã nguồn noisereduce 3.0.3 trong
  `D:\Guzz\runtime\python`, port theo đúng phiên bản đó.
- Chậm trên CPU yếu → cho phép tắt từng bước.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh
