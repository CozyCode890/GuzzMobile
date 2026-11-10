# Stage 5 — Tách giọng trên máy

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 4 (và kết quả Stage 1)

## Mục tiêu

Có đủ 3 cách nhận diện người nói như desktop. Phần tách giọng chạy ngay trên điện thoại bằng sherpa-onnx, không cần torch
hay token Hugging Face.

## Phạm vi

### Làm

- `:core:diarization`:
  - bọc `OfflineSpeakerDiarization` của sherpa-onnx; model (segmentation + embedding, chọn ở Stage 1) nằm trong assets;
  - số người nói: biết chính xác → `num_clusters`; không biết → ngưỡng phân cụm (sherpa-onnx không có sẵn kiểu min–max
    như pyannote);
  - báo tiến độ, Dừng được giữa chừng; chạy trên âm thanh gốc hay âm thanh đã làm sạch;
  - kết quả xuất cùng định dạng JSON với `diarization_worker.py` (`bat_dau`, `ket_thuc`, `nguoi_noi`) để dùng lại test
    và dữ liệu của desktop.
- `:core:speaker`, port đủ `speaker.py`: `load_speaker_turns`, `remap_timestamps`, `find_main_speaker`, làm mượt (quy
  tắc 1 kẹp giữa, quy tắc 2 mật độ), `merge_turns`, `process_turns`, `build_name_map`, `minutes_per_speaker`,
  `split_turns_by_chunk`, `speaker_map_text`, `parse_prompt_paragraphs`, `words_to_spans`, `assign_speakers`,
  `merge_spans`, `spans_to_paragraphs`, `format_paragraph`.
- Ba cách:
  - **Trên máy + prompt** (desktop gọi là "pyannote + prompt"): gửi kèm bản đồ người nói, chỉ dùng model đa năng;
  - **Gemini tự tách**: chỉ dùng model `*-transcribe`;
  - **Kết hợp**: mốc thời gian từng từ của Gemini + lượt nói tính trên máy.
- Ràng buộc model ↔ cách nhận diện; đổi model thì tự chuyển cách nhận diện và báo cho người dùng.
- Pipeline: thêm bước (3), cache kết quả tách giọng theo `speaker_hash`, lưu kèm bản đồ người nói.
- UI: trang Người nói (rút gọn các tuỳ chỉnh của desktop).
- Golden test: cùng JSON lượt nói đầu vào → kết quả của `speaker.py` và bản Kotlin giống nhau. Port `test_speaker.py`.

### Không làm

- Cho người dùng tự chọn hay tải thêm model embedding (có thể để sau).

## Nguồn tham khảo trong Guzz desktop

- `speaker.py`, `diarization.py`, `diarization_worker.py`
- `transcribe.py`: `diarize_speakers`, phần người nói trong `transcribe_chunks` và `assemble_transcript`
- `prompt_diarization.txt`, `gui/pages/speaker.py`
- `tests/test_speaker.py`
- README desktop §5

## Kết quả bàn giao

- 3 cách nhận diện chạy được trên máy thật với bài giảng thật.

## Tiêu chí hoàn thành

- [ ] Golden test `speaker.py` khớp.
- [ ] Bài giảng thật: số người và thời gian nói của giảng viên gần với desktop (ngưỡng D29).
- [ ] Dừng giữa lúc đang tách giọng, rồi chạy lại thì dùng lại được kết quả đã cache.

## Câu hỏi cần chốt khi plan chi tiết

- Tên các cách nhận diện trên giao diện (không còn là pyannote đầy đủ)?
- Kiểu min–max số người của desktop: bỏ đi, hay giả lập (chạy vài ngưỡng rồi chọn kết quả nằm trong khoảng)?
- Với cách Gemini tự tách và Kết hợp: tách giọng chạy **song song** với việc gửi Gemini (nhanh hơn), hay tuần tự như
  desktop?
- Có cho chọn model embedding trong app không?

## Rủi ro

- Độ chính xác (README rủi ro 1). Nếu kém quá → điều chỉnh ngưỡng làm mượt, hoặc quay lại cân nhắc phương án tự ghép.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 1, 2026-11-10) Mặc định đã chọn ở Stage 1 ([stage-01-results.md](stage-01-results.md) §1–2): sherpa-onnx
  2.0.1, segmentation-3.0 fp32, embedding **CAM++ zh_en advanced** (dự phòng ERes2Net base 200k, chậm gấp 2,2 lần),
  `windowShiftRatio` 0,1, `minDurationOn` 0,2 / `minDurationOff` 0,5, **4 luồng**, **chia phần P = 20 phút, chồng lấn
  60 s** rồi nối người nói, chạy trong **process riêng** để Dừng được. Bài 2 giờ chia phần: 22,9 phút, PSS 556 MB,
  −8% pin, 41,1 °C (ngưỡng tạm 0,5). Model chọn đều MIT / Apache-2.0, không cần ghi công CC-BY.
- (Từ Stage 1, 2026-11-10) Việc đo còn lại của Stage 1 chuyển sang đây (D39), làm cùng lúc viết `:core:diarization`:
  - **Ngưỡng phân cụm khi không biết số người** (B5 cũ). Đây là rủi ro chính: ngưỡng 0,3–0,8 ra 12–471 cụm, 0 bài đạt
    D29. Khoảng cách cosine trong [0, 2] và complete-linkage cần ngưỡng cao hơn nhiều: thử từ 0,8 tới > 1,2 bằng
    `spikes/diarization/sweep.py B5` (JSON đã có ở `$env:GUZZ_SPIKE_DATA\out\pc\` thì bỏ qua). Chọn ngưỡng theo luật của
    B5 trong stage-01-spike.md. Không ngưỡng nào đạt thì **hỏi người dùng** (trắc nghiệm): bắt nhập số người, giả lập
    min–max bằng vài ngưỡng, hay đổi cách phân cụm / đổi hướng (README §2, D39). RAM phình theo số cụm, nên ngưỡng sai
    cũng làm sai số RAM.
  - Segmentation int8 (B6), chạy trên âm thanh gốc hay âm thanh sạch (B7), DER của bản chia phần so với nguyên bài (B8).
  - Trên máy, với ngưỡng thật: bài 2 giờ chia phần (`L1_120m` và `L2`, 5 người), pin và nhiệt khi rút cáp (D6 cũ), thời
    gian từ lúc bấm Dừng tới khi phần đang chạy xong (D7 cách 2).
- (Từ Stage 1, 2026-11-10) Lưu ý khi viết code:
  - JNI của sherpa-onnx tìm `invoke(IIJ)Ljava/lang/Integer;` trên callback; lambda Kotlin 2.x không có method đó và làm
    app chết vì JNI abort → dùng lớp callback viết rõ (`ProgressCallback` trong `spikes/android/.../diarize/`).
  - Giá trị trả về của callback bị bỏ qua: không dừng sherpa-onnx giữa chừng được, chỉ dừng được giữa các phần hoặc giết
    process (`Process.killProcess` process `:diarize` mất 54 ms, process chính sống).
  - Thuật toán nối người nói đã có bản Kotlin (`PieceLinker.kt`, `Hungarian.kt`) khớp bản Python 136/136 lượt.
- (Từ plan Stage 2, 2026-11-10) Stage 2 để lại trong thư mục tạm `goc.pcm` (âm thanh gốc) và `sach.pcm` (đã chuẩn hoá và
  cắt lặng), cả hai là PCM 16-bit 16 kHz mono, đọc theo khối bằng `Pcm16File` của `:core:audio`. Tách giọng chia phần đọc
  thẳng từ đây, không cần giải mã lại.
