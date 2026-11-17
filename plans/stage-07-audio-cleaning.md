# Stage 7 — Làm sạch âm thanh đầy đủ

**Trạng thái:** ✅ xong 2026-11-17 (plan chi tiết 2026-11-15) · **Cần xong trước:** 4

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

- [x] Golden test từng bước: SNR so với Python ≥ 60 dB (D85), mốc cắt lặng / điểm cắt đoạn lệch ≤ 1 khối.
- [x] Bài 1 giờ: làm sạch đủ thêm ≤ 10 phút so với Stage 2 trên Xiaomi 15T, màn hình tắt, một luồng; PSS ≤ 400 MB (D88).
- [x] Nghe thử so với bản desktop trên cùng tệp: không nghe ra khác biệt (A/B, D90). Người dùng nghe ra khác ở B4, không
  ở B3; đo thì mỗi cặp chỉ lệch 0–1 ms, khác nhau ≤ −86 dB sau khi dóng → người dùng chọn chấp nhận theo số đo (Nhật ký).

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
  (`sosfilt` với `sos` float64 trả về float64 rồi gán lại float32). Đã kiểm ở B4: noisereduce chép mỗi khối vào
  `np.zeros` float64 (`_read_chunk`), nên STFT chạy **complex128**, ISTFT ra **float64**, rồi ép float32 khi ghi khối;
  bản Kotlin tính double / phức double rồi `toFloat()` ở cùng chỗ.
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

- [x] **A1. Cài đặt:** `[AUDIO]` `denoise`, `noise_sample_sec`, `bandpass_low`, `bandpass_high`, `prop_decrease`,
  `level_window_sec`, `transient_clamp_db`; `[OUTPUT] save_clean_audio`. Chặn như `load_config`. `sample_rate` giữ cố
  định 16 kHz (Stage 2), ghi lý do.
  **Kiểm:** `DesktopDefaultsTest` xanh với mục mới (mặc định = Giảng viên đi lại, D87).
- [x] **A2. Preset:** port `gui/presets.py` (`PRESETS`, `PRESET_FIELDS`, `values_from_config`, `detect_preset`), áp
  preset = ghi các mục cài đặt.
  **Kiểm:** port các ca của `tests/test_preset.py`; golden bảng preset so với desktop.
- [x] **A3. Mã cache:** `CacheKeys.audioHash` thêm tham số làm sạch như `audio_hash` của desktop.
  **Kiểm:** golden trong `gen_pipeline.py` (có / không ghim tiếng động, video); test: đổi preset thì xử lý lại, không đổi
  thì dùng lại đoạn đã cắt.

### B. Nền tảng số (`:core:audio/dsp`, JVM)

- [x] **B1. FFT** radix-2 thực / phức (D83), `rfft` / `irfft` như numpy.
  **Kiểm:** golden `gen_dsp.py` so với `numpy.fft` (sai số tương đối ≤ 1e-12 với double); bench 1 giờ âm thanh các
  phép STFT trên PC (`GUZZ_BENCH=1`), ghi số vào Nhật ký.
- [x] **B2. Butterworth** (D84): `buttap`, `lp2bp_zpk`, `lp2lp_zpk`, `bilinear_zpk`, `zpk2sos` (cách ghép cặp cực / không
  điểm của scipy).
  **Kiểm:** golden hệ số `sos` so với `scipy.signal.butter(..., output="sos")` cho 4 preset và 20 bộ tham số ngẫu nhiên
  (bậc 4–5, band / low), sai lệch ≤ 1e-10.
- [x] **B3. `sosfilt` (có `zi`), `sosfiltfilt`, `filtfilt`** (đúng `padtype`, `padlen`, `lfilter_zi` / `sosfilt_zi`).
  **Kiểm:** golden trên tín hiệu ngẫu nhiên và sin, nhiều độ dài (kể cả ngắn hơn `padlen`), sai lệch ≤ 1e-9.
- [x] **B4. STFT / ISTFT** của scipy (cửa sổ hann, `padded=False`, `boundary`), `fftconvolve(mode="same")`.
  **Kiểm:** golden so với scipy trên khối 600 000 + đệm; ghi kiểu số thật của scipy (float32 / complex64 hay float64) vào
  "Mặc định kỹ thuật".

### C. Các bước làm sạch (`:core:audio`, JVM)

- [x] **C1. `bandpass_filter`** dòng chảy theo khối 2^20 mẫu có `zi`.
  **Kiểm:** golden trên tệp tổng hợp (`gen_cleaning.py`), SNR ≥ 60 dB; đúng các nhánh bỏ qua (`low <= 0`, `low >= high`,
  `high` > Nyquist).
- [x] **C2. `reduce_noise`** = noisereduce 3.0.3 non-stationary (khối 600 000, đệm 30 000, mặt nạ sigmoid, làm mượt bằng
  bộ lọc `_smoothing_filter`, `prop_decrease`, `time_constant_s` = max(0,2, `noise_sample_sec`)).
  **Kiểm:** golden SNR ≥ 60 dB trên tệp tổng hợp (ồn trắng + ồn hồng + giọng, dài hơn 2 khối để có biên khối); đo
  thời gian 1 giờ trên PC.
- [x] **C3. `suppress_transients`** (hai lượt quanh khử ồn, `_merge_regions`, log "Đã ghim N tiếng động…").
  **Kiểm:** golden mức ghim từng khối (≤ 1e-6 dB), các vùng ghim và tín hiệu ra (SNR ≥ 60 dB) trên tệp có tiếng gõ / ho
  tổng hợp; port các ca liên quan của `tests/test_audio_processing.py`.
- [x] **C4. `level_loudness`.**
  **Kiểm:** golden đường gain và tín hiệu ra (SNR ≥ 60 dB) trên tệp giọng xa gần; port ca của test desktop.
- [x] **C5. Chuỗi `clean_audio`** trong `ChunkPreparer` theo đúng thứ tự desktop (bandpass → ghim → khử ồn → ghim →
  cân bằng → chuẩn hoá → cắt lặng), báo tiến độ `[1/6]…[6/6]`, `denoise = false` đi đúng nhánh desktop (giữ đường của
  Stage 2).
  **Kiểm:** golden `clean_audio` cả chuỗi trên tệp tổng hợp: SNR ≥ 60 dB, `ban_do_thoi_gian` và điểm cắt đoạn lệch ≤ 1
  khối; `RealRecordingGoldenTest` mở rộng (B1–B5, chuỗi đủ, preset mặc định) chạy (không bỏ qua) trên PC này; các golden
  cũ của Stage 2 (nhánh không khử ồn) vẫn xanh.

### D. Pipeline và trên máy

- [x] **D1. Nối vào pipeline:** `AudioPreparation` / `ChunkPreparer` dùng chuỗi đủ theo cài đặt; tên bước trên thông báo
  ("lọc dải tần", "khử ồn"…); `save_clean_audio` → `<tên>.sach.flac` cạnh bản chép lời (qua `OutputFolder`).
  **Kiểm:** `.\gradlew :core:pipeline:test` xanh; test: bật lưu âm thanh sạch thì có `.sach.flac`.
- [x] **D2. Máy ảo:** `audio_debug.ps1` và hàng đợi (Gemini giả) với `L1_20m.m4a`, 4 preset; `-PullPcm` rồi so với
  desktop bằng `compare_ffmpeg.py` / script so mới.
  **Kiểm:** SNR so với desktop ≥ 60 dB trên tệp thật; Dừng giữa lúc khử ồn → `dung`, chạy lại xử lý lại từ đầu bước âm
  thanh (hoặc dùng lại nếu có cache); `logcat -b crash` trống.
- [x] **D3. Xiaomi 15T, bài 1 giờ** (`L1_60m` hoặc B4), màn hình tắt, preset mặc định: thời gian xử lý âm thanh so với
  Stage 2 trên cùng tệp, PSS đỉnh.
  **Kiểm:** bảng trong stage-01-results.md §3 (hoặc tệp kết quả Stage 7): thêm ≤ 10 phút, PSS ≤ 400 MB (D88). Không đạt
  thì ghi Nhật ký và hỏi người dùng (đa luồng, JTransforms, tắt bước).

### E. Giao diện

- [x] **E1. Trang Âm thanh rút gọn (D89):** mở từ menu và dòng "Âm thanh: <preset>" ở màn hình Hàng đợi; chọn preset (4
  + Tuỳ chỉnh, nhận preset bằng `detect_preset`), bật / tắt khử ồn, cân bằng âm lượng, ghim tiếng động, cắt lặng; lưu âm
  thanh sạch. vi / en trong `strings.xml`. Công tắc "Cắt lặng" cũ ở cài đặt nhanh chuyển vào trang này.
  **Kiểm:** máy ảo: chọn từng preset, quay lại thấy giữ; tắt một bước thì preset thành "Tuỳ chỉnh"; `.\gradlew lint`
  xanh.

### F. Nghe thử (D90)

- [x] **F1. Tệp A/B:** chạy cùng tệp gốc trên điện thoại (bản debug, `-PullPcm` / `.sach.flac`) và trên desktop (cùng
  preset) cho B4 và một bài loa nhiều hướng (preset Loa nhiều hướng); cắt 3 đoạn 30 s giống nhau mỗi bài, đặt tên A/B
  ngẫu nhiên, ghi khoá giải ở ngoài repo (`D:\GuzzMobileData\stage-07\nghe_thu\`).
  **Kiểm:** người dùng nghe và báo; không nghe ra khác biệt → tick tiêu chí; nghe ra thì ghi Nhật ký và hỏi.

### G. Đóng stage

- [x] **G1. Tài liệu:** CLAUDE.md thêm lệnh (golden DSP / làm sạch, bench, so PCM); README §3–4 sửa nếu module khác dự
  kiến.
  **Kiểm:** chạy lại từng lệnh đã chép.
- [x] **G2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 7 → ✅; "Việc tiếp theo" → stage kế (hỏi người dùng);
  commit, push.
  **Kiểm:** `git status -sb` báo `## main...origin/main`; `git grep -E "AIza[0-9A-Za-z_-]{30,}"` không ra gì; repo không
  có bản ghi thật.

## Nhật ký

- **2026-11-15, plan.** Đọc `audio_processing.py` (toàn bộ), `gui/presets.py`, mục `[AUDIO]` của `config.default.txt`
  (trùng preset Giảng viên đi lại), noisereduce 3.0.3 (`noisereduce.py`, `spectralgate/base.py`, `nonstationary.py`,
  `utils.py`). Hỏi người dùng 8 câu trắc nghiệm, cả 8 chọn Recommended (D83–D90).

- **2026-11-17, A–D1.** Session tách từ session đang điều tra lỗi mốc thời gian (chung thư mục).
  - A: 8 mục cài đặt mới (`SettingKeys`), `AudioPresets` (port presets.py) + golden `config/presets.json` (thêm vào
    `gen_config_keys.py`), `CacheKeys.audioHash` đúng thứ tự `audio_hash` của desktop (golden `gen_pipeline.py` nay gọi
    thẳng `transcribe.audio_hash`). Mã cache của mobile đổi so với Stage 2–5: bài làm dở từ trước sẽ xử lý âm thanh lại.
    Chặn thêm so với desktop: `prop_decrease` trong [0, 1], `noise_sample_sec` / `level_window_sec` ≥ 0.
  - B: gói `:core:audio/dsp` (`RealFft`, `Butterworth`, `IirFilter`, `Stft`, `Convolve`, `Np`, `NoiseReducer`). Golden
    `gen_dsp.py` → `testdata/golden/dsp/dsp.json` (1,2 MB, mảng base64 float64). Đạt: rfft / irfft ≤ 1e-12, hệ số
    butter ≤ 1e-10 (4 preset, lọc thấp của ghìm tiếng động, 20 bộ ngẫu nhiên, bậc 1–8), sosfilt / sosfiltfilt ≤ 1e-9
    (sosfilt theo khối gán float32 lệch ≤ 1 ulp), filtfilt bậc một ≤ 1e-12, stft / istft ≤ 1e-12 (khối 660 000 mẫu ≤ 1e-11).
    scipy tự thu `nperseg` khi đầu vào ngắn hơn 1 024 mẫu: noisereduce không gặp (mỗi khối ≥ 60 000 mẫu) nên không port.
  - **Làm khác D83:** không dùng FFT cho `fftconvolve` của mặt nạ. Nhân `_smoothing_filter` (33 × 7) là tích ngoài của
    hai vectơ nên chập lần lượt theo trục tần số rồi thời gian (`Convolve.same1d`, 40 phép nhân mỗi ô) — bằng
    `fftconvolve(mode="same")` tới 1e-12 (golden), nhanh hơn và không cần mảng 1 024 × 4 096 mỗi khối.
  - C: `Bandpass`, `TransientSuppressor`, `NoiseReducer`, `LevelLoudness`, `AudioCleaner` (thứ tự `clean_audio`, tệp tạm
    `tam_NN.f32` qua `F32Writer` / `F32File`, xoá tệp bước trước). Golden `gen_cleaning.py` → `testdata/golden/cleaning/`
    (3,8 MB: tín hiệu tổng hợp 10 s 16 kHz có gõ bàn / ho / đi xa gần, kết quả float32 thô từng bước, 3 chuỗi đủ PCM
    16-bit). Kết quả: bandpass, ghìm tiếng động (mức ghìm từng khối, vùng ghìm), khử ồn (3 khối 60 000 + đệm 15 000)
    **trùng từng bit** với Python; level_loudness 145 dB; cả chuỗi (sach.pcm int16) 100 / 105 / 126 dB, bản đồ thời gian
    và điểm cắt trùng. Golden khử ồn dùng khối 60 000 / đệm 15 000 để có biên khối trong 10 s (desktop 600 000 / 30 000;
    chuỗi đủ chạy đúng mặc định, nhánh một khối); khối mặc định trên bài dài: golden bản ghi thật B1–B5 (C5).
  - `ChunkPreparer.prepare` nhận `CleanSettings` (mặc định `OFF` = Stage 2); `PrepareStep` thêm BANDPASS, TRANSIENTS,
    DENOISE, LEVEL ở đầu. Nhật ký làm sạch (vd "Đã ghìm 4 tiếng động…") đi qua `SourceListener.log` vào log của pipeline.
  - D1: `ChunkSource.prepare` nhận `clean`, `saveClean`; `AudioPreparation` ghi `sach.flac` (FLAC của `sach.pcm`) khi
    bật lưu; `FilePipeline` chép ra `<tên>.sach.flac` bằng `OutputFolder.copy` (SAF chép theo dòng, không đọc cả tệp vào
    RAM) và coi cache thiếu `sach.flac` là phải xử lý lại (như `need_clean`). Màn hình thử âm thanh (debug) dùng cài đặt
    làm sạch của app, ghi `lam_sach` và `nhat_ky_lam_sach` vào `ket_qua.json`.
  - E1 (đang làm): trang Âm thanh (`AudioActivity`), dòng "Âm thanh: <preset>" thay công tắc Cắt lặng ở màn hình Hàng
    đợi. Thêm so với desktop: tắt khử ồn hay cắt lặng thì hiện "Cấu hình thủ công" (desktop `detect_preset` không xét hai
    mục này), và chọn một preset thì bật lại khử ồn + cắt lặng, để "tắt một bước thì thành Tuỳ chỉnh" đúng với mọi công tắc.
  - C5 trên bản ghi thật (`real_golden.py --stage7`, preset mặc định, khối noisereduce mặc định): B1–B5 SNR 91,5–94,5 dB
    (tín hiệu đã làm sạch + chuẩn hoá, int16, so với desktop), mốc TimeMap khớp 100%, điểm cắt lệch ≤ 2 ms. Tốc độ trên
    PC (một luồng): làm sạch đủ bài 79 phút (B4) 15,9 s, bài 115 phút 21–23 s; Python của desktop 24–55 s. Heap đỉnh
    ≤ 188 MB.
  - D2 trên máy ảo `guzz_15t`, `L1_20m.m4a`, 4 preset (`compare_clean.py`): SNR 91,8 / 92,9 / 92,4 / 93,0 dB, TimeMap và
    điểm cắt trùng; khử ồn 20 phút ≈ 29–35 s, ghìm tiếng động hai lượt 5 s ("Đã ghìm 50 tiếng động", trùng desktop), PSS
    đỉnh 169–172 MB. Hàng đợi (Gemini giả): `.sach.flac` ra cạnh bản chép lời; Dừng lúc "khử ồn" → `dung`, không còn
    `tam_*`; chạy lại xử lý lại từ đầu bước âm thanh rồi xong; `logcat -b crash` trống. E1 thử trên máy ảo: chọn từng
    preset, tắt khử ồn / ghìm thì thành "Cấu hình thủ công", mở lại trang vẫn giữ.
  - Công cụ mới: `queue_debug.ps1 -Preset/-Denoise/-SaveClean` (lệnh adb `audio`), `audio_debug.ps1 -PullClean`,
    `tools/device/compare_clean.py` (chạy desktop trên đúng `goc.pcm` của máy rồi so), `tools/device/make_ab.py` (F1).
  - F1: người dùng chọn **B3** cho preset Loa nhiều hướng (cùng B4 với preset mặc định). B3 trên máy là `L2.m4a` (cùng
    tệp gốc).
- **2026-11-17, D3 + F1 trên Xiaomi 15T** (USB; dùng thêm `android` CLI 1.1 cho máy ảo / màn hình). Màn hình thử âm
  thanh, `-ScreenOff`, một luồng; kết quả ở `D:\GuzzMobileData\stage-07\d3\`:

  | Bài | Đường | Tổng (s) | Giải mã (s) | Sau giải mã (s) | Lọc / ghìm / khử ồn / cân bằng (s) | PSS đỉnh | So với desktop |
  | --- | --- | --- | --- | --- | --- | --- | --- |
  | B4 79 phút | Stage 2 (khử ồn tắt) | 232,6 | 192,0 | 40,6 | — | 136 MB | — |
  | B4 79 phút | Stage 7, preset mặc định | 439,7 | 337,6 | 102,1 | 9,8 / — / 53,2 / 4,0 | 200 MB | 91,6 dB, TimeMap 100%, 5 / 5 đoạn |
  | B3 (L2) 115 phút | Stage 7, Hội trường đông người | 564,6 | 388,6 | 176,0 | 14,6 / 11,2 / 105,7 / — | 231 MB | 91,8 dB, TimeMap 100%, 9 / 9 đoạn |

  Lượt Stage 7 của B4 đo lại vì lần đầu màn hình bật lúc kết thúc; lần đo lại `dumpsys power` báo `Dozing` suốt lúc chạy.
  Thêm so với Stage 2: 207 s tổng, trong đó giải mã chậm hơn 146 s (biến động của bộ giải mã khi máy ngủ, không phải
  Stage 7), phần sau giải mã thêm 62 s → **đạt D88** (≤ 10 phút, PSS ≤ 400 MB). Không cần đa luồng (D86).
  - `compare_clean.py` so theo từng khoảng giữ lại trùng nhau: một mốc cắt lặng lệch 1 ms (trong dung sai B9) làm phần
    sau trượt vài mẫu, so "nửa đầu" như bản trước ra −2 dB vô nghĩa. Nay so trên 99,7–99,8% mẫu.
  - F1: `make_ab.py` → `D:\GuzzMobileData\stage-07\nghe_thu\B4\` và `...\L2\` (3 đoạn 30 s ở 20 / 50 / 80% mỗi bài,
    A/B ngẫu nhiên, khoá giải `khoa_giai.json`).
- **2026-11-17, F1 + đóng stage.** Người dùng nghe: B4 nghe ra khác biệt, L2 (B3) không. Đo lại chính các tệp: hai tệp
  mỗi cặp cùng độ to; đoạn 1–2 của B4 SNR 86 / 92 dB không lệch thời gian; đoạn 3 của B4 và cả 3 đoạn của L2 lệch 16–32
  mẫu (1–2 ms, do một mốc cắt lặng lệch 1 ms ở trước đó), dóng lại thì 89–92 dB. Khác biệt đo được nằm dưới ngưỡng nghe
  rất xa và L2 có cùng kiểu lệch mà không nghe ra. Hỏi người dùng (ABX mù / chấp nhận theo số đo / mô tả chỗ khác):
  **chọn chấp nhận theo số đo**; cảm nhận khác ở B4 chưa lặp lại được. G1: chạy lại `gen_dsp.py`, `gen_cleaning.py`,
  `gen_config_keys.py`, `gen_pipeline.py` → `testdata/golden` không đổi; các lệnh máy thật đã chạy ở D2–D3; CLAUDE.md
  thêm mục `android` CLI. Stage 7 ✅, tiếp theo Stage 6.

## Phát sinh
