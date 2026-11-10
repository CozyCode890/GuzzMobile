# Stage 2 — Lõi âm thanh tối giản

**Trạng thái:** 🔨 đang làm từ 2026-11-10 · **Cần xong trước:** 1

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
  - ghi từng đoạn ra FLAC (D41, D48).
- `:core:audio`, port từ `audio_processing.py` và `chunker.py`:
  - `find_speech_segments`, `trim_silence`, `boost_and_normalize`, `measure_dbfs`;
  - TimeMap: đổi qua lại giữa mốc trên âm thanh đã cắt lặng và mốc trên tệp gốc;
  - `find_split_points`, `write_chunks`.
- Bộ nhớ: **không giữ cả bài trong RAM** dưới dạng FloatArray (bài 2 giờ ≈ 460 MB). Giải mã ra tệp PCM 16-bit tạm rồi
  đọc theo khối (D43).
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

- [ ] Golden test khớp Python: điểm cắt đoạn lệch không quá 1 khối 50 ms; TimeMap khớp (trên tín hiệu tổng hợp: khớp
  hẳn; trên bản ghi thật: theo dung sai ở bước B9).
- [ ] Bài 2 giờ chạy trên máy thật lúc màn hình tắt: từ tệp gốc tới các đoạn FLAC **≤ 10 phút, PSS đỉnh ≤ 300 MB** (D45).
- [ ] Video có 2 luồng âm thanh: chọn đúng luồng.
- [ ] Phần chuyển từ Stage 1 (D40): giải mã được video MP4; thời lượng PCM lệch ≤ 0,1 s so với ffmpeg; SNR so với ffmpeg
  đã đo và ghi lại.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-10, xem mục [Quyết định](#quyết-định). Định dạng đoạn và resampler đã chốt ở Stage 1 (D41, D40);
PCM trung gian → D43; tệp dài hơn 3 giờ → D44. Hỏi thêm lúc plan: ngưỡng tốc độ → D45; dữ liệu golden → D46; cài đặt
[VIDEO] → D47; định dạng trong cài đặt → D48; màn hình thử → D49.

## Quyết định

Hỏi người dùng ngày 2026-11-10. Người dùng chọn phương án Recommended ở cả 7 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D43 | Giữ PCM của cả bài ở đâu | **Tệp tạm PCM 16-bit 16 kHz mono, đọc / ghi theo khối** | RAM không tăng theo độ dài bài; desktop cũng đọc s16 từ ffmpeg nên golden test dễ khớp. |
| D44 | Tệp dài hơn 3 giờ | **Không giới hạn; trước khi giải mã ước lượng dung lượng tạm, thiếu chỗ thì báo lỗi rõ** | Đọc theo khối nên chỉ dung lượng trống mới giới hạn độ dài. |
| D45 | Ngưỡng tốc độ | Bài 2 giờ, từ tệp gốc tới các đoạn FLAC, Xiaomi 15T, màn hình tắt: **≤ 10 phút, PSS đỉnh ≤ 300 MB** | Phải tối ưu resampler (bản spike 22,5 phút) nhưng không phải vắt kiệt. |
| D46 | Dữ liệu golden | **Tín hiệu tổng hợp commit vào repo + test trên bản ghi thật chạy tại máy** (B1–B5 của Stage 1, ngoài repo), tự bỏ qua khi không có dữ liệu | Repo không chứa bản ghi thật, nhưng vẫn biết chắc khớp trên bài giảng 1–2 giờ. |
| D47 | Cài đặt [VIDEO] `extract_first`, `extract_format`, `save_extracted` | **Bỏ hai mục đầu; `save_extracted` để Stage 4** | Mobile luôn giải mã một lần ra PCM tạm, tức luôn "tách trước". `save_extracted` cần thư mục lưu kết quả. |
| D48 | [CHUNKING] `format` | **Chỉ `flac`**; giá trị khác (nhập từ desktop) tự về `flac` | Đúng D41; Android không có bộ mã hoá MP3. |
| D49 | Màn hình thử trên máy | **Màn hình tối giản chỉ trong bản debug**, nhận thêm lệnh adb | Thử được trình chọn tệp và content:// URI; Stage 4 thay bằng màn hình Hàng đợi. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Thứ tự xử lý giống `clean_audio` của desktop, bỏ các bước của Stage 7 (D7): giải mã → `boost_and_normalize` →
  `trim_silence` → `find_split_points` → ghi đoạn. Desktop mặc định bật `denoise` và `level_window_sec = 3`, nên đầu ra
  của mobile ở Stage 2 khác desktop với cài đặt mặc định; golden test gọi thẳng từng hàm của desktop, không qua
  `clean_audio`.
- Tần số xử lý cố định **16 kHz**; không đưa `[AUDIO] sample_rate` vào cài đặt (desktop mặc định 16000, Gemini cũng hạ về
  16 kHz).
- Đọc PCM thô qua interface `SampleSource` (đọc một khoảng mẫu ra `FloatArray`, giá trị = int16 / 32768, đúng như
  `read_audio` của desktop). Bản chạy thật đọc tệp; test dùng mảng.
- Không lưu tín hiệu đã khuếch đại: mỗi lượt đọc lại PCM thô rồi nhân hệ số khuếch đại (float32) và kẹp [−1, 1], ra đúng
  từng giá trị float32 như numpy. Các lượt đọc tệp PCM thô:
  1. đo dBFS → hệ số khuếch đại;
  2. đo dBFS sau khuếch đại → ngưỡng im lặng của `trim_silence`;
  3. tìm các khoảng có tiếng (`find_speech_segments`, cửa sổ trượt, bộ nhớ chỉ cỡ `min_silence_len`);
  4. ghép các khoảng giữ lại → ghi `sach.pcm` (int16, lượng tử hoá như `_to_pcm16` của desktop: kẹp, × 32767, cắt về 0)
     và tính năng lượng khối 50 ms cho chunker trên giá trị float.
  
  Sau đó tìm điểm cắt, rồi đọc `sach.pcm` để mã hoá từng đoạn.
- Desktop đo dBFS bằng `np.dot` float32 (BLAS), không tái tạo đúng từng bit được; Kotlin cộng bằng double. Lệch cỡ 1e-5
  dB, chỉ làm đổi kết quả khi một cửa sổ nằm sát ngưỡng → tín hiệu tổng hợp được dựng có biên an toàn như golden của
  Stage 0; bản ghi thật so có dung sai (B9).
- Thư mục tạm của một bài (Stage 4 mới đặt tên theo mã băm): `goc.pcm`, `sach.pcm`, `doan_001.flac`…, ghi
  `danh_sach_doan.json` **sau cùng**. Tệp ghi ra `.part` rồi đổi tên, như desktop. JSON cùng khoá với desktop (`nguon`,
  `tan_so_lay_mau`, `thoi_luong_goc`, `ban_do_thoi_gian`, `doan` với `file`, `bat_dau_giay`, `ket_thuc_giay`), chưa có
  `ma` (Stage 4). `sach.pcm` giữ lại cho Stage 5 tách giọng.
- Ước lượng dung lượng tạm (D44): thời lượng (đọc từ MediaExtractor) × (32 KB/s `goc.pcm` + 32 KB/s `sach.pcm` + 20 KB/s
  FLAC) + 10% dư, tức khoảng 330 MB mỗi giờ.
- Module: `SincResampler` và đọc / ghi PCM 16-bit nằm ở `:core:audio` (JVM thuần, test trên PC), TimeMap ở `:core:common`
  như README §3. `:core:media` (Android) chỉ còn MediaExtractor, MediaCodec (giải mã, mã hoá FLAC), dung lượng trống.
- Trộn kênh về mono bằng trung bình các kênh. Nếu SNR so với ffmpeg (bước D3) thấp vì cách trộn khác thì sửa theo ffmpeg.
- Đổi tần số bằng windowed-sinc polyphase (D40): tỉ lệ rút gọn L / M (48 k → 16 k là 1 / 3, 44,1 k → 16 k là 160 / 441), bảng
  hệ số tính sẵn cho L pha; L > 1 024 (tần số lạ) thì tính hệ số tại chỗ và ghi cảnh báo vào log. Tần số cắt =
  0,47 × min(vào, ra), nên lên mẫu từ 8 kHz cũng đúng. Đầu vào đã 16 kHz thì bỏ qua bước này.
- Từ float về int16 khi ghi `goc.pcm`: làm tròn tới số gần nhất (× 32768) rồi kẹp, như ffmpeg ghi `s16le`.
- MediaCodec chạy **chế độ async** (Stage 1: sync chậm gấp đôi lúc màn hình tắt). Nhận cả PCM 16-bit lẫn float
  (`KEY_PCM_ENCODING`), xử lý đổi định dạng giữa chừng.
- Lỗi: tệp không mở được → `MediaReadException` (không được lẫn thành "không có tiếng"); video không có luồng âm thanh →
  `NoAudioError`; giải mã ra 0 mẫu → lỗi; cắt lặng xong không còn gì → lỗi như desktop. Bỏ qua hay báo lỗi khi video
  không có tiếng (`skip_silent`) là việc của Stage 4.
- Báo tiến độ theo bước (giải mã, chuẩn hoá, cắt lặng, cắt đoạn, ghi đoạn) và dừng được giữa các khối (coroutine
  `ensureActive`); dừng thì không ghi `danh_sach_doan.json`.
- Cài đặt thêm vào `SettingKeys`: `[AUDIO]` `target_dbfs`, `trim_silence`, `min_silence_len`, `silence_thresh_offset`,
  `keep_silence`; `[VIDEO]` `audio_track`; `[CHUNKING]` `chunk_minutes`, `search_seconds`, `min_last_chunk_seconds`,
  `format`. Mục nào chỉ stage sau dùng (`extensions`, `accept_video`, `skip_silent`, `denoise`…) thì stage đó thêm.
- Tệp thử để chạy trên máy nằm trong `files/` của app (`/sdcard/Android/data/<package>/files/input/`), đọc được không cần
  quyền; kết quả màn hình thử ghi vào `files/debug-audio/<tên bài>/` để `adb pull`.

## Rủi ro

- Mỗi hãng có bộ giải mã MediaCodec khác nhau, PCM ra lệch vài mẫu → golden test bắt đầu từ PCM, không bắt đầu từ tệp nén.

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Lệnh chạy trong PowerShell 7 tại `D:\GuzzMobile`. Port hàm
nào thì KDoc ghi nguồn (`Port của audio_processing.py: trim_silence`) và những chỗ phải giữ đúng như Python (làm tròn,
`int()`, thứ tự cộng), như `Chunker.kt`.

Thứ tự: A → B (JVM, test trên PC, không cần điện thoại) → C → D (trên máy) → E. B7 (resampler) độc lập, làm lúc nào cũng
được.

### A. Khung

- [x] **A1. Module `:core:media`** (plugin `guzz.android.library`), phụ thuộc `:core:audio`, `:core:common`, coroutines;
  thêm vào `settings.gradle.kts`; `:app` phụ thuộc `:core:media`.
  **Kiểm:** `.\gradlew projects` có `:core:media`; `.\gradlew assembleSideloadDebug test lint` xanh.
- [x] **A2. Cài đặt** (mục "Mặc định kỹ thuật"): thêm vào `SettingKeys` với chặn trên / dưới như `config.py`.
  `[CHUNKING] format` là `ChoiceSetting` chỉ có `flac` (D48). Bỏ `[VIDEO] extract_first`, `extract_format` (D47).
  **Kiểm:** `DesktopDefaultsTest` xanh và có các mục mới (mặc định khớp desktop); test `format = wav` → `flac`.

### B. Thuật toán JVM và golden test (`:core:common`, `:core:audio`)

- [x] **B1. TimeMap** ở `:core:common`, port lớp `TimeMap` (`untrimmed`, `to_original`, `to_clean`, `to_json`).
  `tools/golden/gen_timemap.py`: vài bản đồ (rỗng, một đoạn, nhiều đoạn), mỗi bản đồ đổi một loạt mốc (đầu, cuối, trong
  khoảng đã cắt, ngoài cùng) theo cả hai chiều.
  **Kiểm:** `TimeMapGoldenTest` xanh, kết quả khớp tới 1e-9 s.
- [x] **B2. PCM 16-bit:** `SampleSource` (đọc khoảng mẫu ra `FloatArray`), `Pcm16File` (đọc theo khối từ tệp),
  `Pcm16Writer` (ghi `.part`, `close()` thì đổi tên), `FloatArraySource` cho test.
  **Kiểm:** unit test: ghi rồi đọc lại trùng từng mẫu; đọc vắt qua ranh giới khối; giá trị = int16 / 32768 (so với
  `Golden.readWav` của Stage 0).
- [x] **B3. Âm lượng:** port `measure_dbfs` (theo khối), `boost_and_normalize` dạng `SampleSource` bọc ngoài (nhân hệ số
  float32 rồi kẹp). `tools/golden/gen_audio_processing.py` (dùng chung cho B3–B5, 2 kHz để WAV gọn): tín hiệu bình
  thường, tín hiệu bị kẹp khi khuếch đại, toàn số 0 (dBFS = −∞ → giữ nguyên).
  **Kiểm:** dBFS lệch ≤ 1e-4 dB; hệ số float32 trùng; mọi mẫu sau khuếch đại trùng từng bit.
- [x] **B4. `find_speech_segments`** dạng chạy một lượt, bộ nhớ cỡ `min_silence_len`, giữ đúng phép tính của Python
  (`round` / `int` của thời lượng ms, tổng tích luỹ double, `floor(rms) <= ngưỡng`). Ca golden: không có chỗ lặng; lặng
  ở đầu / cuối; khoảng lặng ngắn hơn `min_silence_len`; toàn lặng → `[]`; tệp ngắn hơn cửa sổ; số mẫu không chia hết cho
  1 ms. Script golden kiểm biên an toàn: không cửa sổ nào có rms sát ngưỡng làm tròn (như `gen_chunker.py`).
  **Kiểm:** `SpeechSegmentsGoldenTest` khớp hẳn mọi khoảng.
- [x] **B5. `trim_silence`** → các khoảng mẫu giữ lại + TimeMap. Ca golden: `keep_silence` làm hai khoảng dính nhau thì
  gộp; khoảng chạm đầu / cuối tệp; không có khoảng nào → giữ cả tệp.
  **Kiểm:** golden khớp hẳn (khoảng mẫu và `ban_do_thoi_gian`).
- [x] **B6. Chunker theo khối.** Tách `findSplitPoints` thành bộ cộng năng lượng khối 50 ms (nạp dần từng khối mẫu) và
  phần tìm điểm cắt trên mảng năng lượng; giữ bản nhận `FloatArray` cho test cũ (việc ở "Phát sinh" từ Stage 0).
  **Kiểm:** `ChunkerGoldenTest` cũ vẫn xanh; test mới: trên tín hiệu ngẫu nhiên, nạp theo khối độ dài bất kỳ cho cùng
  kết quả với bản mảng.
- [x] **B7. `SincResampler`** ở `:core:audio`: port bản spike (`spikes/android/.../media/SincResampler.kt`), viết lại
  dạng polyphase (mục "Mặc định kỹ thuật"), chạy theo khối.
  **Kiểm:**
  - unit test: sin 1 kHz 48 k → 16 k đúng biên độ (±0,01 dB) và đúng pha sau khi bù độ trễ; sin 10 / 15 kHz bị suy giảm
    ≤ −80 dB; các tần số vào 8 k / 16 k / 22,05 k / 44,1 k / 48 k / 96 k đều ra đúng số mẫu (±1); nạp theo khối bất kỳ cho
    cùng kết quả với nạp một lần;
  - đo trên PC: đổi 2 giờ mono 48 kHz → 16 kHz, ghi thời gian vào Nhật ký. Mục tiêu nhanh hơn bản spike ≥ 5 lần (bản spike
    mất khoảng 6 phút trên máy cho 135 phút âm thanh, phần resample và ghi).
- [ ] **B8. `ChunkPreparer`** (port của `transcribe.prepare_chunks`, chưa có cache): nhận `SampleSource` của `goc.pcm`,
  cài đặt, thư mục tạm, `ChunkEncoder` (interface; test dùng bản ghi WAV, máy dùng FLAC ở C3), callback tiến độ. Chạy các
  lượt ở "Mặc định kỹ thuật", ghi `danh_sach_doan.json` sau cùng. `trim_silence = false` thì TimeMap không cắt.
  `tools/golden/gen_prepare.py`: một bài tổng hợp vài phút ở 2 kHz với tham số thu nhỏ (`chunk_minutes` = 0,5…), chạy
  `boost_and_normalize` → `trim_silence` → `find_split_points` → `write_chunks` của desktop.
  **Kiểm:**
  - golden: điểm cắt, `ban_do_thoi_gian`, `thoi_luong_goc`, mốc từng đoạn và mẫu int16 của từng đoạn (đọc lại WAV) khớp
    desktop;
  - huỷ giữa chừng thì không có `danh_sach_doan.json`, không còn tệp `.part`;
  - bài toàn lặng / cắt xong không còn gì thì báo lỗi đúng như desktop.
- [ ] **B9. Golden trên bản ghi thật (D46).** `tools/golden/real_golden.py` (không tên `gen_*` để `regenerate.ps1` không
  chạy nó): với B1–B5, đọc `goc16k.wav` trong `$env:GUZZ_SPIKE_DATA\<mã>\` (Stage 1, giải mã bằng ffmpeg), chạy các hàm
  desktop như B8 với cài đặt mặc định, ghi `$env:GUZZ_SPIKE_DATA\golden-stage-02\<mã>.json` (ngoài repo). Tham số
  `--pcm <tệp>` để chạy trên `goc.pcm` lấy từ điện thoại (D3). `RealRecordingGoldenTest` tự bỏ qua khi không có biến
  `GUZZ_SPIKE_DATA` hoặc không có JSON.
  **Kiểm:** với cả 5 bài:
  - TimeMap: tổng thời lượng giữ lại lệch ≤ 0,1 s; ≥ 99,9% mốc khớp trong ±10 ms (số mốc lệch được in ra);
  - điểm cắt đoạn lệch ≤ 1 khối 50 ms;
  - thời gian chạy bản Kotlin trên PC và heap đỉnh ghi vào Nhật ký; heap đỉnh không tăng theo độ dài bài (so B4 47 phút
    với B2 95 phút).

### C. Giải mã và mã hoá (`:core:media`)

- [ ] **C1. Luồng âm thanh.** `MediaInput` (đường dẫn hoặc content:// URI → `MediaExtractor` qua file descriptor),
  `listAudioTracks` (thứ tự, MIME, ngôn ngữ, tần số, số kênh, thời lượng), `selectAudioTrack` (port `video_io`).
  **Kiểm:** unit test JVM port `TestSelectTrack` của `tests/test_video.py` (`auto`, rỗng, số hợp lệ, số ngoài khoảng, chữ
  linh tinh, không có luồng nào).
- [ ] **C2. `AudioDecoder`:** MediaCodec async → trộn mono → `SincResampler` → int16 → `Pcm16Writer` (`goc.pcm`). Báo tiến
  độ theo thời điểm mẫu / thời lượng, dừng được. Kiểm dung lượng trống trước khi bắt đầu (D44): thiếu thì
  `InsufficientStorageException` kèm số MB cần và còn.
  **Kiểm:** unit test JVM cho công thức ước lượng dung lượng và cho bước trộn kênh; phần còn lại kiểm ở C4.
- [ ] **C3. `FlacEncoder`** (`ChunkEncoder` cho máy): MediaCodec `audio/flac`, tự ghi tệp `.flac` (port từ
  `spikes/android/.../media/EncodeBench.kt`). Phần ghép header (`fLaC` + STREAMINFO từ buffer codec-config, điền tổng số
  mẫu và MD5) là hàm thuần.
  **Kiểm:** unit test JVM cho phần ghép header (STREAMINFO đúng số mẫu, MD5 của PCM).
- [ ] **C4. Test trên máy** (`.\gradlew :core:media:connectedDebugAndroidTest`, điện thoại nối adb). Tệp thử tổng hợp, sinh
  bằng ffmpeg của desktop qua `tools/golden/gen_media.py`, commit vào `testdata/media/` (vài chục KB, không phải bản ghi
  thật): m4a AAC stereo 44,1 kHz; mp3 mono 48 kHz; ogg Opus; mp4 có hình và 2 luồng âm thanh (440 Hz và 880 Hz); mp4 có
  hình không có tiếng; một tệp hỏng (cắt cụt).
  **Kiểm:**
  - mỗi tệp: thời lượng ra lệch ≤ 20 ms, tần số trội đúng;
  - mp4 2 luồng: chọn luồng 1 ra 880 Hz, `auto` ra 440 Hz;
  - mp4 không tiếng → `NoAudioError`; tệp hỏng → `MediaReadException`;
  - FLAC: mã hoá rồi giải mã lại trùng từng mẫu.

### D. Màn hình thử và đo trên máy (D45, D49)

- [ ] **D1. Màn hình thử** trong `app/src/debug/`: chọn tệp (`OpenDocument`, `audio/*` và `video/*`), chọn luồng, Chạy /
  Dừng, tiến độ theo bước, kết quả (thời lượng gốc / sạch, số đoạn, thời gian từng bước, PSS đỉnh, đường dẫn). Chạy trong
  một foreground service chỉ có ở bản debug, giữ partial wake lock (như `BenchService` của app spike), để đo được lúc màn
  hình tắt; Stage 4 thay bằng service thật. Nhận lệnh adb: `--es path <tệp trong files/input>`, `--es track`,
  `--ez auto_run true`. Kết quả ghi `files/debug-audio/<tên bài>/ket_qua.json`, có cả log.
  **Kiểm:** `.\gradlew installSideloadDebug`, chạy bằng adb một tệp ngắn ra đủ `goc.pcm`, `sach.pcm`, `doan_*.flac`,
  `danh_sach_doan.json`, `ket_qua.json`; bấm Dừng giữa chừng thì không có `danh_sach_doan.json`.
- [ ] **D2. Đo trên máy** (lệnh adb, màn hình tắt). Đẩy lên `files/input/`: `L1_120m.m4a` (cắt 120 phút đầu của `L1.m4a`
  bằng `ffmpeg -t 7200 -c copy`), `A1.aac`, `L1.mp3`, `L1.ogg`, `V1.mp4` (tệp ở `$env:GUZZ_SPIKE_DATA\media\`).
  **Kiểm:** bảng trong Nhật ký: × thời gian thực, PSS đỉnh, thời gian từng bước. `L1_120m` ≤ 10 phút và PSS ≤ 300 MB
  (D45). Mọi tệp: thời lượng `goc.pcm` lệch ≤ 0,1 s so với `ffmpeg -i`.
- [ ] **D3. So với ffmpeg và với Python trên dữ liệu thật.** `adb pull` `goc.pcm` của `L1_120m` và `V1`:
  - SNR so với `ffmpeg -ac 1 -ar 16000 -f s16le` (sửa `spikes/media/compare_pcm.py` cho đọc int16), căn độ trễ bằng
    tương quan chéo;
  - `real_golden.py --pcm` trên chính `goc.pcm` đó, so `danh_sach_doan.json` của máy theo dung sai B9.

  **Kiểm:** SNR ghi vào Nhật ký; dưới 30 dB thì tìm nguyên nhân (cách trộn kênh, độ trễ) trước khi đóng stage. So với
  Python đạt dung sai B9.
- [ ] **D4. content:// URI:** `adb push` `A1.aac` vào `/sdcard/Download/`, chọn bằng màn hình thử.
  **Kiểm:** kết quả giống hệt chạy cùng tệp bằng đường dẫn (cùng TimeMap, cùng số đoạn).

### E. Đóng stage

- [ ] **E1. Tài liệu:** CLAUDE.md thêm lệnh chạy golden trên bản ghi thật, test trên máy, mở màn hình thử bằng adb;
  README §3 sửa dòng `:core:audio` / `:core:media` (resampler và PCM 16-bit chuyển sang `:core:audio`).
  **Kiểm:** chạy lại từng lệnh đã chép, lệnh nào cũng chạy được.
- [ ] **E2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 2 → ✅ trong CLAUDE.md, "Việc tiếp theo" → plan Stage 3
  (hoặc Stage 4 nếu Stage 3 đã xong); commit, push.
  **Kiểm:** `git ls-files | Select-String '\.(flac|m4a|aac|mp3|ogg|mp4|pcm|f32)$'` chỉ ra tệp trong `testdata/`;
  `git status -sb` báo `## main...origin/main`.

## Nhật ký

- **2026-11-10, plan.** Đọc `audio_io.py`, `video_io.py`, `chunker.py`, `audio_processing.py`, `transcribe.prepare_chunks`
  của desktop và mục "Phát sinh" từ Stage 0, 1. Hỏi người dùng 7 câu trắc nghiệm, cả 7 chọn Recommended (D43–D49). Theo
  yêu cầu của người dùng ở Stage 1 (vừa làm vừa thử), phép đo nằm trong các bước làm thật (D2–D3) chứ không có bước đo
  riêng.
- **2026-11-10, làm A–B7** (session riêng, tách từ session plan).
  - A2: desktop không chặn các số `[AUDIO]` / `[CHUNKING]` (chỉ báo lỗi `chunk_minutes <= 0`); mobile kẹp `min_silence_len`,
    `keep_silence` ≥ 0, `chunk_minutes` ≥ 0,1, `search_seconds`, `min_last_chunk_seconds` ≥ 0. `DesktopDefaultsTest` so số
    thực theo giá trị: `config.default.txt` viết `-16.0`, `_format_setting` ghi `-16`.
  - B3: hệ số khuếch đại float32 của desktop lệch 1–2 ulp so với hệ số tính từ tổng chính xác ngay ở ca golden đầu tiên
    (np.dot float32 của BLAS cộng sai cỡ 1e-7 tương đối). Kotlin cộng double nên trùng hệ số "chính xác". Golden ghi cả
    hai; test so hệ số theo dung sai 4 ulp, còn tìm khoảng có tiếng / cắt lặng chạy trên tín hiệu khuếch đại bằng đúng hệ
    số của desktop và khớp tuyệt đối. Với bản ghi thật (B9) chính điều này sinh ra lệch nhỏ, nên B9 có dung sai.
  - B4: đã kiểm trên numpy 2.5.3 của desktop: `np.add.reduceat` (float64) = phần tử đầu + `pairwise_sum` của phần còn
    lại, khớp từng bit với mọi độ dài ô 2–300; `np.cumsum` cộng tuần tự. Kotlin làm đúng như vậy (`SpeechSegments.cellSum`),
    có golden kiểm 300 ô đầu của ca 16 kHz.
  - B7: trên PC, 2 giờ mono 48 kHz → 16 kHz mất 9,7 s (744 lần thời gian thực); bản spike 23,5 s, tức chỉ nhanh hơn 2,4
    lần, chưa tới mục tiêu ≥ 5 lần của plan. 8 tổng riêng không nhanh hơn 4 tổng. Mục tiêu 5 lần dựa trên giả định resample
    là phần chậm của bản spike trên máy (6 phút cho 135 phút âm thanh), nhưng tính theo số phép nhân cộng thì resample chỉ
    cỡ chục giây; phần chậm của spike nằm ở chỗ khác (ghi float từng mẫu, trộn kênh). Giữ 32 zero-crossing; ngưỡng thật là
    D45, đo ở D2. Nếu D2 cho thấy resample vẫn là nút thắt thì mới giảm zero-crossing (hỏi người dùng trước).

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
