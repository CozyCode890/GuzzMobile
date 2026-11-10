# Stage 2 — Lõi âm thanh tối giản

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 1

## Mục tiêu

Từ một tệp âm thanh hoặc video, tạo ra các đoạn âm thanh sẵn sàng gửi Gemini, kèm TimeMap để đổi mốc thời gian về tệp gốc.
Kết quả phải khớp bản Python.

## Phạm vi

### Làm

- `:core:media`:
  - liệt kê luồng âm thanh (tệp âm thanh, video một hoặc nhiều luồng); chọn luồng (`auto`, hoặc luồng 1…8; số ngoài
    khoảng thì lùi về luồng đầu);
  - giải mã → PCM mono 16 kHz (trộn kênh, resample); đọc thời lượng;
  - video không có âm thanh → `NoAudioError` (bỏ qua hay báo lỗi tuỳ cài đặt);
  - đọc được cả đường dẫn tuyệt đối lẫn content:// URI (chuẩn bị cho Stage 6);
  - ghi từng đoạn theo định dạng đã chọn ở Stage 1.
- `:core:audio`, port từ `audio_processing.py` và `chunker.py`:
  - `find_speech_segments`, `trim_silence`, `boost_and_normalize`, `measure_dbfs`;
  - TimeMap: đổi qua lại giữa mốc trên âm thanh đã cắt lặng và mốc trên tệp gốc;
  - `find_split_points`, `write_chunks`.
- Bộ nhớ: **không giữ cả bài trong RAM** dưới dạng FloatArray (bài 2 giờ ≈ 460 MB). Giải mã ra tệp PCM tạm rồi đọc theo
  khối, hoặc dùng mmap.
- Golden test: đưa **cùng một PCM** (giải mã bằng ffmpeg của desktop) vào cả hai bản, để khác biệt do bộ giải mã không làm
  lẫn kết quả. So điểm cắt đoạn, các khoảng có tiếng nói, TimeMap.
- Màn hình debug: chọn tệp → thư mục các đoạn + JSON danh sách đoạn + TimeMap.

### Không làm

- Khử ồn, bandpass, ghìm tiếng động đột ngột, cân bằng âm lượng theo thời gian, preset (Stage 7).
- Mã băm cache và làm tiếp (Stage 4).

## Nguồn tham khảo trong Guzz desktop

- `audio_io.py`, `video_io.py`, `chunker.py`
- `audio_processing.py`: `find_speech_segments`, `trim_silence`, `boost_and_normalize`, `measure_dbfs`, lớp TimeMap,
  `clean_audio` (thứ tự các bước)
- `tests/test_audio_processing.py`, `tests/test_video.py`
- `config.default.txt`: các mục `[AUDIO]`, `[VIDEO]`, cắt đoạn

## Kết quả bàn giao

- Module `:core:media` và `:core:audio` có test.
- Màn hình debug ra các đoạn từ tệp thật.

## Tiêu chí hoàn thành

- [ ] Golden test khớp Python: điểm cắt đoạn lệch không quá 1 khối 50 ms; TimeMap khớp.
- [ ] Bài 2 giờ chạy trên máy thật, RAM đỉnh dưới mức đặt ra ở Stage 1.
- [ ] Video có 2 luồng âm thanh: chọn đúng luồng.

## Câu hỏi cần chốt khi plan chi tiết

- Lưu PCM trung gian thế nào: tệp tạm + đọc theo khối, mmap, hay ShortArray trong RAM?
- Định dạng đoạn gửi Gemini (theo kết quả Stage 1): FLAC / OGG-Opus / WAV?
- Resampler: tự viết (windowed-sinc) hay dùng thư viện?
- Có cần hỗ trợ tệp dài hơn 3 giờ không?

## Rủi ro

- Mỗi hãng có bộ giải mã MediaCodec khác nhau, PCM ra lệch vài mẫu → golden test bắt đầu từ PCM, không bắt đầu từ tệp nén.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ plan Stage 0, 2026-10-31) `find_split_points` cùng `_block_energy`, `_moving_average` được port ở Stage 0 (bước H4)
  làm golden test mẫu, trên tín hiệu tổng hợp ở 1000 Hz. Stage 2 dùng lại bản đó, chỉ thêm `write_chunks` và golden test
  trên PCM thật 16 kHz. Công cụ đọc dữ liệu golden (JSON, WAV) nằm ở test fixtures của `:core:common`.
- (Từ Stage 0, 2026-10-31) `Chunker.findSplitPoints` hiện nhận cả bài trong một `FloatArray`. Bài 2 giờ ở 16 kHz là
  khoảng 460 MB (README §6, rủi ro 2): Stage 2 cần bản tính năng lượng khối theo từng khối đọc từ tệp PCM tạm (chỉ giữ
  mảng năng lượng, 50 ms một số), golden test hiện có vẫn phải xanh.
- (Từ Stage 1, 2026-11-10) Hai câu "Câu hỏi cần chốt" đã có đáp án: định dạng đoạn là **FLAC** (D41), resampler là
  **windowed-sinc tự viết** (D40). Số đo ở [stage-01-results.md](stage-01-results.md) §3–4.
  - FLAC: MediaCodec `audio/flac`, tự ghi tệp `.flac` (`fLaC` + STREAMINFO lấy từ buffer codec-config, rồi các khung;
    điền lại tổng số mẫu và MD5 sau khi ghi xong). Tham khảo `spikes/android/.../media/EncodeBench.kt`. 10 phút = 9,6 MB,
    mã hoá 4–6 s.
  - Resampler: port `spikes/android/.../media/SincResampler.kt` (32 zero-crossing, Kaiser β 8,6, cắt 7,52 kHz) và
    `SincResamplerTest`. Bản spike chậm: giải mã + sinc + ghi tệp của bài m4a 135 phút mất 22,5 phút, chỉ giải mã mất 5,4
    phút → tối ưu (polyphase với bảng hệ số tính sẵn cho tỉ lệ 3 : 1 và 160 : 441, bớt zero-crossing nếu SNR vẫn đạt) rồi
    đo lại.
  - Giải mã: dùng MediaCodec **chế độ async** (`setCallback`). Chế độ sync lúc màn hình tắt chỉ 3,6–8× thời gian thực,
    async ổn định khoảng 16×. Tham khảo `spikes/android/.../media/DecodeBench.kt`.
- (Từ Stage 1, 2026-11-10) Phần đo của Stage 1 chuyển sang đây, làm thành bước kiểm của bộ giải mã thật (D40):
  - giải mã `V1.mp4` (video 60 phút) và `L1.ogg` cả chuỗi;
  - thời lượng PCM ra lệch ≤ 0,1 s so với `ffmpeg -i`;
  - SNR của PCM giải mã trên máy so với `ffmpeg -ac 1 -ar 16000 -f f32le`, dùng `spikes/media/compare_pcm.py`.
  Tệp thử nằm ở `$env:GUZZ_SPIKE_DATA\media\` (ngoài repo).
