# Stage 1 — Kết quả spike

> Điền dần trong lúc làm [Stage 1](stage-01-spike.md). Bài chỉ ghi bằng mã (B1–B5, L1, L2, A1, V1; bảng ở bước A2).
> Không chép lời nói hay tên tệp thật vào đây. Mỗi bảng ghi máy, bản Android và ngày đo.
>
> DER ở đây đo độ **lệch so với pyannote community-1 của desktop**. Tham chiếu là kết quả máy, không phải nhãn do người gán.

## Tóm tắt

| Mục | Kết luận | Mặc định chọn |
| --- | --- | --- |
| 1. Tách giọng: độ chính xác | chưa đo | |
| 2. Tách giọng: tốc độ, RAM, pin | chưa đo | |
| 3. Giải mã + resampler | chưa đo | |
| 4. Định dạng đoạn | chưa đo | |
| 5. Intent → chạy nền | chưa đo | |
| 6. Giấy phép | chưa kiểm | |

Đi tiếp / đổi hướng với sherpa-onnx: _chưa quyết_ (bước I2).

## 1. Độ chính xác trên PC

Cấu hình chung: sherpa-onnx 2.0.1, segmentation-3.0, DER đo với collar 0,5 s. Số liệu đo sau `process_turns`; số trong
ngoặc là trước khi làm mượt.

### B3. windowShiftRatio

| shift | DER TB | thời gian (s) | RAM đỉnh (MB) |
| --- | --- | --- | --- |

### B4. Embedding (num_clusters = số người của desktop)

| Embedding | B1 | B2 | B3 | B4 | B5 | TB |
| --- | --- | --- | --- | --- | --- | --- |

### B5. Ngưỡng phân cụm

| Embedding | Ngưỡng | DER TB | Số bài đạt D29 | Bài trượt (tiêu chí) |
| --- | --- | --- | --- | --- |

### B6–B8. int8, âm thanh gốc, chia phần

| Biến thể | B1 | B2 | B3 | B4 | B5 | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |

## 2. Tách giọng trên máy

| Đầu vào | Cách chạy | Luồng | Thời gian | PSS đỉnh | VmHWM | Heap Java đỉnh | Pin | Nhiệt đỉnh |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

Dừng giữa chừng (D7):

Dung lượng thư viện (C3):

## 3. Giải mã và resampler

| Tệp | Bộ giải mã | Resampler | × thời gian thực | PSS đỉnh | Lệch thời lượng |
| --- | --- | --- | --- | --- | --- |

| Resampler | SNR sweep 0–7 kHz | Suy giảm 10 kHz / 15 kHz | SNR tệp thật |
| --- | --- | --- | --- |

## 4. Định dạng đoạn

Bộ mã hoá có trên máy (F1):

| Định dạng | Bitrate / mức | MB / 10 phút | Thời gian mã hoá | Kiểm ffmpeg | Độ giống với FLAC 1 (Gemini) |
| --- | --- | --- | --- | --- | --- |

## 5. Intent → chạy nền

| Cách | Cài đặt | FGS lên / lần gửi | Lỗi gặp |
| --- | --- | --- | --- |

Chạy dài (G4), onTimeout (G5), sự kiện gửi ngược về MacroDroid (G6):

Hướng dẫn cài đặt cần ghi cho người dùng:

## 6. Giấy phép

| Thành phần | Giấy phép | Nguồn (URL) | Ngày kiểm | Ghi công |
| --- | --- | --- | --- | --- |
