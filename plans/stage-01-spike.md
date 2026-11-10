# Stage 1 — Spike kỹ thuật

**Trạng thái:** ✅ 2026-11-10, đã rút gọn (D39–D42): phần chưa đo chuyển sang Stage 2, 4, 5, 6 · **Cần xong trước:** 0

## Mục tiêu

Lấy số đo trên máy thật cho những câu hỏi có thể làm đổ kế hoạch, **trước khi** viết code thật. Code spike để trong
`spikes/` (D32); không module nào của app phụ thuộc vào nó.

## Phạm vi

### Làm

1. **Tách giọng — độ chính xác (đo trên PC cho nhanh).** Chạy sherpa-onnx (gói pip `sherpa-onnx`, bản Python) trên 5 bản
   ghi lớp học thật mà desktop đã có kết quả pyannote community-1 (D28). So số người nói, DER và thời gian của người nói
   chính, sau khi cả hai chạy qua bước làm mượt của `speaker.py` (ngưỡng ở D29). Thử 5 model embedding (D33), vài ngưỡng
   phân cụm, vài giá trị `windowShiftRatio`; thử trên âm thanh gốc và trên âm thanh đã làm sạch; thử chia phần rồi nối
   người nói (D34).
2. **Tách giọng — tốc độ và RAM trên điện thoại.** Dùng app spike tự viết (D31), chạy bài 10 phút, 1 giờ và 2 giờ. Đo thời
   gian, RAM đỉnh, máy nóng tới đâu, tốn bao nhiêu pin (ngưỡng ở D30).
3. **Giải mã.** MediaExtractor + MediaCodec giải mã m4a / aac / mp3 / opus / mp4 dài 1–2 giờ ra PCM 16 kHz mono: mất bao
   lâu; so hai resampler (D38).
4. **Định dạng đoạn gửi Gemini.** Máy có những bộ mã hoá nào. Với FLAC / OGG-Opus / M4A-AAC / WAV (D36): một đoạn 10 phút
   nặng bao nhiêu MB, mã hoá mất bao lâu. Gửi thử lên Gemini xem bản chép lời có kém đi không (D37).
5. **Intent → việc chạy nền.** MacroDroid gửi intent lúc màn hình tắt và Guzz không mở, thử từng cách:
   (a) broadcast, Guzz đã được miễn tối ưu pin;
   (b) activity trong suốt (MacroDroid có quyền hiện trên ứng dụng khác);
   (c) WorkManager expedited.
   Ghi lại cách nào khởi động được foreground service trên bản Android của máy thử (tiêu chí D35).
6. **Giấy phép.** Kiểm lại giấy phép của từng tệp model và thư viện định đóng gói.

### Không làm

- Code cho app thật. Stage 2 và 5 viết lại code thật, đọc code spike để tham khảo. Không module nào của app phụ thuộc
  vào `spikes/`.

## Nguồn tham khảo trong Guzz desktop

- `diarization_worker.py`: định dạng JSON kết quả mà desktop dùng (`doan`: `bat_dau`, `ket_thuc`, `nguoi_noi`).
- `speaker.py`: `load_speaker_turns`, `process_turns`, `smooth`, `merge_turns`, `remap_timestamps`, `find_main_speaker`.
- `config.py`: `Config()` (mặc định các mục `[SPEAKERS]`), `load_config`.
- `audio_processing.py`: `TimeMap` (đổi mốc tệp gốc → âm thanh đã cắt lặng).
- `transcribe.py`: `diarize_speakers` (desktop chạy pyannote trên âm thanh gốc rồi đổi mốc thế nào).
- `google_ai.py`: `Client`, `get_api_key`, `_MIME` (đuôi tệp → MIME).
- `data\tmp\<bài>\`: `sach.flac`, `nguoi_noi.<mã>.json`, `danh_sach_doan.json` (`nguon`, `thoi_luong_goc`,
  `ban_do_thoi_gian`).
- `runtime\nguoi_noi\python.exe`: có sẵn pyannote.audio 4.0.7, pyannote.metrics, numpy, scipy, soundfile.
- `runtime\ffmpeg\ffmpeg.exe`: cắt, giải mã tham chiếu, kiểm tệp. Không có ffprobe; đo thời lượng bằng `ffmpeg -i`.

## Kết quả bàn giao

- [stage-01-results.md](stage-01-results.md): bảng số đo, model embedding và ngưỡng mặc định đã chọn, resampler, định
  dạng đoạn, cơ chế intent dùng được, giấy phép.
- Quyết định **đi tiếp / đổi hướng** cho sherpa-onnx. Nếu đổi hướng: tự ghép VAD + embedding trên ONNX Runtime, hoặc thu
  hẹp phạm vi tách giọng.
- Bảng quyết định trong [README §2](README.md#2-quyết-định-đã-chốt) đã cập nhật.
- `spikes/` (giữ lại, D32): script đo trên PC và app spike, đủ để đo lại trên máy khác.

## Tiêu chí hoàn thành

Rút gọn ngày 2026-11-10 (D39–D42): mục nào chưa đo xong thì có kết luận tạm và tên stage nhận phần còn lại.

- [x] Cả 6 mục trên đều có số đo hoặc kết luận trên máy thật, hoặc đã ghi stage nhận phần còn lại.
- [x] Đã chọn được mặc định cho các stage 2, 5, 6 (ghi ở "Phát sinh" của từng stage).
- [x] Quyết định đi tiếp / đổi hướng cho sherpa-onnx đã ghi vào README §2 (D39).

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-01, xem mục [Quyết định](#quyết-định). Ba câu có sẵn: bản ghi để đo → D28; ngưỡng chấp nhận → D29
(độ chính xác) và D30 (tốc độ, RAM); app đo tốc độ → D31. Hỏi thêm lúc plan: chỗ để code spike → D32; model embedding →
D33; bài dài quá heap → D34; tiêu chí chạy nền → D35; định dạng đoạn → D36; thử Gemini → D37; resampler → D38.

## Thông tin bên ngoài (kiểm 2026-11-01)

**sherpa-onnx**

- Bản mới nhất là **v2.0.1**, ra ngày 2026-10-26; v2.0.0 ra ngày 2026-10-23. Release notes không có thay đổi phá API tách
  giọng. Nguồn: `gh api repos/k2-fsa/sherpa-onnx/releases`.
- Android: `sherpa-onnx-2.0.1.aar` (49,4 MB, đủ mọi ABI, gồm `libsherpa-onnx-jni.so` và `libonnxruntime.so`), SHA-256
  `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`. Có thêm bản `sherpa-onnx-static-link-onnxruntime-2.0.1.aar`
  (38,6 MB, SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`). Hai bản này không có trên Maven,
  phải tải từ GitHub Releases. Bản dựng sẵn cho Android tắt C API (`SHERPA_ONNX_ENABLE_C_API=OFF` trong
  `build-android-arm64-v8a.sh`), chỉ có JNI.
- Python: `sherpa-onnx==2.0.1` kéo theo `sherpa-onnx-core==2.0.1` (15,9 MB), có wheel `win_amd64` từ cp37 tới cp315,
  Apache-2.0. API Python có `window_shift_ratio`.
- Kotlin API `OfflineSpeakerDiarization` (v2.0.1) có `process(samples: FloatArray)` và
  `processWithCallback(samples, callback(numProcessedChunks, numTotalChunks, arg) -> Int)`. Cả hai đều **nhận cả bài
  trong một `FloatArray`**.
  - Cấu hình: `OfflineSpeakerSegmentationPyannoteModelConfig(model, windowShiftRatio = 0.1)`, `numThreads`, `provider`;
    `FastClusteringConfig(numClusters = -1, threshold = 0.5, computeConfidence = false)`; `minDurationOn = 0.2`,
    `minDurationOff = 0.5`.
  - Nạp model từ assets hoặc từ đường dẫn tệp.
  - Mỗi đoạn kết quả có `start`, `end`, `speaker` (số, đếm từ 0) và `confidence`.
- Thuật toán, đọc từ `offline-speaker-diarization-pyannote-impl.h` và `fast-clustering.cc` của v2.0.1:
  - segmentation chạy theo cửa sổ của model; cửa sổ sau dịch đi `windowShiftRatio` × độ dài cửa sổ;
  - mỗi cặp (cửa sổ, người nói cục bộ) cho một embedding;
  - phân cụm **complete-linkage** (fastcluster) trên ma trận khoảng cách cosine **đầy đủ, kiểu double**, cỡ
    n(n−1)/2 × 8 byte, với n là số embedding. Ví dụ n ≈ 9 000 thì ma trận ≈ 320 MB;
  - giá trị trả về của callback **bị bỏ qua**, tức không dừng được giữa chừng bằng callback.
- Model (release `speaker-segmentation-models` và `speaker-recongition-models`):

  | Model | Tệp | Cỡ | Giấy phép (nguồn) |
  | --- | --- | --- | --- |
  | pyannote segmentation-3.0 | `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` (`model.onnx` 5,7 MB, `model.int8.onnx` 1,5 MB) | 7,0 MB | MIT (huggingface.co/pyannote/segmentation-3.0, gated) |
  | reverb-diarization v1 / v2 | — | — | **phi thương mại** → loại |
  | 3D-Speaker CAM++ zh-cn common | `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 28,3 MB | Apache-2.0 (ModelScope `iic/speech_campplus_sv_zh-cn_16k-common`) |
  | 3D-Speaker CAM++ zh_en advanced | `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` | 28,3 MB | Apache-2.0 (ModelScope `iic/speech_campplus_sv_zh_en_16k-common_advanced`) |
  | 3D-Speaker ERes2Net base 200k | `3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx` | 39,6 MB | Apache-2.0 (ModelScope `iic/speech_eres2net_base_200k_sv_zh-cn_16k-common`) |
  | NeMo TitaNet small | `nemo_en_titanet_small.onnx` | 40,3 MB | **chưa xác nhận**: bản large trên HF là CC-BY-4.0; bản small chỉ có trên NGC → kiểm ở H1 |
  | WeSpeaker ResNet34-LM (VoxCeleb) | `wespeaker_en_voxceleb_resnet34_LM.onnx` | 26,5 MB | CC-BY-4.0, theo giấy phép dataset VoxCeleb (`wespeaker/docs/pretrained.md`; HF `Wespeaker/wespeaker-voxceleb-resnet34-LM`) |

  Tài liệu sherpa-onnx (k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html) đo RTF với 1 luồng trên máy tác giả:
  segmentation-3.0 + 3D-Speaker 0,297 (int8 0,241); + TitaNet small 0,119 (int8 0,110).
- pyannote community-1 của desktop gồm segmentation powerset, embedding WeSpeaker và phân cụm VBx; giấy phép CC-BY-4.0
  (huggingface.co/pyannote/speaker-diarization-community-1).

**Foreground service và chạy nền** (developer.android.com, các trang cập nhật 2026-10-28 và 2026-10-31)

- Android 12+: app đang ở nền không khởi động được FGS, trừ các ngoại lệ. Những ngoại lệ liên quan tới Guzz:
  - chuyển từ trạng thái người dùng nhìn thấy (activity);
  - app được phép mở activity từ nền;
  - người dùng tắt tối ưu pin cho app;
  - app giữ `SYSTEM_ALERT_WINDOW`; nếu target 35+ thì còn phải **đang có cửa sổ overlay hiện**;
  - người dùng thao tác trên thông báo hoặc widget;
  - exact alarm cho việc người dùng yêu cầu.

  Broadcast thường **không** phải ngoại lệ. Trang này không nhắc tới expedited job.
  (`/develop/background-work/services/fgs/restrictions-bg-start`)
- `dataSync` (quyền `FOREGROUND_SERVICE_DATA_SYNC`) và `mediaProcessing` (quyền `FOREGROUND_SERVICE_MEDIA_PROCESSING`, có
  từ API 35). (`/fgs/timeout`, `/fgs/service-types`)
  - App target 35+ chạy trên Android 15+ bị giới hạn **6 giờ / 24 giờ cho mỗi loại, đếm riêng từng loại**; mọi service
    cùng loại dùng chung hạn mức đó.
  - Hết giờ thì hệ thống gọi `Service.onTimeout(int, int)`. Vài giây sau mà service chưa `stopSelf()` thì báo lỗi "A
    foreground service of type … did not stop within its timeout".
  - Đồng hồ đặt lại khi người dùng đưa app lên trước.
  - Target 35+ không được khởi động hai loại này từ `BOOT_COMPLETED`.
  - Thử nhanh: `adb shell device_config put activity_manager media_processing_fgs_timeout_duration <ms>`.
- Android 16 (mọi app): job, kể cả expedited job và WorkManager, bị tính vào runtime quota trong hai trường hợp: chạy **song
  song với FGS**, hoặc bắt đầu lúc app đang hiện rồi chạy tiếp ở nền. Worker chạy dài dùng FGS có thể ăn hết quota job.
  (`/about/versions/16/behavior-changes-all`, `/background-tasks/persistent/how-to/long-running`)
- WorkManager expedited dành cho việc ngắn "vài phút"; quota tính theo standby bucket. Từ Android 12, `setForeground()` có
  thể ném lỗi khi việc khởi động bị hạn chế. (`/persistent/getting-started/define-work#expedited`)
- Android 17 (API 37, cũng là targetSdk của dự án) có hai thay đổi: siết âm thanh nền (phát nhạc, audio focus; Guzz không
  dùng) và mở rộng hạn chế BAL sang `IntentSender`. Không có thay đổi mới cho FGS `dataSync` / `mediaProcessing`, cho
  broadcast hay cho job. (`/about/versions/17/behavior-changes-17`, `/about/versions/17/behavior-changes-all`)

**Gemini** (ai.google.dev/gemini-api/docs/audio, cập nhật 2026-09-23)

- Nhận `audio/wav`, `mp3`, `aiff`, `aac`, `ogg`, `flac`, `mpeg`, `m4a`, `l16`, `opus`, `alaw`, `mulaw`, `webm`.
- Âm thanh bị hạ xuống 16 kbps và trộn về mono; 32 token / giây; tối đa 9,5 giờ trong một prompt.
- `_MIME` của desktop chỉ có `.flac .wav .mp3 .ogg .m4a .aac`, nên tệp Opus đặt đuôi `.ogg`.

**Máy thử** (đọc bằng adb ngày 2026-11-01): Xiaomi 15T, API 36. `dalvik.vm.heapgrowthlimit = 256m`; `dalvik.vm.heapsize =
512m` là heap Java tối đa khi bật `largeHeap`. Đã cài MacroDroid (`com.arlosoft.macrodroid` và `.helper`).

**PC**: AMD Ryzen 7 7435HS, 8 nhân / 16 luồng; ổ D: còn trống 30 GB; có sẵn `uv 0.12.3` và Python 3.12.10 (bản Microsoft
Store).

**Thư viện thêm cho app spike** (tra dl.google.com maven ngày 2026-11-01): `androidx.media3:media3-common` 1.11.1 (có
`SonicAudioProcessor`), `androidx.work:work-runtime` 2.12.0.

## Quyết định

Hỏi người dùng ngày 2026-11-01. Người dùng chọn phương án Recommended ở cả 10 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D28 | Bản ghi để đo | **5 bài đã có kết quả pyannote của desktop**, chép ra `D:\GuzzMobileData\stage-01` (ngoài repo) cùng tệp gốc. B1 có 2 người nói, B2 có 3, B3 có 5, B4 có 4, B5 có 1. Thêm bài gốc 2 giờ (L1) và bài 115 phút có 5 người (L2) để đo tốc độ (bảng ở A2) | Có sẵn tham chiếu, không phải chạy lại pyannote. Chép ra để desktop chạy lại bài đó cũng không ghi đè `sach.flac`, số đo lặp lại được. |
| D29 | Độ chính xác được kém desktop tới mức nào | Áp cho **từng bài**, sau khi cả hai bên qua `speaker.process_turns` với cài đặt mặc định: DER so với desktop ≤ 15% (collar 0,5 s); phần trăm thời gian của người nói chính lệch ≤ 5 điểm %; số người nói lệch ≤ 1; bài 1 người không có người nào thêm vào chiếm > 5% thời gian | Đủ để bản chép lời ghi đúng giảng viên và người hỏi; không đòi khớp từng ranh giới. |
| D30 | Bài 2 giờ phải xong trong bao lâu | **≤ 30 phút, PSS đỉnh ≤ 1 GB**, nhiệt độ pin ≤ 45 °C, tốn ≤ 10% pin | Chạy nền được cùng việc khác; RAM thấp thì trình diệt app lúc thiếu RAM của HyperOS khó giết. |
| D31 | Đo trên điện thoại bằng app nào | **App spike tự viết** `io.github.cozycode890.guzz.spike`, dùng AAR sherpa-onnx 2.0.1 | Đổi được model, số luồng, `windowShiftRatio`, đo được RAM; dùng chung cho các phép đo giải mã, mã hoá và intent. App demo không đo được RAM theo cấu hình. |
| D32 | Code spike để ở đâu, có giữ lại không | **`spikes/` trên `main`, giữ lại sau spike**. `spikes/android` là build Gradle riêng, dùng chung version catalog và `build-logic` | Đo lại được trên máy khác hoặc với bản sherpa-onnx mới; Stage 5 dùng lại script so độ chính xác. App không phụ thuộc vào `spikes/`, `.\gradlew test` của app không đụng tới. |
| D33 | Thử model embedding nào | **3D-Speaker** (CAM++ zh-cn common, CAM++ zh_en advanced, ERes2Net base 200k; Apache-2.0) + **NeMo TitaNet small, WeSpeaker ResNet34-LM** (CC-BY-4.0). Chọn theo kết quả; chọn model CC-BY thì ghi công trong app (Stage 8/9) | WeSpeaker ResNet34-LM cùng họ embedding với pyannote community-1 của desktop; CC-BY-4.0 cho dùng thương mại, chỉ cần ghi công. |
| D34 | Bài dài quá heap Java | **Chia thành các phần ≤ 30–60 phút có chồng lấn, tách giọng từng phần rồi nối người nói** theo vùng chồng lấn và embedding trung bình của cụm. Chỉ dùng Kotlin, không cần NDK | Kotlin API nhận cả bài trong một `FloatArray` (2 giờ = 460 MB, heap tối đa 512 MB); bản Android dựng sẵn không có C API. Chia phần thì RAM bị chặn theo độ dài phần và cache được từng phần. |
| D35 | Chạy nền thế nào thì coi là đạt | **Sau khi cài một lần** (pin "No restrictions", bật "Autostart" của HyperOS): ít nhất một cách khởi động được FGS 10/10 lần trong điều kiện màn hình tắt ≥ 10 phút, máy ở Doze, Guzz đã bị vuốt khỏi đa nhiệm. Service chạy liền ≥ 60 phút khi màn hình tắt. Thử thêm khi chưa cài gì | Bắt người dùng cài một lần là chấp nhận được. Thử cả khi chưa cài để biết hướng dẫn cài đặt cần ghi những gì. |
| D36 | Đo định dạng đoạn nào | **FLAC, OGG-Opus, M4A-AAC, WAV 16-bit** | M4A-AAC chạy được từ API 26 (= minSdk); bộ mã hoá Opus và muxer OGG chỉ có từ API 29. |
| D37 | Có gửi thử Gemini để so định dạng không | **Có, khoảng 5 yêu cầu**: một đoạn 10 phút, mỗi định dạng gửi một lần, FLAC gửi hai lần; chạy bằng `google_ai.py` của desktop với key đã lưu; hỏi lại người dùng ngay trước khi gửi | Biết định dạng nén có làm bản chép lời kém đi không trước khi Stage 2 chọn. FLAC gửi hai lần cho biết Gemini tự dao động bao nhiêu. |
| D38 | Đo resampler nào | **Windowed-sinc tự viết** (Kotlin thuần) và **`SonicAudioProcessor` của Media3** | Stage 2 chọn theo chất lượng (SNR so với ffmpeg) và tốc độ. |

**Rút gọn stage**, hỏi người dùng ngày 2026-11-10 vì không đủ thời gian cho spike dài; muốn vừa làm vừa thử ở stage sau.
Người dùng chọn phương án Recommended ở cả 4 câu.

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D39 | Phần tách giọng còn lại (B5–B8, đo lại D4 / D5, D6, D7 cách 2) | **Chuyển sang Stage 5. Đi tiếp với sherpa-onnx** (I2) | Bài 2 giờ chia phần đã đạt D30; biết số người thì độ chính xác tạm được. Rủi ro còn lại (ngưỡng khi không biết số người) chỉ ảnh hưởng Stage 5. |
| D40 | Giải mã và resampler (E3–E4) | **Chốt `SincResampler`, loại Sonic**; phần chưa đo thành bước kiểm của Stage 2 | Sonic không lọc chống alias. |
| D41 | Định dạng đoạn (F3) | **FLAC, bỏ F3** | Không mất dữ liệu, giống desktop. |
| D42 | Chạy nền và MacroDroid (G2–G6) | **G4, G5 → Stage 4; G2, G3, G6 → Stage 6**; chỉ một đêm S1, bỏ S0 | Thử trên code thật. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- PC (pip) và điện thoại (AAR) dùng cùng một bản sherpa-onnx **2.0.1**, để so được kết quả hai nơi.
- Segmentation: `pyannote-segmentation-3-0`, model segmentation duy nhất không bị giới hạn phi thương mại. Giữ mặc định của
  sherpa-onnx: `minDurationOn = 0,2`, `minDurationOff = 0,5`.
- Trong repo, bài chỉ được gọi bằng mã (B1–B5, L1, L2, A1, V1). Tên tệp thật chỉ nằm trong `recordings.json` ngoài repo.
  `stage-01-results.md` chỉ có số tổng hợp, không có lời nói hay tên tệp thật.
- Thư mục dữ liệu ngoài repo: biến môi trường `GUZZ_SPIKE_DATA`, mặc định `D:\GuzzMobileData\stage-01`. Mọi thứ nặng hoặc
  lấy từ bản ghi thật đều để ở đây: bản ghi, model, venv Python, JSON kết quả, bản chép lời của Gemini. Trong repo chỉ
  gitignore thêm `spikes/android/app/libs/` (chỗ để AAR).
- Trên PC, sherpa-onnx chạy trong venv `uv` ở `$GUZZ_SPIKE_DATA\venv` (Python 3.12). Tính DER, làm mượt và đổi mốc chạy bằng
  `D:\Guzz\runtime\nguoi_noi\python.exe -B -s`, import thẳng `speaker`, `config`, `audio_processing` của desktop. Không cài
  gì vào `D:\Guzz`, không ghi gì vào đó.
- DER: `pyannote.metrics.diarization.DiarizationErrorRate(collar=0.5, skip_overlap=False)`, tức bỏ qua 0,25 s mỗi bên ranh
  giới lượt nói của tham chiếu; thư viện tự tìm cách ghép người nói tối ưu. Làm mượt bằng
  `speaker.process_turns(turns, config.Config())` (mặc định của `config.default.txt`), áp cho cả tham chiếu lẫn kết quả
  sherpa-onnx. Báo cả số trước khi làm mượt.
- JSON ra của sherpa-onnx cùng định dạng với `diarization_worker.py`: `model`, `thiet_bi`, `file_wav`, `thoi_luong_giay`,
  `giay_xu_ly`, `so_nguoi`, `doan` (`nguoi_noi` = `SPEAKER_00`…), thêm `sherpa` (cấu hình đã chạy) và `ram_dinh_mb`.
  Stage 5 dùng lại định dạng này.
- App spike:
  - applicationId `io.github.cozycode890.guzz.spike`; chỉ dùng bản debug, có hậu tố `.spike.debug`;
  - `largeHeap="true"`;
  - FGS loại `mediaProcessing` trên API 35+, loại `dataSync` trên API 26–34;
  - dữ liệu trên máy ở `/sdcard/Android/data/io.github.cozycode890.guzz.spike.debug/files/{models,input,results}`, không
    cần quyền bộ nhớ;
  - thư viện chỉ spike dùng (media3, work) khai thẳng toạ độ trong `spikes/android/app/build.gradle.kts`, không thêm vào
    `gradle/libs.versions.toml`.
- Lệnh đổi trạng thái hệ thống của điện thoại (`device_config`, `dumpsys deviceidle force-idle`) và cài đặt trên máy (pin,
  Autostart, quyền thông báo, quyền của MacroDroid): **người dùng tự chạy hoặc tự bật**. Claude soạn sẵn lệnh và lệnh hoàn
  tác.
- Gửi lên Gemini là việc ra ngoài máy, nên hỏi lại người dùng ngay trước khi chạy (D37).

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Lệnh chạy trong PowerShell 7 tại `D:\GuzzMobile`, với
`$D = $env:GUZZ_SPIKE_DATA`. Số đo ghi vào [stage-01-results.md](stage-01-results.md) ngay khi có.

**Rút gọn 2026-11-10 (D39–D42):** bước còn `[ ]` có dòng in đậm ở đầu, ghi bước đó bỏ hay chuyển sang stage nào. Phần đã
chuyển được chép sang mục "Phát sinh" của stage nhận.

Thứ tự:

- A làm trước.
- B chạy nền trên PC vài giờ; trong lúc đó làm C rồi D, E, F.
- G cần hai đêm để điện thoại rút cáp.
- Sau đó H, I, J.

Bước chạy lâu (B3–B8, D4–D6, G3) chạy nền và làm tiếp được: JSON đã có thì bỏ qua.

### Code spike nằm ở đâu (D32)

```
spikes/
  README.md              cách chạy lại từng phép đo, hướng dẫn macro MacroDroid
  diarization/           Python trên PC: prepare_data.py, fetch_models.ps1, run_sherpa.py, evaluate.py,
                         requirements.txt (đã ghim phiên bản), models.sha256
  media/compare_pcm.py   so PCM điện thoại với scipy / ffmpeg (SNR)
  formats/gemini_compare.py
  android/               build Gradle riêng: settings.gradle.kts, build.gradle.kts, gradle.properties,
                         gradle/gradle-daemon-jvm.properties, app/ (applicationId ...guzz.spike)
```

`settings.gradle.kts` gốc không `include` gì trong `spikes/`, nên `.\gradlew test` và `lint` của app không đụng tới. App
spike build bằng wrapper gốc: `.\gradlew -p spikes\android :app:installDebug`. Giữ lại sau spike, app không phụ thuộc vào.

### A. Chuẩn bị dữ liệu và công cụ

- [x] **A1. Khung `spikes/` và `.gitignore`.** Tạo cây thư mục ở trên và `spikes/README.md` (mục lục, biến
  `GUZZ_SPIKE_DATA`). Thêm `spikes/android/app/libs/` vào `.gitignore`. Đặt `GUZZ_SPIKE_DATA` mức User =
  `D:\GuzzMobileData\stage-01`. Đây là biến thường, không phải `Path`, nên dùng `SetEnvironmentVariable` được. Đặt xong thì
  khởi động lại app Claude, hoặc đặt tạm `$env:GUZZ_SPIKE_DATA` trong shell.
  **Kiểm:** `git check-ignore -v spikes/android/app/libs/x.aar` báo tệp bị bỏ qua; `.\gradlew projects` vẫn chỉ có `:app`,
  `:core:common`, `:core:audio`.
- [x] **A2. Danh sách bài (D28)** ghi vào `$D\recordings.json` (ngoài repo): mỗi mã trỏ tới thư mục tạm của desktop
  (`D:\Guzz\data\tmp\*_<đuôi>`) hoặc tới tệp gốc.

  | Mã | Dùng cho | Nguồn (đuôi thư mục tạm) | Desktop: sạch / gốc (phút) / số người |
  | --- | --- | --- | --- |
  | B1 | độ chính xác | `*_a3bbd5e7` | 85,7 / 114,3 / 2 |
  | B2 | độ chính xác | `*_041599f4` | 94,9 / 112,3 / 3 |
  | B3 | độ chính xác; L2 | `*_72e0108b` | 85,3 / 114,7 / 5 |
  | B4 | độ chính xác | `*_94fa7c97` | 47,4 / 78,9 / 4 |
  | B5 | độ chính xác | `*_cc16d2a2` | 59,1 / 84,5 / 1 |
  | L1 | tốc độ trên máy (10 / 60 / 120 phút); giải mã m4a | tệp gốc của `*_9ebc3241` (m4a, 196 MB) | 99,8 / 135,5 / 1 |
  | L2 | tốc độ khi nhiều người nói | tệp gốc của B3 | — / 114,7 / 5 |
  | A1 | giải mã AAC ADTS | tệp gốc của `*_bab05cca` (`.aac`) | 69,9 / 120,0 / 1 |
  | V1 | giải mã video | tệp gốc của `*_c2301aab` (mp4, 467 MB) | 39,2 / 59,7 / 3 |

  **Kiểm:** tệp có đủ 9 mã; mọi đường dẫn đều tồn tại.
- [x] **A3. `spikes/diarization/prepare_data.py`.** Chạy bằng `D:\Guzz\runtime\python\python.exe -B -s`, dùng
  `D:\Guzz\runtime\ffmpeg\ffmpeg.exe`. Chạy lại nhiều lần được: tệp đã có và đúng SHA-256 thì bỏ qua. Script làm:
  - **B1–B5**, chép vào `$D\<mã>\`:
    - `sach.flac` và `danh_sach_doan.json`;
    - JSON pyannote, đổi tên thành `ref.json`. Thư mục có hai JSON thì chọn JSON có `thoi_luong_giay` khớp thời lượng
      `sach.flac` (lệch ≤ 0,5 s); không JSON nào khớp thì báo lỗi;
    - tệp gốc (đường dẫn `nguon` trong `danh_sach_doan.json`), đổi tên thành `goc.<đuôi>`, rồi giải mã ra `goc16k.wav`
      (16 kHz mono 16-bit).
  - **L1** cắt từ đầu bài ra `L1_10m.f32`, `L1_60m.f32`, `L1_120m.f32` (f32le 16 kHz mono). **L2** giải mã cả bài ra
    `L2.f32`.
  - **Tệp cho phép đo giải mã**, trong `media\`:
    - `L1.m4a` (bản gốc), `A1.aac`, `V1.mp4`;
    - `L1.mp3` (MP3 64 kbps mono) và `L1.ogg` (Opus 32 kbps), mã hoá từ L1 bằng ffmpeg;
    - `sweep48k.wav`: quét sin từ 50 Hz tới 23 kHz, dài 60 s, 48 kHz;
    - `tones48k.wav`: tổng các sin 1 kHz, 6 kHz, 10 kHz và 15 kHz. Hai tệp này dùng để đo resampler.
  - **Đoạn cho phép đo định dạng:** `F1.f32` = phút 10–20 của `sach.flac` bài B1 (desktop đã gửi đoạn này lên Google).
  - Mỗi mã một `meta.json`: thời lượng, số người nói, số lượt nói, SHA-256 từng tệp.

  **Kiểm:** chạy hai lần, lần hai báo "đã có" cho mọi tệp. `meta.json` của B1–B5 khớp số ở bảng A2. Không đụng tới desktop:
  ghi `$start = Get-Date` trước khi chạy, sau đó lệnh
  `Get-ChildItem D:\Guzz -Recurse -File | ? { $_.FullName -notlike 'D:\Guzz\runtime\*' -and $_.LastWriteTime -gt $start }`
  ra trống.
- [x] **A4. Python cho sherpa-onnx.** Chạy lần lượt:
  - `uv venv --python 3.12 $D\venv`
  - `uv pip install --python $D\venv sherpa-onnx==2.0.1 numpy soundfile psutil`
  - `uv pip freeze --python $D\venv > spikes\diarization\requirements.txt`

  **Kiểm:** `& $D\venv\Scripts\python.exe -c "import sherpa_onnx; print(sherpa_onnx.__version__)"` in ra `2.0.1`.
- [x] **A5. `spikes/diarization/fetch_models.ps1`** tải vào `$D\models\`. Mỗi tệp tải về để trong một thư mục riêng.
  - Tải:
    - `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`: giải nén ra `seg\`, đọc tệp LICENSE đi kèm;
    - 5 model embedding của D33;
    - `0-four-speakers-zh.wav`, tệp thử của sherpa-onnx;
    - `sherpa-onnx-2.0.1.aar`, để vào `spikes/android/app/libs/`.
  - Kiểm toàn vẹn:
    - model embedding: so với `checksum.txt` của release `speaker-recongition-models`;
    - AAR: so SHA-256 với số ở "Thông tin bên ngoài";
    - các tệp còn lại: ghi SHA-256 vào `spikes/diarization/models.sha256` (commit tệp này) để các lần sau so.

  **Kiểm:** chạy lần hai, script báo mọi tệp "đã có, khớp SHA-256"; có `seg\model.onnx` và `seg\model.int8.onnx`.

### B. Độ chính xác trên PC (mục 1; D28, D29, D33, D34)

- [x] **B1. `run_sherpa.py`** (chạy bằng venv của A4).
  - Đầu vào: wav / flac / f32, 16 kHz mono.
  - Tham số: `--seg`, `--emb`, `--threads` (mặc định 8), `--shift` (`window_shift_ratio`), `--threshold` hoặc
    `--num-clusters`, `--out`.
  - Ra JSON theo "Mặc định kỹ thuật", kèm `giay_xu_ly` và `ram_dinh_mb` (`peak_wset` của `psutil`).
  - Đo tỉ lệ chồng lấn trong đầu ra. Có chồng lấn thì đổi về dạng không chồng lấn: vùng chồng lấn thuộc về đoạn dài hơn,
    giống bản `exclusive` mà desktop dùng.

  **Kiểm:** chạy `0-four-speakers-zh.wav` với `--num-clusters 4` thì `so_nguoi` = 4; `speaker.load_speaker_turns` của desktop
  đọc được JSON ra.
- [x] **B2. `evaluate.py`** (chạy bằng `D:\Guzz\runtime\nguoi_noi\python.exe -B -s`).
  - Với mỗi cặp (`ref.json`, JSON của sherpa-onnx), tính cả trước và sau `process_turns`:
    - DER, tách thành miss / false alarm / confusion;
    - phần trăm thời gian của người nói chính ở mỗi bên và độ lệch giữa hai bên;
    - số người nói;
    - có người nào thêm vào chiếm > 5% thời gian không (cho B5).
  - Mỗi lần chạy thêm một dòng vào `$D\out\pc\metrics.csv`, in bảng markdown để chép vào stage-01-results.md.
  - Tuỳ chọn `--remap <mã>`: kết quả chạy trên âm thanh gốc thì đổi mốc về âm thanh sạch bằng
    `speaker.remap_timestamps(turns, TimeMap(ban_do_thoi_gian, thoi_luong_goc).to_clean)`, đúng như
    `transcribe.diarize_speakers`.

  **Kiểm:**
  - import `speaker`, `config`, `audio_processing` không lỗi;
  - `ref.json` so với chính nó → DER 0; so với bản đã đổi tên nhãn người nói → DER 0;
  - so với bản gán hết cho một người → confusion bằng tổng thời gian của những người không phải người nói chính (sai số
    ≤ 0,5 điểm %).
- [x] **B3. Chọn `windowShiftRatio`.** Embedding CAM++ zh-cn common, `num_clusters` = số người của desktop,
  `shift` ∈ {0,1; 0,25; 0,5}, chạy trên B1–B5.
  **Kiểm:** bảng DER / thời gian / RAM theo shift. Chọn shift lớn nhất mà DER trung bình tăng không quá 1 điểm % so với 0,1
  (shift lớn thì ít embedding hơn, nên nhanh và nhẹ hơn). Ghi lựa chọn vào Nhật ký.
- [x] **B4. So 5 embedding (D33)** với shift chọn ở B3, `num_clusters` = số người của desktop, trên B1–B5.
  **Kiểm:** bảng DER sau làm mượt theo embedding × bài; chọn ra 2 embedding có DER trung bình thấp nhất.
- [ ] **→ Stage 5 (D39).** Đã chạy CAM++ zh_en ở 0,3–0,8: không bài nào đạt (xem Nhật ký).
  **B5. Ngưỡng phân cụm**, cho trường hợp không biết số người nói (mặc định của desktop). Chạy 2 embedding của B4 ×
  `threshold` ∈ {0,3; 0,4; 0,5; 0,6; 0,7; 0,8}, trên B1–B5.
  **Kiểm:** bảng theo ngưỡng. Chọn **một** ngưỡng chung cho cả 5 bài: trước hết lấy ngưỡng có nhiều bài đạt D29 nhất, hoà
  thì lấy ngưỡng có DER trung bình thấp hơn. Ghi rõ bài nào trượt tiêu chí nào.
- [ ] **→ Stage 5 (D39).** **B6. Segmentation int8** với cấu hình tốt nhất (embedding và ngưỡng của B5).
  **Kiểm:** bảng DER và thời gian của int8 so với fp32 trên B1–B5.
- [ ] **→ Stage 5 (D39).** **B7. Âm thanh gốc** (`goc16k.wav`) với cấu hình tốt nhất, chấm bằng `evaluate.py --remap`.
  **Kiểm:** bảng "sạch / gốc" cho B1–B5, kèm nhận xét trong stage-01-results.md: Stage 5 nên mặc định chạy trên âm thanh
  nào.
- [ ] **→ Stage 5 (D39).** Thuật toán đã viết (Python và Kotlin, khớp nhau ở D5); chưa chấm DER trên B1–B5.
  **B8. Chia phần rồi nối người nói (D34)**, `run_sherpa.py --piece-minutes 30|60 --overlap-seconds 60`. Các bước:
  1. Cắt bài thành các phần dài P phút; hai phần liền nhau chồng lấn O giây. Tách giọng từng phần với cấu hình tốt nhất.
  2. Nối phần k+1 vào phần k: trong vùng chồng lấn, lập ma trận thời gian trùng nhau giữa nhãn của hai phần, ghép cặp bằng
     `scipy.optimize.linear_sum_assignment`; chỉ nhận cặp trùng ≥ 5 s.
  3. Người nói chưa ghép được: lấy embedding trung bình của người đó (`SpeakerEmbeddingExtractor`, tối đa 30 s tiếng nói),
     so cosine với embedding trung bình của từng người nói đã có. Đạt ngưỡng của B5 thì gộp vào người đó, không thì thêm
     người mới.
  4. Trong vùng chồng lấn, dùng lượt nói của phần trước tới giữa vùng, của phần sau từ giữa vùng trở đi.

  **Kiểm:** với B1–B5, P = 30 và P = 60: DER so với desktop (tiêu chí D29), DER tăng bao nhiêu so với chạy nguyên bài, RAM
  đỉnh trên PC theo P.

### C. App spike (D31, D32)

- [x] **C1. Build riêng `spikes/android/`.**
  - `settings.gradle.kts`:
    - `pluginManagement { includeBuild("../../build-logic") }`;
    - `dependencyResolutionManagement` với google + mavenCentral như build gốc;
    - `versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }`.
  - `build.gradle.kts` chép khối `plugins { … apply false }` của build gốc. Chép `gradle.properties` và
    `gradle/gradle-daemon-jvm.properties` của build gốc.
  - Module `:app`:
    - plugin `guzz.android.application` + `guzz.android.compose`;
    - namespace và applicationId `io.github.cozycode890.guzz.spike`;
    - phụ thuộc `implementation(files("libs/sherpa-onnx-2.0.1.aar"))`, `androidx.media3:media3-common:1.11.1`,
      `androidx.work:work-runtime:2.12.0`.
  - Manifest: `largeHeap="true"`; quyền `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`,
    `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

  **Kiểm:**
  - `.\gradlew -p spikes\android :app:assembleDebug` xanh;
  - `.\gradlew -p spikes\android :app:installDebug`, rồi
    `adb shell am start -n io.github.cozycode890.guzz.spike.debug/io.github.cozycode890.guzz.spike.MainActivity` mở được
    app;
  - `.\gradlew test` ở gốc vẫn xanh và không configure `spikes`.
- [x] **C2. Hạ tầng đo dùng chung.**
  - `BenchLog`: ghi JSON lines vào `files/results/<phép đo>.jsonl` (giờ, model máy, API, tham số, kết quả).
  - `MemSampler`: mỗi giây đọc `Debug.getPss()`, `VmRSS` / `VmHWM` trong `/proc/self/status`, heap Java đang dùng,
    `Debug.getNativeHeapAllocatedSize()`; giữ giá trị đỉnh.
  - `BatteryProbe`: % pin, `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh), nhiệt độ pin (`ACTION_BATTERY_CHANGED`),
    `PowerManager.getCurrentThermalStatus()`; giữ giá trị đỉnh trong lúc đo.
  - `BenchService`: FGS (loại `mediaProcessing` trên API 35+, `dataSync` dưới API 35), có thông báo tiến độ, chạy phép đo
    theo extra. `onTimeout` thì ghi log rồi `stopSelf()`.
  - `BenchActivity`: nhận lệnh `adb shell am start … --es bench <tên> --es <tham số>`, xin quyền thông báo, khởi động
    `BenchService` khi đang hiện trên màn hình, nên không vướng hạn chế chạy nền.

  **Kiểm:** chạy `--es bench noop --ei seconds 60` rồi `adb pull`: `noop.jsonl` có dòng bắt đầu, dòng kết thúc, PSS đỉnh
  > 0; thông báo hiện trong lúc chạy; log ghi `getLargeMemoryClass()` = 512.
- [x] **C3. Dung lượng thư viện** (cho Stage 9): chạy `apkanalyzer files list` và `apkanalyzer files download-size` trên APK
  spike.
  **Kiểm:** cỡ `lib/arm64-v8a/libsherpa-onnx-jni.so` và `libonnxruntime.so` đã ghi vào stage-01-results.md.

### D. Tách giọng trên máy (mục 2; D30, D34)

- [x] **D1. Đưa dữ liệu lên máy** bằng `adb push`:
  - model (segmentation và 2 embedding tốt nhất của B4) vào `files/models/`;
  - `L1_10m.f32`, `L1_60m.f32`, `L1_120m.f32`, `L2.f32` vào `files/input/`.

  Bench nạp model theo đường dẫn tệp, nên đổi model không cần build lại.
  **Kiểm:** `adb shell ls -l` cho cỡ tệp giống trên PC.
- [x] **D2. Bench `diarize`, chạy nguyên bài.** Đọc tệp f32 vào một `FloatArray`, gọi `processWithCallback` (log tiến
  độ), ghi JSON cùng định dạng với PC vào `results/`. Chạy `L1_10m` với cấu hình tốt nhất của B.
  **Kiểm:** chạy `run_sherpa.py` trên PC với cùng tệp, cùng cấu hình; `evaluate.py` so JSON của điện thoại với JSON của PC
  (PC làm tham chiếu) → DER ≤ 1%.
- [x] **D3. Số luồng.** `L1_10m`, `numThreads` ∈ {1, 2, 4, 6, 8}.
  **Kiểm:** bảng thời gian theo số luồng. Chọn số luồng nhỏ nhất mà chậm hơn bản nhanh nhất không quá 10%, dùng cho D4–D6.
- [x] **D4. 1 giờ và 2 giờ, chạy nguyên bài.** `L1_60m`, rồi `L1_120m` (`FloatArray` 460 MB, sát heap 512 MB). Bắt
  `OutOfMemoryError` và ghi lại, không để app chết.
  **Kiểm:** mỗi lần chạy có một dòng kết quả: thời gian, PSS đỉnh, `VmHWM`, heap Java đỉnh. Nếu OOM thì ghi nó xảy ra ở đâu:
  lúc cấp mảng hay lúc chạy native.
- [ ] **Một phần; còn lại → Stage 5 (D39).** Đã có: kiểm 1 đạt; `L1_120m` P = 20 phút với ngưỡng tạm 0,5. Còn: `L2`, và đo
  lại với ngưỡng thật.
  **D5. 2 giờ, chia phần (D34).** Port sang Kotlin thuật toán nối người nói của B8, đặt trong app spike. Đọc từng phần từ
  tệp f32 (`FileChannel`), không lúc nào giữ cả bài trong heap. Chạy `L1_120m` và `L2` với P chọn ở B8.
  **Kiểm:**
  - chạy `L1_10m` với phần nhỏ (P = 3 phút, O = 60 s): kết quả giống bản Python cùng tham số, DER giữa hai bản ≤ 1%;
  - với `L1_120m` và `L2`: thời gian và PSS đỉnh, so với ngưỡng D30.
- [ ] **→ Stage 5 (D39).** Số tạm từ lượt D5 `L1_120m` rút cáp: −8% pin, nhiệt đỉnh 41,1 °C (khởi động bằng adb, không
  qua MacroDroid).
  **D6. Pin và nhiệt.** Chạy một lần bài 2 giờ, chọn cách tốt nhất trong D4 / D5. Máy rút cáp, màn hình tắt từ lúc bắt
  đầu; khởi động bằng MacroDroid, gộp với G4. Cắm lại máy rồi mới lấy kết quả.
  **Kiểm:** số % pin và µAh đã tốn, nhiệt độ pin đỉnh, trạng thái nhiệt đỉnh; so với D30.
- [ ] **Một phần; cách 2 → Stage 5 (D39).** Cách 1 không dừng được; cách 3 dừng trong 54 ms. Đề xuất cho Stage 5: chạy
  tách giọng trong process riêng.
  **D7. Dừng giữa chừng** (cho Stage 5). Thử ba cách:
  1. callback trả về khác 0 thì `process` có dừng sớm không (theo mã nguồn v2.0.1 thì không);
  2. chạy chia phần: từ lúc bấm Dừng tới khi phần đang chạy xong mất bao lâu;
  3. chạy bench trong process riêng (`android:process=":diarize"`) rồi `Process.killProcess`: process chính còn sống
     không, RAM có được trả lại không.

  **Kiểm:** kết quả ba cách đã ghi vào stage-01-results.md, kèm đề xuất cách Dừng cho Stage 5.

### E. Giải mã và resampler (mục 3; D38)

- [x] **E1. Đưa tệp lên máy:** `media\L1.m4a`, `A1.aac`, `L1.mp3`, `L1.ogg`, `V1.mp4`, `sweep48k.wav`, `tones48k.wav` →
  `files/input/`.
  **Kiểm:** `adb shell ls -l` cho cỡ tệp đúng.
- [x] **E2. Hai resampler** về 16 kHz:
  - `SincResampler`: windowed-sinc polyphase với cửa sổ Kaiser, Kotlin thuần, có unit test JVM (sin 1 kHz ở 48 kHz đổi
    sang 16 kHz vẫn là sin 1 kHz, đúng biên độ, đã bù độ trễ);
  - `SonicAudioProcessor` của media3 1.11.1, đặt `setOutputSampleRateHz(16000)`.

  **Kiểm:** `.\gradlew -p spikes\android :app:testDebugUnitTest` xanh.
- [ ] **Một phần; còn lại → Stage 2 (D40).** Đã đo m4a, aac, mp3, ogg (chỉ giải mã). Còn: `V1.mp4`, ogg cả chuỗi, đối chiếu
  thời lượng với `ffmpeg -i`.
  **E3. Bench `decode`.** Chuỗi xử lý: MediaExtractor chọn luồng âm thanh đầu tiên → MediaCodec → PCM → trộn mono →
  resample (chọn resampler bằng extra) → ghi f32 ra tệp theo từng khối, không giữ cả bài. Đo riêng thời gian chỉ giải mã
  và thời gian cả chuỗi. Ghi tên bộ giải mã (`MediaCodec.getName()`), tần số lấy mẫu và số kênh gốc, PSS đỉnh.
  **Kiểm:** bảng định dạng × resampler: nhanh gấp mấy lần thời gian thực, PSS đỉnh. Thời lượng PCM ra lệch ≤ 0,1 s so với
  `ffmpeg -i`.
- [ ] **Một phần; SNR tệp thật → Stage 2 (D40).** Đã đo `sweep48k` và `tones48k`: chọn sinc.
  **E4. Chất lượng resampler.** `spikes/media/compare_pcm.py` (chạy bằng `D:\Guzz\runtime\python\python.exe -B -s`,
  dùng numpy + scipy):
  - `sweep48k`: so đầu ra của điện thoại với `scipy.signal.resample_poly`, tính SNR trong dải 0–7 kHz;
  - `tones48k`: đo sin 10 kHz và 15 kHz bị suy giảm bao nhiêu. Hai tần số này trên Nyquist 8 kHz nên phải bị lọc đi;
  - tệp thật: `L1.m4a` giải mã trên máy, so với `ffmpeg -ac 1 -ar 16000 -f f32le`. Căn độ trễ bằng tương quan chéo trên
    30 s đầu rồi tính SNR.

  **Kiểm:** bảng SNR và độ suy giảm theo resampler; kiến nghị cho Stage 2 đã ghi vào stage-01-results.md.

### F. Định dạng đoạn gửi Gemini (mục 4; D36, D37)

- [x] **F1. Liệt kê bộ mã hoá** bằng `MediaCodecList(REGULAR_CODECS)` cho `audio/flac`, `audio/opus`, `audio/mp4a-latm`:
  tên, có phải bộ mã hoá phần mềm không, dải bitrate, tần số lấy mẫu hỗ trợ. Kiểm luôn những định dạng ra mà MediaMuxer có
  trên API 36.
  **Kiểm:** danh sách đã ghi vào stage-01-results.md.
- [x] **F2. Bench `encode`.** Từ `F1.f32` (10 phút, 16 kHz mono), ghi ra:
  - FLAC: dùng MediaCodec `audio/flac` với `KEY_FLAC_COMPRESSION_LEVEL` mặc định và mức 8. Tự ghi tệp `.flac`: `fLaC`,
    STREAMINFO lấy từ buffer codec-config, rồi các khung. Phải tự ghi vì MediaMuxer không ghi được FLAC;
  - OGG-Opus: MediaCodec `audio/opus` ở 24 và 32 kbps + MediaMuxer `MUXER_OUTPUT_OGG`, đuôi `.ogg`;
  - M4A-AAC: AAC-LC ở 48 và 64 kbps + MediaMuxer `MUXER_OUTPUT_MPEG_4`;
  - WAV 16-bit.

  Đo cỡ tệp (MB), thời gian mã hoá, PSS đỉnh.
  **Kiểm:** `adb pull` về `$D\out\formats\`.
  - Mỗi tệp chạy `ffmpeg -v error -i <tệp> -f null -` không báo lỗi; thời lượng 600 s ± 0,1 s.
  - FLAC giải mã ngược ra PCM 16-bit trùng từng mẫu với đầu vào đã lượng tử hoá 16-bit.
- [ ] **Bỏ (D41): chọn FLAC.** Script `gemini_compare.py` vẫn giữ, Stage 8 dùng lại nếu xét Opus.
  **F3. Gửi thử Gemini (D37).** `spikes/formats/gemini_compare.py`, chạy bằng
  `D:\Guzz\runtime\python\python.exe -B -s`:
  - đọc cài đặt thật của desktop bằng `config.load_config()` (chỉ đọc);
  - tạo `google_ai.Client(google_ai.get_api_key())`;
  - gọi `client.transcribe(path, cfg)` lần lượt cho FLAC (hai lần), OGG-Opus 32 kbps, M4A-AAC 64 kbps, WAV;
  - lưu bản chép lời vào `$D\gemini\` (ngoài repo);
  - so từng bản với FLAC lần 1 bằng `difflib.SequenceMatcher` trên dãy từ đã viết thường; lấy được số token đầu vào thì
    ghi lại.

  **Hỏi lại người dùng ngay trước khi chạy.**
  **Kiểm:** có 5 phản hồi, không có lỗi API. Bảng độ giống: cặp FLAC/FLAC cho biết Gemini tự dao động bao nhiêu. Định dạng
  nào giống bản FLAC 1 kém hơn mức dao động đó quá 5 điểm % thì loại.

### G. Intent → việc chạy nền (mục 5; D35)

- [x] **G1. Thành phần trong app spike.**
  - `RunReceiver`: exported, action `io.github.cozycode890.guzz.spike.action.RUN`, extra `method`, `request_id`,
    `minutes`. Với `method=broadcast` thì gọi `startForegroundService` ngay; với `method=work` thì xếp `ExpeditedWorker`
    vào hàng đợi.
  - `TrampolineActivity`: theme trong suốt, `excludeFromRecents`, `noHistory`. `onCreate` gọi `startForegroundService` rồi
    `finish()`. Extra `show_when_locked` để thử thêm `setShowWhenLocked(true)` nếu bản thường không chạy.
  - `ExpeditedWorker`: WorkManager với `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, gọi
    `setForeground(ForegroundInfo(…, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING))`. Nếu lỗi thì ghi lại, rồi vẫn chạy tiếp 5
    phút không có FGS để xem job sống được bao lâu.
  - `HeartbeatService` (FGS): chạy `minutes` phút (mặc định 3). Cứ 30 s ghi một nhịp, kèm kết quả của
    `GET https://www.google.com/generate_204` để biết trong Doze có mạng không. Chạy xong thì phát broadcast ngầm
    `io.github.cozycode890.guzz.spike.event.DONE` (extra `request_id`) cho MacroDroid.
  - Mỗi lần nhận lệnh, ghi một dòng vào `automation.jsonl`:
    - giờ, `method`, `request_id`;
    - `PowerManager.isInteractive`, `isDeviceIdleMode`, `isIgnoringBatteryOptimizations`;
    - standby bucket (`UsageStatsManager.getAppStandbyBucket`);
    - lý do khởi động process (`ActivityManager.getHistoricalProcessStartReasons`, API 35+);
    - kết quả: `ok`, hoặc tên lỗi kèm thông điệp, vd `ForegroundServiceStartNotAllowedException`.
  - Màn hình chính hiện 20 dòng gần nhất và nút "Xin miễn tối ưu pin" (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

  **Kiểm:** lúc app đang mở, chạy
  `adb shell am broadcast -n io.github.cozycode890.guzz.spike.debug/io.github.cozycode890.guzz.spike.RunReceiver -a io.github.cozycode890.guzz.spike.action.RUN --es method broadcast --es request_id t1`.
  Log có dòng `ok`, thông báo hiện trong 3 phút, rồi có dòng DONE. Làm tương tự với `method=work` và với `am start`
  `TrampolineActivity`. Lệnh từ adb chỉ để kiểm code, không tính vào kết quả.
- [ ] **→ Stage 6 (D42).** **G2. Macro MacroDroid.** Người dùng tạo trên máy theo hướng dẫn Claude soạn; hướng dẫn chép vào `spikes/README.md`.
  - "Spike broadcast": trigger "Regular Interval" 30 phút → action "Send Intent", Target Broadcast, Package
    `io.github.cozycode890.guzz.spike.debug`, Class `io.github.cozycode890.guzz.spike.RunReceiver`, Action
    `io.github.cozycode890.guzz.spike.action.RUN`, extra `method=broadcast`, `request_id` = giờ hệ thống.
  - "Spike activity": như trên, nhưng Target Activity, Class `io.github.cozycode890.guzz.spike.TrampolineActivity`, chạy
    lệch 10 phút so với macro đầu.
  - "Spike work": Target Broadcast, `method=work`, lệch 20 phút.
  - "Spike DONE": trigger "Intent Received" với action `io.github.cozycode890.guzz.spike.event.DONE` → ghi một dòng bằng
    action "Write to File".

  Bản thân MacroDroid cũng phải được đặt pin "No restrictions", bật "Autostart" và có quyền "Display over other apps"
  (người dùng bật). Thiếu một trong ba thì lỗi nằm ở phía gửi.
  **Kiểm:** bật macro lúc màn hình đang mở; log của MacroDroid có lần gửi, `automation.jsonl` có lần nhận tương ứng.
- [ ] **→ Stage 6 (D42), chỉ S1 (một đêm).** **G3. Ma trận thử.** Máy rút cáp suốt đêm, vì máy đang sạc không vào Doze. Trước khi tắt màn hình, vuốt app spike khỏi
  đa nhiệm.
  - **S1, đêm 1**, sau khi cài một lần: app spike "No restrictions" + "Autostart", đã bấm "Xin miễn tối ưu pin". Ba macro
    chạy cả đêm; mỗi cách cần ≥ 10 lần ở trạng thái `isInteractive = false` và `isDeviceIdleMode = true`. Nếu đêm đó quá ít
    lần rơi vào Doze thì đêm sau người dùng tự chạy `adb shell dumpsys deviceidle force-idle` qua Wireless debugging, hoàn
    tác bằng `adb shell dumpsys deviceidle unforce`.
  - **S0, đêm 2**, chưa cài gì: pin mặc định, tắt Autostart, gỡ miễn tối ưu pin. Ba macro, mỗi cách ≥ 3 lần.
  - Nếu `TrampolineActivity` bản thường không chạy lúc màn hình tắt thì thêm biến thể `show_when_locked`.

  **Kiểm:** bảng cách × cài đặt: số lần FGS lên / số lần MacroDroid gửi (đếm từ log hai bên), kèm các lỗi gặp. Đạt D35 khi
  ít nhất một cách ở S1 được 10/10.
- [ ] **→ Stage 4 (D42).** **G4. Chạy dài.** Dùng cách đã đạt ở G3: MacroDroid khởi động lúc màn hình tắt, chạy bench 2 giờ của D6. Nếu D chưa
  xong thì chạy `HeartbeatService` 90 phút.
  **Kiểm:** nhịp trong log liền ≥ 60 phút, không hở quá 2 phút; service không bị giết; ghi lại suốt lúc chạy có mạng hay
  không.
- [ ] **→ Stage 4 (D42).** **G5. `onTimeout`** (cho Stage 4). Người dùng tự chạy
  `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 120000`, rồi mở `HeartbeatService` 5
  phút từ app. Xong thì chạy `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.
  **Kiểm:** log có `onTimeout(startId, fgsType)` vào khoảng phút thứ 2; service dừng trong ≤ 1 s;
  `adb logcat -d -b crash` không có dòng nào của app spike.
- [ ] **→ Stage 6 (D42).** **G6. Sự kiện gửi ngược về MacroDroid** (rủi ro của Stage 6): đếm số broadcast DONE mà macro "Spike DONE" bắt được,
  kể cả lúc màn hình tắt.
  **Kiểm:** log MacroDroid có DONE cho ≥ 3 lần chạy lúc màn hình tắt, `request_id` khớp.

### H. Giấy phép (mục 6)

- [x] **H1. Bảng giấy phép** cho mọi thứ đã chọn hoặc định đóng gói:
  - segmentation-3.0: MIT, đối chiếu thẻ HF với tệp LICENSE trong tar.bz2;
  - model embedding chọn ở B. 3D-Speaker: trường License trên ModelScope. TitaNet small: trang NGC
    `nvidia/nemo/titanet_small`. WeSpeaker: `wespeaker/docs/pretrained.md`;
  - sherpa-onnx (Apache-2.0); ONNX Runtime (MIT, kèm NOTICE trong AAR); media3 và WorkManager (Apache-2.0).

  Mỗi dòng có URL và ngày kiểm. Model CC-BY thì ghi sẵn câu ghi công cần đưa vào app.
  **Kiểm:** bảng có trong stage-01-results.md; không mục nào đã chọn có giấy phép phi thương mại hay "chỉ cho nghiên cứu".

### I. Tổng hợp và quyết định

- [x] **I1. Viết xong [stage-01-results.md](stage-01-results.md).** Gồm số đo của 6 mục và các mặc định đã chọn:
  - tách giọng: embedding, ngưỡng, `windowShiftRatio`, fp32 hay int8, số luồng, chạy nguyên bài hay chia phần (P, O), chạy
    trên âm thanh gốc hay âm thanh sạch, cách Dừng giữa chừng;
  - âm thanh: resampler, định dạng và bitrate của đoạn;
  - chạy nền: cách nhận lệnh từ MacroDroid, những gì hướng dẫn cài đặt phải ghi;
  - bảng giấy phép.

  **Kiểm:** mỗi mục có số đo kèm tên máy, bản Android, ngày đo; không còn ô "chưa đo".
- [x] **I2. Đi tiếp hay đổi hướng với sherpa-onnx.** Đi tiếp (D39); hỏi lại ở Stage 5 nếu ngưỡng không đạt. Cả 5 bài đạt D29 và bài 2 giờ đạt D30 (chạy nguyên bài hoặc chia phần)
  thì đi tiếp. Không đạt thì ghi rõ bài nào trượt tiêu chí nào, vì sao, rồi **hỏi người dùng** bằng câu trắc nghiệm: đi
  tiếp kèm rủi ro, nới ngưỡng, hay đổi hướng (tự ghép VAD + embedding trên ONNX Runtime, hoặc thu hẹp phạm vi tách giọng).
  **Kiểm:** quyết định có số D mới trong README §2.
- [x] **I3. Mặc định cho stage sau.** Ghi vào mục "Phát sinh" của từng stage:
  - Stage 2: định dạng đoạn, resampler, đọc PCM theo khối;
  - Stage 4: loại FGS, `onTimeout`, có mạng trong Doze không;
  - Stage 5: model, ngưỡng, shift, chia phần, cách Dừng, âm thanh gốc hay sạch;
  - Stage 6: cách nhận lệnh, hướng dẫn cài đặt, sự kiện gửi ngược về MacroDroid;
  - Stage 9: dung lượng các tệp .so, câu ghi công CC-BY.

  Nếu kiến trúc module ở README §3 cần đổi (vd tách giọng chạy trong process riêng) thì ghi luôn.
  **Kiểm:** mỗi stage nêu trên có một dòng "(Từ Stage 1, <ngày>)".

### J. Đóng stage

- [x] **J1. Lệnh.** Thêm vào "Lệnh thường dùng" của CLAUDE.md các lệnh: build và cài app spike, đẩy dữ liệu lên máy, lấy kết
  quả về, chạy các script đo trên PC.
  **Kiểm:** chạy lại từng lệnh đúng như đã chép, lệnh nào cũng chạy được.
- [x] **J2. Kiểm trước khi commit:** `git status` không có bản ghi, JSON lượt nói, bản chép lời, model hay AAR;
  `git ls-files spikes` chỉ có code, script và `models.sha256`.
  **Kiểm:** `git ls-files | Select-String '\.(flac|wav|m4a|aac|mp3|ogg|mp4|f32|onnx|aar)$'` chỉ ra tệp trong
  `testdata/golden/`.
- [x] **J3. Đóng stage.** Tick đủ "Tiêu chí hoàn thành". Trong CLAUDE.md: Stage 1 → ✅ kèm ngày; cập nhật "Stage hiện tại";
  "Việc tiếp theo" → plan chi tiết Stage 2. Commit message tiếng Việt, push lên `main`.
  **Kiểm:** `git log -1` có commit đóng stage; `git status -sb` báo `## main...origin/main`.

## Rủi ro

- Không có kết quả desktop để so → chạy lại pyannote bằng `runtime\nguoi_noi` trên PC. Hiện đã có sẵn 35 kết quả (D28).
- `FloatArray` của bài 2 giờ không vừa heap 512 MB → chia phần (D34). Phân cụm complete-linkage tốn RAM theo bình phương
  số embedding → tăng `windowShiftRatio` (B3) hoặc chia phần.
- Không dừng được lúc sherpa-onnx đang chạy, vì giá trị trả về của callback bị bỏ qua → D7 thử dừng giữa các phần, và
  chạy trong process riêng.
- HyperOS có thể chặn macro hoặc app tự khởi chạy. Vuốt app khỏi đa nhiệm có thể bằng force-stop, mà app đã bị force-stop
  thì không nhận broadcast cho tới khi được mở lại → G3 ghi lý do khởi động process, và thử cả S0 lẫn S1.
- Máy đang cắm cáp thì không vào Doze → G3 chạy lúc rút cáp, đọc log sau.
- Tham chiếu là kết quả pyannote, không phải nhãn do người gán → DER ở đây là "lệch so với desktop", không phải sai số tuyệt
  đối. Ghi rõ điều này trong stage-01-results.md.
- Bước B chậm trên PC, vì đổi ngưỡng nào cũng phải tính lại embedding → B3–B5 chạy nền, làm tiếp được (JSON đã có thì bỏ
  qua).

## Nhật ký

- **2026-11-01, plan.** Kiểm thông tin bên ngoài (mục "Thông tin bên ngoài"). Hỏi người dùng 10 câu trắc nghiệm, cả 10 câu
  đều chọn phương án Recommended (D28–D38). Viết kế hoạch chi tiết A–J và khung stage-01-results.md. Plan bị ngắt giữa
  chừng vì người dùng cần để dành token; câu trả lời được lưu ở ghi chú ngoài repo, phiên sau làm tiếp.
- **2026-11-01, làm A–B2.**
  - App Claude giữ `JAVA_HOME` cũ (JDK 25 đã gỡ) và chưa có `GUZZ_SPIKE_DATA`: lệnh Gradle trong session này đặt tạm
    `$env:JAVA_HOME` / `$env:ANDROID_HOME` / `$env:GUZZ_SPIKE_DATA` từ giá trị mức User.
  - A3: 5 bài đều chỉ có một JSON pyannote trong thư mục tạm, chạy trên `sach.flac` (desktop mặc định `run_on =
    da_lam_sach`). `L1_10m` / `L1_60m` là phần đầu của `L1_120m.f32` (giải mã một lần), giống hệt cắt riêng.
    Thêm `_common.py` (thư viện chuẩn) dùng chung cho các script, vì mỗi script chạy bằng một Python khác nhau.
  - A4: cài thêm `scipy` vào venv cho `linear_sum_assignment` của B8.
  - B1: bản Python của sherpa-onnx để `min_duration_on = 0,3`, Kotlin để 0,2. `run_sherpa.py` đặt rõ 0,2 / 0,5 để PC và
    điện thoại chạy cùng cấu hình. Docstring Python của `process` ghi "Return a non-zero value to abort", trái với đọc mã
    nguồn lúc plan → D7 thử trên máy.
  - B2: kiểm "gán hết cho một người" theo plan (confusion ≈ phần người khác, ±0,5 điểm) chỉ đúng khi collar 0. Với
    collar 0,5 s, B1 còn 6,9% thay vì 11,2%, vì ref có 3 623 lượt và collar bỏ 0,25 s mỗi bên ranh giới. Self-test kiểm
    cả hai: collar 0 so với phần người khác, collar 0,5 so với phần người khác tính sau khi bỏ vùng collar (tự tính, không
    qua pyannote). Cả 5 bài khớp tới 0,1 điểm.
  - Thêm `sweep.py`: chạy B3–B8 theo nhóm, mỗi lần chạy một process để RAM đỉnh đúng, JSON đã có thì bỏ qua.
- **2026-11-01, làm C, H1, viết code D–G.**
  - App spike chia package: `bench/` (BenchActivity, BenchService, MemSampler, BatteryProbe, BenchLog), `diarize/`,
    `media/`, `automation/`. Vì vậy class của G1/G2 là `io.github.cozycode890.guzz.spike.automation.RunReceiver` và
    `….automation.TrampolineActivity`, không phải `….spike.RunReceiver` như plan; lệnh và macro trong `spikes/README.md`
    dùng tên mới.
  - Thêm `WAKE_LOCK`: BenchService và HeartbeatService giữ partial wake lock, vì FGS không giữ CPU thức khi màn hình tắt.
    Guzz thật cũng phải làm vậy (ghi vào Phát sinh của Stage 4 ở I3).
  - `RunReceiver` / `TrampolineActivity` có extra `bench` thì khởi động BenchService thay cho HeartbeatService, để D6 / G4
    chạy bench 2 giờ bằng MacroDroid; xong bench thì phát DONE.
  - D7 cách 3: `IsolatedBenchService` (process `:diarize`) được gọi bằng `startService`, nên không lên FGS được thì vẫn
    chạy như service thường.
  - C3: AAR 2.0.1 có `libsherpa-onnx-c-api.so` (plan ghi bản Android tắt C API). JNI không phụ thuộc vào nó.
  - E4: alias của 10 kHz và 15 kHz khi ra 16 kHz rơi đúng 6 kHz và 1 kHz, tức trùng hai sin còn lại của `tones48k`.
    `compare_pcm.py` đo suy giảm 10 / 15 kHz trên đoạn quét sin (quét tuyến tính nên thời điểm ứng với tần số), còn
    `tones48k` chỉ kiểm biên độ 1 / 6 kHz.
  - F3: `gemini_compare.py` chỉ gửi khi có `--yes`.
  - **Dừng ở đây (commit a9f2bef):** xong A1–A5, B1, B2, C3, H1; C1 xanh trên PC (còn cài lên máy). B3 đang chạy nền
    (`$D\out\pc\sweep-B3.log`; chạy lại `sweep.py B3` là làm tiếp). Chờ người dùng cắm điện thoại qua USB để làm C1–C2,
    D, E, F1–F2, G1.
- **2026-11-01, B3 xong.** Chọn `windowShiftRatio = 0,1` theo luật của B3 (bảng ở stage-01-results.md). Ghi chú: chênh
  lệch DER giữa các shift chủ yếu do complete-linkage + ép số cụm (B4 trượt ở cả ba shift), không hẳn do shift; shift 0,5
  nhanh gấp 9 lần. Nếu D3/D4 cho thấy shift 0,1 làm bài 2 giờ trên điện thoại quá 30 phút (D30) thì xem lại shift ở I2.
  B4 chạy với `--jobs 2 --threads 8` (PC chỉ còn trống khoảng 2,6 GB RAM), nên thời gian của B4 không so được với B3.
- **2026-11-01, trên máy: C1, C2, D1, E1, F1, F2.**
  - C2: `noop` 60 s có dòng bắt đầu / kết thúc, PSS đỉnh 189 MB, `largeMemoryClass` = 512, có thông báo. App spike có
    `isIgnoringBatteryOptimizations = true` ngay sau khi cài, dù chưa bấm "Xin miễn tối ưu pin"; S0 ở G3 phải gỡ bằng
    `dumpsys deviceidle whitelist -<package>`.
  - F2: vòng lặp MediaCodec ban đầu chờ 5 ms ở cả đầu vào lẫn đầu ra cho từng khung 1 024 mẫu, nên đo ra thời gian chờ
    (FLAC 85 s / 10 phút). Sửa: nạp mọi buffer đầu vào đang trống không chờ, khung 1 s, chỉ chờ 1 ms ở đầu ra → FLAC 4–6 s.
    Thêm biến thể `flacmp4` vì F1 thấy MediaMuxer MPEG_4 nhận FLAC. AAC thừa 2 048 mẫu priming (600,128 s, lệch quá
    ±0,1 s của dòng Kiểm): đó là kết quả đo của định dạng, không phải lỗi bench; vẫn đánh dấu F2 xong.
  - E3: Sonic của Media3 1.11 đòi `flush(StreamMetadata)` (bản không tham số ném IllegalStateException). Giải mã AAC lúc
    màn hình tắt chỉ 3,6–8× thời gian thực ở chế độ sync. Thử: tệp ở bộ nhớ trong thay vì `/sdcard` (FUSE) không nhanh
    hơn; màn hình bật nhanh gấp khoảng 2; chế độ async (`setCallback`) ổn định 16× lúc màn hình tắt. E3 chạy bằng async;
    mỗi dòng bench ghi `man_hinh_bat`. Thêm tham số `api`, `max_seconds` cho bench `decode`.
  - D2: lần đầu app chết vì JNI abort: JNI của sherpa-onnx tìm `invoke(IIJ)Ljava/lang/Integer;` trên callback, lambda
    Kotlin 2.x (invokedynamic + D8) không có method đó. Sửa bằng lớp callback viết rõ (`ProgressCallback`). Stage 5 phải
    làm như vậy (ghi ở I3).
  - Thêm `spikes/android/run_bench.ps1` và `spikes/android/batches/*.txt` để chạy lần lượt nhiều bench qua adb.
  - D3 (màn hình tắt cả 5 lượt): 1 / 2 / 4 / 6 / 8 luồng = 213 / 125 / 77 / 113 / 108 s. Lượt 4 luồng chạy đầu tiên lúc
    máy mát, nên `batches/d4.txt` đo lặp 4 luồng và thêm 3 luồng trước khi chốt.
  - G1 (lệnh từ adb, chỉ để kiểm code): broadcast, work và TrampolineActivity đều lên FGS, đủ nhịp 3 phút, phát DONE.
  - 15:21 process app bị `BackupManagerService` giết để sao lưu đầy đủ (`FullBackupSession`, máy đang sạc và rảnh) dù
    đang có hai FGS. App spike đặt `android:allowBackup="false"`. Guzz thật cũng phải chặn (ghi ở I3 cho Stage 4 / 9).
  - Session bị ngắt giữa chừng: B4 và hàng đợi trên máy dừng; chạy lại (`sweep.py B4` bỏ qua JSON đã có;
    `run_bench.ps1` bỏ qua log đã có `ket_thuc`). Bench đang chạy trên máy vẫn chạy tiếp vì nằm trong FGS của app.
  - Người dùng hỏi chạy qua Wi-Fi: máy đã để adbd nghe TCP 5555, `adb connect 192.168.1.6:5555` được ngay, không đổi gì
    trên máy. Từ đây dùng `$env:ANDROID_SERIAL = '192.168.1.6:5555'`; `run_bench.ps1` tự connect lại khi rớt Wi-Fi và
    không tính rớt kết nối là app chết. Máy rút cáp được, nên D6 / G3 không cần cáp; đêm G3 thì `adb disconnect` để PC
    không đánh thức máy.
  - B4 xong: chọn CAM++ zh_en advanced (DER TB 8,2%) và ERes2Net base (9,9%) cho B5. B5 chạy `--jobs 3 --threads 5`.
  - D3: chọn 4 luồng. D4: nguyên bài 60 phút PSS 3,7 GB; 120 phút bị giết vì LOW_MEMORY sau 21 phút → D30 không đạt với
    nguyên bài ở shift 0,1. D5 đo thêm P = 15 và 20 phút ngoài P = 30 / 60 của plan (B8, D5), vì RAM phân cụm tăng
    theo bình phương độ dài phần; chọn P cuối cùng để ở I2. D5 chạy trước B5 / B8 với embedding của B4 và ngưỡng tạm
    0,5, vì ngưỡng gần như không đổi thời gian và RAM.
  - D7 cách 1: callback trả về 1 không dừng được, sherpa-onnx chạy tới hết.
  - B5, CAM++ zh_en, ngưỡng 0,3–0,7: mỗi bài ra 12–471 cụm, DER TB 41–89%, 0 bài đạt; ngưỡng 0,3 bài B1 hết RAM trên PC
    (`MemoryError`), các lượt khác RAM đỉnh tới 7,7 GB. Khoảng cách cosine nằm trong [0, 2], phân cụm complete-linkage
    đòi mọi cặp trong cụm gần nhau nên cần ngưỡng cao hơn nhiều. Bỏ phần ERes2Net ở 0,3–0,8; chạy CAM++ zh_en ở 0,8 / 0,9 /
    1,0 / 1,1 / 1,2 trước, rồi ERes2Net quanh vùng tốt nhất. Luật chọn ngưỡng của B5 giữ nguyên.
  - **RAM của sherpa-onnx phình theo số cụm** (ép 2–5 người ở B4: khoảng 1,1 GB trên PC; ngưỡng 0,3: 7,7 GB). Vậy 3,7 GB
    của D4 (60 phút, ngưỡng 0,5 → 100 cụm) chủ yếu do ngưỡng sai. D4 / D5 đo lại với ngưỡng của B5; bỏ hai lượt D5 P = 15
    / 30 ở ngưỡng 0,5.
  - D5 kiểm 1 đạt: `L1_10m`, P = 3 phút, O = 60 s, bản Kotlin trùng bản Python 136/136 lượt, cách nối từng nhãn giống hệt.
  - Người dùng bật "Gỡ lỗi không dây" → adb trên PC tự nối thêm kết nối TLS qua mDNS; chạy adb server với `ADB_MDNS=0`.
    Theo yêu cầu người dùng, adb chuyển sang địa chỉ Tailscale `100.71.61.23:5555` (`ANDROID_SERIAL`), bỏ đường LAN.
    Lưu ý cho G3 / D6: app Tailscale (VPN) chạy trên máy có thể ảnh hưởng Doze và pin; ghi lại lúc chạy đêm.
  - D5, `L1_120m`, P = 20 phút, ngưỡng tạm 0,5 (71 cụm): 22,9 phút, PSS 556 MB, đạt D30 ngay cả với số cụm quá nhiều.
  - D7 cách 3: giết process `:diarize` mất 54 ms, process chính sống, RAM trả lại.
- **2026-11-01 18:40, TẠM DỪNG theo yêu cầu người dùng (đi thi; người dùng báo khi nào chạy tiếp).** Đã tắt mọi job
  trên PC; trên máy gửi `bench stop` (lượt `dec_L1ogg_sinc` có thể chạy nốt rồi tự dừng). Khi chạy tiếp:
  1. Kết nối máy: `adb connect 100.71.61.23:5555` (Tailscale), `$env:ANDROID_SERIAL = '100.71.61.23:5555'`. Có hai kết
     nối thì `adb kill-server; $env:ADB_MDNS = '0'; adb start-server`. Máy khởi động lại thì phải cắm USB, người dùng chạy
     `adb tcpip 5555`. Đặt tạm `$env:JAVA_HOME`, `$env:ANDROID_HOME`, `$env:GUZZ_SPIKE_DATA` từ giá trị mức User.
  2. PC, B5: chạy lại `sweep.py B5 --shift 0.1 --embs campplus-zhen --thresholds 0.8 0.9 1.0 1.1 1.2 --jobs 3 --threads 5`
     (đã có 4/5 bài ở 0,8; ở 0,8 vẫn ra 27–110 cụm, có thể cần ngưỡng > 1,2). Thấy vùng tốt thì chạy ERes2Net quanh đó,
     rồi chọn ngưỡng theo luật B5. Sau đó B6, B7, B8 (thêm P = 15 / 20 ngoài 30 / 60).
  3. Máy: E3 còn `dec_L1ogg_sinc` (nếu chưa có `ket_thuc`), `dec_V1mp4_only/_sinc/_sonic`
     (`run_bench.ps1 -Batch spikes\android\batches\e3.txt`, bỏ qua lượt đã xong). Rồi E4 với `L1.m4a_{sinc,sonic}.f32`.
  4. Máy, sau B5: đo lại D4 (nguyên bài 60 / 120 phút) và D5 (`L1_120m`, `L2`, P đã chọn) với ngưỡng của B5; D7 cách 2.
  5. Việc chờ người dùng: G2 tạo 4 macro (hướng dẫn trong `spikes/README.md`, mục "Macro MacroDroid"), cài đặt S1, rồi đêm
     1 (G3 S1) và đêm 2 (S0); D6 + G4; G5 (`device_config`); F3 (hỏi lại trước khi gửi Gemini).
- **2026-11-10, rút gọn và đóng stage.** Người dùng không đủ thời gian cho phần đo còn lại (nhiều giờ trên PC, hai đêm rút
  cáp), muốn vừa làm vừa thử ở stage sau. Hỏi 4 câu trắc nghiệm, cả 4 chọn Recommended (D39–D42). Không chạy thêm phép đo
  nào trên PC hay trên máy.
  - Chép số E3 đã có trong `$D\out\phone\dec_*.jsonl` (m4a, aac, mp3, ogg) vào stage-01-results.md. Cả chuỗi giải mã +
    sinc + ghi tệp chậm hơn chỉ giải mã 1,4–4,2 lần: Stage 2 phải tối ưu resampler (ghi ở Phát sinh của Stage 2).
  - E2: `.\gradlew -p spikes\android :app:testDebugUnitTest` xanh (`SincResamplerTest`, `TurnsTest`).
  - Ngưỡng B5 chưa chốt: Stage 5 làm tiếp từ 0,8 trở lên (0,8 vẫn ra 27–110 cụm). JSON B5 đã có nằm ở `$D\out\pc\`;
    `sweep.py` bỏ qua JSON đã có.
  - Phần chuyển đi đã chép vào "Phát sinh" của Stage 2, 4, 5, 6, 8, 9.
  - J1: CLAUDE.md chỉ thêm một mục trỏ tới `spikes/README.md` (đã có đủ lệnh build, cài, đẩy dữ liệu, chạy bench), không
    chép lại từng lệnh.

## Phát sinh
