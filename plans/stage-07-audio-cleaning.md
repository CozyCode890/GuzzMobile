# Stage 7 — Làm sạch âm thanh đầy đủ

**Trạng thái:** 📝 đã plan chi tiết (2026-11-15) · **Cần xong trước:** 4

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
- UI: trang Âm thanh rút gọn (D89).

### Không làm

- Khử ồn bằng model AI (RNNoise / DeepFilterNet): không chọn ở D7, chỉ cân nhắc lại nếu port DSP không đạt.
- Ô số từng tham số của preset Tuỳ chỉnh, trang cắt đoạn và video đầy đủ: Stage 8 (D89).
- Chạy khử ồn nhiều luồng (D86), trừ khi D3 không đạt và người dùng chọn.

## Nguồn tham khảo trong Guzz desktop

- `audio_processing.py` (toàn bộ), `gui/presets.py`, `gui/pages/audio.py`
- `tests/test_audio_processing.py`, `tests/test_preset.py`
- README desktop §7 (preset Loa nhiều hướng và số đo)
- noisereduce 3.0.3 trong `D:\Guzz\runtime\python\Lib\site-packages\noisereduce` (`noisereduce.py`,
  `spectralgate/base.py`, `spectralgate/nonstationary.py`, `spectralgate/utils.py`), scipy 1.18.1 (`butter`, `sosfilt`,
  `sosfiltfilt`, `filtfilt`, `stft`, `istft`, `fftconvolve`, `maximum_filter1d`, `uniform_filter1d`)

## Kết quả bàn giao

- Làm sạch đầy đủ bật / tắt được, có preset.

## Tiêu chí hoàn thành

- [ ] Golden test từng bước: SNR so với Python ≥ 60 dB (D85), mốc cắt lặng / điểm cắt đoạn lệch ≤ 1 khối.
- [ ] Bài 1 giờ: làm sạch đủ thêm ≤ 10 phút so với Stage 2 trên Xiaomi 15T, màn hình tắt, một luồng; PSS ≤ 400 MB (D88).
- [ ] Nghe thử so với bản desktop trên cùng tệp: không nghe ra khác biệt (A/B, D90).

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-15, xem mục [Quyết định](#quyết-định): FFT → D83; hệ số Butterworth → D84; sai số → D85; nhiều
luồng → D86; preset mặc định → D87; hỏi thêm lúc plan: ngưỡng tốc độ → D88; giao diện → D89; nghe thử → D90.

## Quyết định

Hỏi người dùng ngày 2026-11-15, người dùng chọn phương án Recommended ở cả 8 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D83 | FFT cho STFT / fftconvolve | **Tự viết FFT Kotlin** (radix-2, độ dài lũy thừa 2; độ dài khác của fftconvolve thì đệm lên lũy thừa 2) | JVM thuần nên golden chạy trên PC; không thêm thư viện hay JNI. Đo tốc độ ở D3, chậm quá mới đổi. |
| D84 | Hệ số Butterworth | **Port `scipy.signal.butter`** (buttap → lp2bp_zpk / lp2lp_zpk → bilinear_zpk → zpk2sos) | Tần số nào cũng được (Tuỳ chỉnh); golden so hệ số với scipy. |
| D85 | Sai số so với Python | **SNR ≥ 60 dB** từng bước và cả chuỗi; mốc cắt lặng / điểm cắt đoạn lệch ≤ 1 khối như Stage 2 | Không nghe ra khác biệt. Bước nào không đạt thì ghi Nhật ký và hỏi người dùng. |
| D86 | Nhiều luồng CPU | **Một luồng** như desktop (`n_jobs=1`), đo rồi mới tính | Mát máy, ít pin. D3 không đạt D88 thì hỏi trước khi chia luồng. |
| D87 | Preset mặc định | **Như desktop: Giảng viên đi lại** (đúng `config.default.txt`: lọc 80–7000 Hz, khử ồn 0,8, cửa sổ ồn 1,5 s, cân bằng âm lượng 3 s, không ghim tiếng động) | Giữ `DesktopDefaultsTest`; cân bằng âm lượng hợp khi điện thoại nằm cố định trên bàn. |
| D88 | Ngưỡng tốc độ | Bài 1 giờ, Xiaomi 15T, màn hình tắt: **làm sạch đủ thêm ≤ 10 phút** so với chuỗi Stage 2; **PSS ≤ 400 MB** | Vẫn ngắn hơn thời gian chép lời. |
| D89 | Giao diện ở Stage 7 | **Trang Âm thanh rút gọn**: chọn preset (4 + Tuỳ chỉnh), bật / tắt từng bước (khử ồn, cân bằng âm lượng, ghim tiếng động, cắt lặng), lưu âm thanh sạch. Ô số từng tham số → Stage 8 | Đủ dùng; Stage 8 làm đủ các trang cài đặt. |
| D90 | Nghe thử | **Cặp tệp A/B, người dùng nghe**: `sach.flac` của điện thoại và desktop cho 2 bài, 3 đoạn 30 s giống nhau mỗi bài, đặt tên A/B ngẫu nhiên | Tiêu chí "không nghe ra khác biệt" cần tai người. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Thuật toán ở `:core:audio` (JVM, đã có chuẩn hoá, cắt lặng, cắt đoạn của Stage 2); gói con `dsp/` cho FFT, Butterworth,
  `sosfilt`, `filtfilt`, STFT. `:core:media` chỉ đổi chỗ gọi.
- Dòng chảy như Stage 2 (D43): mỗi bước đọc PCM tạm theo khối và ghi PCM tạm mới (float32, `tam_NN.f32`), xoá tệp của
  bước trước khi xong bước sau; `sach.pcm` vẫn là PCM 16-bit sau cùng. Không giữ cả bài trong một `FloatArray`.
  - bandpass: `sosfilt` có trạng thái `zi` qua các khối 2^20 mẫu, như desktop;
  - khử ồn: đúng cách chia khối của noisereduce (`chunk_size` 600 000, `padding` 30 000), nên đọc từng khối + đệm từ tệp;
  - ghim tiếng động: lượt 1 tính năng lượng khối 10 ms (mảng nhỏ), tính mức ghim trên mảng nhỏ đó, lượt 2 ghi lại; lát
    có đệm 1 024 mẫu cho `sosfiltfilt` như desktop;
  - cân bằng âm lượng: lượt 1 năng lượng khối 50 ms, tính đường gain trên mảng nhỏ, lượt 2 nhân gain.
- Kiểu số: float32 cho mẫu như desktop (`y` float32), float64 cho phép tính trung gian mà numpy làm bằng float64
  (`sosfilt` với `sos` float64 trả về float64 rồi gán lại float32, STFT của scipy chạy complex128 khi đầu vào float32?
  → kiểm kiểu thật ở B4 và ghi lại).
- Tham số mặc định của noisereduce 3.0.3 mà desktop không đổi: `n_fft` 1024, `win_length` = `n_fft`, `hop_length` = 256,
  `freq_mask_smooth_hz` 500, `time_mask_smooth_ms` 50, `thresh_n_mult_nonstationary` 2, `sigmoid_slope_nonstationary` 10.
- Golden: `tools/golden/gen_dsp.py` (FFT, butter, sosfilt / sosfiltfilt / filtfilt, stft / istft, fftconvolve) và
  `tools/golden/gen_cleaning.py` (từng bước + `clean_audio` trên tệp tổng hợp có tiếng nói, ồn nền, tiếng gõ, đi xa gần),
  chạy chính Python của desktop. Bản ghi thật B1–B5: mở rộng `tools/golden/real_golden.py` sang chuỗi đủ (ngoài repo, tự
  bỏ qua khi thiếu dữ liệu).
- Mã cache `audio_hash` thêm đúng các tham số desktop dùng (`denoise`, `noise_sample_sec`, `bandpass_low/high`,
  `prop_decrease`, `level_window_sec`; `transient_clamp_db` chỉ thêm khi > 0), golden trong `gen_pipeline.py`.
- Preset lưu bằng giá trị các mục như desktop (không có khoá "preset"); trang Âm thanh nhận ra preset bằng
  `detect_preset`.

## Rủi ro

- noisereduce có nhiều chi tiết ẩn (cách làm mượt mặt nạ, padding STFT) → đọc mã nguồn noisereduce 3.0.3 trong
  `D:\Guzz\runtime\python`, port theo đúng phiên bản đó.
- Chậm trên CPU yếu → cho phép tắt từng bước.
- FFT tự viết chậm hơn pocketfft nhiều lần → đo ở B1 trên PC và ở D3 trên máy; không đạt D88 thì hỏi (JTransforms, đa
  luồng).
- `sosfiltfilt` / `filtfilt` của scipy có quy tắc đệm riêng (`padtype`, `padlen`, trạng thái đầu `lfilter_zi`) → port
  đúng, golden từng hàm trước khi ráp.

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Port hàm nào thì KDoc ghi nguồn (`Port của
audio_processing.py: level_loudness`, `Port của noisereduce 3.0.3: SpectralGateNonStationary`). Test port từ desktop giữ
tên ca. Thử trên máy ảo `guzz_15t` trước (D70), Xiaomi 15T cho đo tốc độ (D3) và tệp nghe thử.

### A. Cài đặt, preset, mã cache

- [ ] **A1. Cài đặt:** `[AUDIO]` `denoise`, `noise_sample_sec`, `bandpass_low`, `bandpass_high`, `prop_decrease`,
  `level_window_sec`, `transient_clamp_db`; `[OUTPUT] save_clean_audio`. Chặn như `load_config`. `sample_rate` giữ cố
  định 16 kHz (Stage 2), ghi lý do.
  **Kiểm:** `DesktopDefaultsTest` xanh với mục mới (mặc định = Giảng viên đi lại, D87).
- [ ] **A2. Preset:** port `gui/presets.py` (`PRESETS`, `PRESET_FIELDS`, `values_from_config`, `detect_preset`), áp
  preset = ghi các mục cài đặt.
  **Kiểm:** port các ca của `tests/test_preset.py`; golden bảng preset so với desktop.
- [ ] **A3. Mã cache:** `CacheKeys.audioHash` thêm tham số làm sạch như `audio_hash` của desktop.
  **Kiểm:** golden trong `gen_pipeline.py` (có / không ghim tiếng động, video); test: đổi preset thì xử lý lại, không đổi
  thì dùng lại đoạn đã cắt.

### B. Nền tảng số (`:core:audio/dsp`, JVM)

- [ ] **B1. FFT** radix-2 thực / phức (D83), `rfft` / `irfft` như numpy.
  **Kiểm:** golden `gen_dsp.py` so với `numpy.fft` (sai số tương đối ≤ 1e-12 với double); bench 1 giờ âm thanh các
  phép STFT trên PC (`GUZZ_BENCH=1`), ghi số vào Nhật ký.
- [ ] **B2. Butterworth** (D84): `buttap`, `lp2bp_zpk`, `lp2lp_zpk`, `bilinear_zpk`, `zpk2sos` (cách ghép cặp cực / không
  điểm của scipy).
  **Kiểm:** golden hệ số `sos` so với `scipy.signal.butter(..., output="sos")` cho 4 preset và 20 bộ tham số ngẫu nhiên
  (bậc 4–5, band / low), sai lệch ≤ 1e-10.
- [ ] **B3. `sosfilt` (có `zi`), `sosfiltfilt`, `filtfilt`** (đúng `padtype`, `padlen`, `lfilter_zi` / `sosfilt_zi`).
  **Kiểm:** golden trên tín hiệu ngẫu nhiên và sin, nhiều độ dài (kể cả ngắn hơn `padlen`), sai lệch ≤ 1e-9.
- [ ] **B4. STFT / ISTFT** của scipy (cửa sổ hann, `padded=False`, `boundary`), `fftconvolve(mode="same")`.
  **Kiểm:** golden so với scipy trên khối 600 000 + đệm; ghi kiểu số thật của scipy (float32 / complex64 hay float64) vào
  "Mặc định kỹ thuật".

### C. Các bước làm sạch (`:core:audio`, JVM)

- [ ] **C1. `bandpass_filter`** dòng chảy theo khối 2^20 mẫu có `zi`.
  **Kiểm:** golden trên tệp tổng hợp (`gen_cleaning.py`), SNR ≥ 60 dB; đúng các nhánh bỏ qua (`low <= 0`, `low >= high`,
  `high` > Nyquist).
- [ ] **C2. `reduce_noise`** = noisereduce 3.0.3 non-stationary (khối 600 000, đệm 30 000, mặt nạ sigmoid, làm mượt bằng
  bộ lọc `_smoothing_filter`, `prop_decrease`, `time_constant_s` = max(0,2, `noise_sample_sec`)).
  **Kiểm:** golden SNR ≥ 60 dB trên tệp tổng hợp (ồn trắng + ồn hồng + giọng, dài hơn 2 khối để có biên khối); đo
  thời gian 1 giờ trên PC.
- [ ] **C3. `suppress_transients`** (hai lượt quanh khử ồn, `_merge_regions`, log "Đã ghim N tiếng động…").
  **Kiểm:** golden mức ghim từng khối (≤ 1e-6 dB), các vùng ghim và tín hiệu ra (SNR ≥ 60 dB) trên tệp có tiếng gõ / ho
  tổng hợp; port các ca liên quan của `tests/test_audio_processing.py`.
- [ ] **C4. `level_loudness`.**
  **Kiểm:** golden đường gain và tín hiệu ra (SNR ≥ 60 dB) trên tệp giọng xa gần; port ca của test desktop.
- [ ] **C5. Chuỗi `clean_audio`** trong `ChunkPreparer` theo đúng thứ tự desktop (bandpass → ghim → khử ồn → ghim →
  cân bằng → chuẩn hoá → cắt lặng), báo tiến độ `[1/6]…[6/6]`, `denoise = false` đi đúng nhánh desktop (giữ đường của
  Stage 2).
  **Kiểm:** golden `clean_audio` cả chuỗi trên tệp tổng hợp: SNR ≥ 60 dB, `ban_do_thoi_gian` và điểm cắt đoạn lệch ≤ 1
  khối; `RealRecordingGoldenTest` mở rộng (B1–B5, chuỗi đủ, preset mặc định) chạy (không bỏ qua) trên PC này; các golden
  cũ của Stage 2 (nhánh không khử ồn) vẫn xanh.

### D. Pipeline và trên máy

- [ ] **D1. Nối vào pipeline:** `AudioPreparation` / `ChunkPreparer` dùng chuỗi đủ theo cài đặt; tên bước trên thông báo
  ("lọc dải tần", "khử ồn"…); `save_clean_audio` → `<tên>.sach.flac` cạnh bản chép lời (qua `OutputFolder`).
  **Kiểm:** `.\gradlew :core:pipeline:test` xanh; test: bật lưu âm thanh sạch thì có `.sach.flac`.
- [ ] **D2. Máy ảo:** `audio_debug.ps1` và hàng đợi (Gemini giả) với `L1_20m.m4a`, 4 preset; `-PullPcm` rồi so với
  desktop bằng `compare_ffmpeg.py` / script so mới.
  **Kiểm:** SNR so với desktop ≥ 60 dB trên tệp thật; Dừng giữa lúc khử ồn → `dung`, chạy lại xử lý lại từ đầu bước âm
  thanh (hoặc dùng lại nếu có cache); `logcat -b crash` trống.
- [ ] **D3. Xiaomi 15T, bài 1 giờ** (`L1_60m` hoặc B4), màn hình tắt, preset mặc định: thời gian xử lý âm thanh so với
  Stage 2 trên cùng tệp, PSS đỉnh.
  **Kiểm:** bảng trong stage-01-results.md §3 (hoặc tệp kết quả Stage 7): thêm ≤ 10 phút, PSS ≤ 400 MB (D88). Không đạt
  thì ghi Nhật ký và hỏi người dùng (đa luồng, JTransforms, tắt bước).

### E. Giao diện

- [ ] **E1. Trang Âm thanh rút gọn (D89):** mở từ menu và dòng "Âm thanh: <preset>" ở màn hình Hàng đợi; chọn preset (4
  + Tuỳ chỉnh, nhận preset bằng `detect_preset`), bật / tắt khử ồn, cân bằng âm lượng, ghim tiếng động, cắt lặng; lưu âm
  thanh sạch. vi / en trong `strings.xml`. Công tắc "Cắt lặng" cũ ở cài đặt nhanh chuyển vào trang này.
  **Kiểm:** máy ảo: chọn từng preset, quay lại thấy giữ; tắt một bước thì preset thành "Tuỳ chỉnh"; `.\gradlew lint`
  xanh.

### F. Nghe thử (D90)

- [ ] **F1. Tệp A/B:** chạy cùng tệp gốc trên điện thoại (bản debug, `-PullPcm` / `.sach.flac`) và trên desktop (cùng
  preset) cho B4 và một bài loa nhiều hướng (preset Loa nhiều hướng); cắt 3 đoạn 30 s giống nhau mỗi bài, đặt tên A/B
  ngẫu nhiên, ghi khoá giải ở ngoài repo (`D:\GuzzMobileData\stage-07\nghe_thu\`).
  **Kiểm:** người dùng nghe và báo; không nghe ra khác biệt → tick tiêu chí; nghe ra thì ghi Nhật ký và hỏi.

### G. Đóng stage

- [ ] **G1. Tài liệu:** CLAUDE.md thêm lệnh (golden DSP / làm sạch, bench, so PCM); README §3–4 sửa nếu module khác dự
  kiến.
  **Kiểm:** chạy lại từng lệnh đã chép.
- [ ] **G2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 7 → ✅; "Việc tiếp theo" → stage kế (hỏi người dùng);
  commit, push.
  **Kiểm:** `git status -sb` báo `## main...origin/main`; `git grep -E "AIza[0-9A-Za-z_-]{30,}"` không ra gì; repo không
  có bản ghi thật.

## Nhật ký

- **2026-11-15, plan.** Đọc `audio_processing.py` (toàn bộ), `gui/presets.py`, mục `[AUDIO]` của `config.default.txt`
  (trùng preset Giảng viên đi lại), noisereduce 3.0.3 (`noisereduce.py`, `spectralgate/base.py`, `nonstationary.py`,
  `utils.py`). Hỏi người dùng 8 câu trắc nghiệm, cả 8 chọn Recommended (D83–D90).

## Phát sinh
