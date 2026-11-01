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
- [ ] Bài giảng thật: số người và thời gian nói của giảng viên gần với desktop (ngưỡng đặt ở Stage 1).
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
