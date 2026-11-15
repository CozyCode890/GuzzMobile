# Stage 5 — Tách giọng trên máy

**Trạng thái:** 🔨 đang làm (từ 2026-11-14) · **Cần xong trước:** 4 (và kết quả Stage 1)

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
- [ ] Bài 2 giờ chia phần trên Xiaomi 15T: tách giọng ≤ 30 phút, PSS ≤ 1 GB, ≤ 45 °C, ≤ 10% pin (D30).

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-14, xem mục [Quyết định](#quyết-định): tên các cách → D71; min–max số người → D72; song song hay
tuần tự → D73; chọn model embedding → D74. Hỏi thêm lúc plan: PCM tạm → D75; giao diện → D76; gửi thật → D77; âm thanh
gốc / sạch, int8 → D78; lấy AAR và model → D79.

## Quyết định

Hỏi người dùng ngày 2026-11-14, người dùng chọn phương án Recommended ở cả 9 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D71 | Tên các cách nhận diện trên giao diện | **"Tách giọng trên máy + Prompt"**, **"Gemini tự nhận diện"**, **"Kết hợp (trên máy + Gemini)"**; giá trị cài đặt giữ `pyannote` / `gemini` / `hybrid` như desktop | Nói đúng việc gì chạy ở đâu; trên máy không còn pyannote đầy đủ. Giữ giá trị cài đặt để ràng buộc model ↔ cách và test của desktop dùng lại được. |
| D72 | Không biết số người (min–max của desktop) | **Phân cụm bằng ngưỡng chọn ở B1; số người ra ngoài [min, max] thì phân cụm lại với số cụm = min hoặc max** | Giữ cài đặt của desktop, tốn thêm tối đa một lần phân cụm cho mỗi phần. |
| D73 | Cách Kết hợp: tách giọng song song với gửi Gemini? | **Tuần tự như desktop** | Một đường chạy cho cả hai cách cần tách giọng; Dừng / làm tiếp và tiến độ đơn giản. Bài 2 giờ chậm hơn khoảng 20 phút. |
| D74 | Cho chọn model embedding | **Không: chỉ CAM++ zh_en advanced**, đóng gói trong APK | APK nhỏ hơn, ít thứ phải thử. ERes2Net chỉ hơn một bài mà chậm gấp 2,2 lần. |
| D75 | PCM tạm khi bật tách giọng (đổi D66) | **Giữ PCM tách giọng cần tới lúc có `nguoi_noi.<mã>.json`, rồi xoá ngay**; các đoạn lấy từ cache mà thiếu PCM thì giải mã lại (như `need_clean` của desktop) | Không giải mã hai lần trong lần chạy bình thường; chỗ trống tạm chỉ tăng trong lúc tách giọng. |
| D76 | Giao diện người nói ở stage này | **Trang Người nói rút gọn**: bật / tắt, chọn cách (khoá cách không hợp model), số người hoặc min–max, tên giảng viên / người hỏi, làm mượt, lưu bản đồ. Ngưỡng, mẫu đoạn văn, prompt → Stage 8 | Đủ để dùng hằng ngày; Stage 8 làm đủ các trang cài đặt. |
| D77 | Gửi thật lên Gemini | **B4 × 3 cách, tổng ≤ 15 yêu cầu**; hỏi người dùng ngay trước mỗi lần gửi | B4 sau cắt lặng 4 đoạn, 4 người nói, có kết quả desktop để so; 12 yêu cầu, dư 3 cho thử lại. |
| D78 | Âm thanh gốc hay sạch, segmentation fp32 hay int8 | **Chọn theo số đo trên PC (B1–B5)**: mặc định như desktop (sạch, fp32) trừ khi cách kia tốt hơn rõ: DER TB thấp hơn ≥ 1 điểm và không bài nào trượt thêm D29. Giữ cài đặt `run_on` | Không bỏ lỡ cách tốt hơn, mà không đổi mặc định vì chênh lệch nhỏ. |
| D79 | Lấy AAR sherpa-onnx 2.0.1 và model khi build | **Task Gradle tự tải** từ GitHub Releases của sherpa-onnx vào thư mục cache ngoài repo, **kiểm SHA-256**, sai thì dừng build | Repo nhẹ; clone mới chỉ cần mạng một lần; tệp tải về không đổi lén được. |
| D80 | Không ngưỡng nào đạt D29 ở ≥ 3/5 bài khi không biết số người (B1, hỏi ngày 2026-11-15 theo D39) | **Ngưỡng 1,1 + ưu tiên nhập số người**: không biết số người thì phân cụm bằng 1,1; trang Người nói khuyên nhập số người; biết số người thì chạy luật D72 với min = max = N | 1,1: 2/5 đạt, DER TB 17,9, giữ được người hỏi; 1,2–1,4: 2/5 đạt, DER TB 12,5–13,7 nhưng bài nhiều người gần như luôn ra 1 người. Biết số người (min = max = N): 3/5 đạt, DER TB 15,4. |
| D81 | Chia phần làm DER TB tăng 2,6 điểm so với nguyên bài (B4, vượt mức 2 điểm; hỏi ngày 2026-11-15) | **Giữ P = 20 phút** | Chia phần đạt D29 nhiều bài hơn (2/5 so với 1/5), nguyên bài ra thừa người; bài 2 giờ nguyên bài làm điện thoại hết RAM. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Module:
  - `:core:speaker` (JVM): port `speaker.py`, nối người nói giữa các phần (`PieceLinker`, `Hungarian` của app spike),
    chia phần, luật min–max (D72), định dạng JSON lượt nói. Test và golden chạy trên PC.
  - `:core:diarization` (Android library): bọc sherpa-onnx, service chạy trong process `:diarize`, model trong assets.
  - `:core:pipeline` gọi tách giọng qua interface `SpeakerDiarizer` (JVM), test bằng bản giả; `:feature:queue` cắm bản
    Android vào.
- Cấu hình sherpa-onnx như Stage 1: 2.0.1, segmentation-3.0, CAM++ zh_en advanced, `windowShiftRatio` 0,1,
  `minDurationOn` 0,2 / `minDurationOff` 0,5, 4 luồng, chia phần P = 20 phút, chồng lấn O = 60 s, nối người nói bằng
  ngưỡng của B1. Ngưỡng là hằng số trong code (Stage 8 mới đưa ra cài đặt, D76).
- Process `:diarize`: service bound, nói chuyện bằng `Messenger`; mỗi lần một phần. Kết quả từng phần ghi vào thư mục tạm
  `nguoi_noi.<mã>.phan_NN.json` (lượt nói + embedding trung bình từng nhãn, tối đa 30 s tiếng nói). Bấm Dừng thì giết
  process `:diarize` (Stage 1: 54 ms); phần đã xong dùng lại khi chạy tiếp. Ghép xong ghi `nguoi_noi.<mã>.json` cùng
  định dạng với `diarization_worker.py` (`bat_dau`, `ket_thuc`, `nguoi_noi`). Wake lock và FGS vẫn do `QueueService` của
  process chính giữ.
- Callback tiến độ của JNI là lớp viết rõ (lỗi `invoke(IIJ)` của lambda Kotlin, Stage 1); giá trị trả về bị bỏ qua.
- Mã cache `speaker_hash` như desktop; trường `diarization_model` là chuỗi gom model + ngưỡng + P / O + int8, nên đổi
  bất kỳ thứ nào cũng tách giọng lại.
- Model nằm trong assets, sherpa-onnx đọc qua `AssetManager`. APK debug giữ đủ 4 ABI (máy ảo `x86_64`); tách ABI ở Stage 9.
- Golden `speaker.py`: `tools/golden/gen_speaker.py` chạy chính `speaker.py` của desktop trên lượt nói tổng hợp (seed cố
  định), commit vào `testdata/golden/speaker/`. Lượt nói của bản ghi thật chỉ dùng trong test tự bỏ qua khi thiếu dữ liệu
  (như `RealRecordingGoldenTest` của Stage 2).
- `FakeTranscriber` (Gemini giả) trả thêm mốc từng từ và nhãn `spk_N` để thử cách Gemini tự nhận diện và Kết hợp không
  cần mạng.

## Rủi ro

- Không ngưỡng phân cụm nào đạt D29 khi không biết số người (Stage 1: 0,3–0,8 ra quá nhiều cụm) → B1 hỏi người
  dùng trước khi viết tiếp phần phụ thuộc ngưỡng.
- RAM của phân cụm phình theo số cụm: ngưỡng sai làm `:diarize` bị giết vì thiếu RAM → luật max của D72 chặn số cụm.
- Độ chính xác (README rủi ro 1). Nếu kém quá → điều chỉnh ngưỡng làm mượt, hoặc quay lại cân nhắc phương án tự ghép.

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Port hàm nào thì KDoc ghi nguồn (`Port của speaker.py:
merge_turns`). Test port từ desktop giữ tên ca kiểm tra để đối chiếu. Log giữ câu của desktop (viết có dấu).

Thứ tự: A → B (đo trên PC, chạy nền) cùng lúc với C (JVM) → D (sherpa-onnx trên máy) → E (pipeline) → F (service, giao
diện, thử trên máy ảo `guzz_15t`, D70) → G (Xiaomi 15T: đo bài 2 giờ, gửi thật) → H. Dữ liệu thật ở
`$env:GUZZ_SPIKE_DATA` (`D:\GuzzMobileData\stage-01`), script đo ở `spikes/diarization/` (venv của Stage 1).

### A. Khung

- [x] **A1. Tải AAR và model (D79):** task Gradle (trong `build-logic`, dùng ở `:core:diarization`) tải
  `sherpa-onnx-2.0.1.aar`, segmentation-3.0 (fp32 và int8) và `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`
  từ GitHub Releases của sherpa-onnx vào thư mục cache ngoài repo (mặc định `<Gradle user home>/guzz-deps`), kiểm SHA-256
  với số ghi trong repo (lấy từ `spikes/diarization/models.sha256` và số AAR đã kiểm ở Stage 1). Có sẵn và khớp thì không
  tải lại; sai thì dừng build, báo tên tệp và hai mã băm.
  **Kiểm:** xoá thư mục cache → `.\gradlew :core:diarization:assembleDebug` tải một lần; chạy lại không tải (log Gradle);
  sửa một chữ số SHA → build dừng với thông báo rõ; `git status` không có tệp tải về.
- [x] **A2. Module** `:core:speaker` (JVM) và `:core:diarization` (Android library) như "Mặc định kỹ thuật";
  `:core:pipeline` phụ thuộc `:core:speaker`, `:feature:queue` phụ thuộc `:core:diarization`.
  **Kiểm:** `.\gradlew projects` có hai module mới; `.\gradlew test assembleSideloadDebug assemblePlayDebug lint` xanh.
- [x] **A3. Cài đặt:** đủ mục `[SPEAKERS]` của desktop còn ý nghĩa trên máy (`num_speakers`, `min_speakers`,
  `max_speakers`, `run_on`, `smooth`, `sandwich_*`, `density_*`, `merge_gap_sec`, tên người nói, `map_delivery`,
  `prompt_file` → prompt mặc định, `timestamps`, `paragraph_template`, `max_paragraph_sec`, `paragraph_gap_sec`,
  `mark_review`) và `[OUTPUT] save_speaker_map`. Bỏ `python_path`, `pyannote_model`, `model_dir`, `offline`, `device`,
  `timeout_minutes` (ghi lý do trong KDoc). Chặn giá trị như `load_config`.
  **Kiểm:** `DesktopDefaultsTest` xanh với mục mới; mục khác desktop có `sameAsDesktop = false` và lý do.

### B. Đo trên PC (D72, D78; chạy nền, JSON đã có thì bỏ qua)

- [x] **B1. Ngưỡng phân cụm** (B5 cũ của Stage 1), chạy **giống trên máy**: chia phần P = 20 phút, O = 60 s, CAM++
  zh_en, shift 0,1, ngưỡng ∈ {0,8; 0,9; 1,0; 1,1; 1,2; 1,3; 1,4} cho cả phân cụm trong phần lẫn nối người nói, trên B1–B5
  (`sweep.py`, thêm tuỳ chọn chia phần nếu chưa có).
  **Kiểm:** bảng theo ngưỡng (DER, số người, % người chính, sau `process_turns`) trong stage-01-results.md §1. Chọn
  **một** ngưỡng theo luật của B5 cũ: nhiều bài đạt D29 nhất, hoà thì DER TB thấp hơn. Không ngưỡng nào có ≥ 3/5 bài đạt
  → **hỏi người dùng** (trắc nghiệm, D39): bắt nhập số người, đổi cách phân cụm, hay đổi hướng.
- [x] **B2. Luật min–max (D72)** với ngưỡng của B1, min 1 / max 6 như desktop: viết luật trong `run_sherpa.py` (bản
  Python để chấm), rồi chấm B1–B5. Luật: phần nào ra > max cụm thì phân cụm lại phần đó với `num_clusters` = max; sau khi
  nối, tổng > max thì gộp người nói ít thời gian nhất vào người gần nhất (cosine) tới khi còn max; tổng < min thì phân cụm
  lại mọi phần với `num_clusters` = min.
  **Kiểm:** bảng DER có / không có luật; không bài nào xấu đi quá 1 điểm. Xấu hơn thì ghi Nhật ký và hỏi người dùng.
- [x] **B3. int8 và âm thanh gốc (B6, B7 cũ; D78)** với cấu hình B1–B2: segmentation int8 so với fp32; `goc16k.wav` so với
  `sach.flac` (chấm bằng `evaluate.py --remap`).
  **Kiểm:** hai bảng trong stage-01-results.md; chọn theo luật D78, ghi vào "Mặc định kỹ thuật" nếu khác desktop.
- [x] **B4. Chia phần so với nguyên bài (B8 cũ)** với cấu hình cuối: DER của P = 20 so với nguyên bài trên B1–B5.
  **Kiểm:** bảng trong stage-01-results.md; DER TB tăng ≤ 2 điểm, không thì ghi Nhật ký và hỏi người dùng (đổi P).

### C. `:core:speaker` (JVM, chạy hết trên PC)

- [x] **C1. Port `speaker.py`** đủ các hàm ở mục Phạm vi (kể cả `smooth`, `rename_speakers`, `speakers_in_chunk`,
  `hh_mm_ss`) và 11 ca của `tests/test_speaker.py`.
  **Kiểm:** 11 ca port xanh, tên ca giữ như desktop.
- [x] **C2. Golden `speaker.py`:** `tools/golden/gen_speaker.py` sinh lượt nói tổng hợp (seed cố định: nhiều người, lượt
  kẹp giữa, người phụ thưa, lượt chạm biên đoạn, mốc từng từ có / không có nhãn) và ghi kết quả của `process_turns`,
  `build_name_map`, `minutes_per_speaker`, `split_turns_by_chunk`, `speaker_map_text`, `parse_prompt_paragraphs`,
  `assign_speakers` + `smooth` + `merge_spans`, `format_paragraph` vào `testdata/golden/speaker/`. Thêm test bản ghi
  thật: JSON lượt nói của Stage 1 (B1–B5) qua `process_turns` + `build_name_map`, thiếu dữ liệu thì bỏ qua.
  **Kiểm:** `.\gradlew :core:speaker:test` xanh; chạy lại `gen_speaker.py` không đổi `git diff testdata/golden/speaker`;
  test bản ghi thật chạy (không bỏ qua) trên PC này.
- [x] **C3. Chia phần và nối người nói:** port `PieceLinker`, `Hungarian` từ app spike (thay `org.json` bằng
  kotlinx.serialization), hàm chia phần (P, O → danh sách [a, b)), luật min–max của B2.
  **Kiểm:** golden so với `run_sherpa.py` (`link_pieces` và luật min–max) trên JSON từng phần của B1–B5 lấy từ B2, khớp
  từng lượt (±1 ms) và nhãn; thiếu dữ liệu thì bỏ qua. Ca tổng hợp cho biên: phần cuối ngắn, phần không ai nói, một phần.

### D. `:core:diarization` (sherpa-onnx trên máy)

- [x] **D1. Tách giọng một phần:** đọc PCM 16 kHz của phần [a, b) bằng `Pcm16File`, chạy `OfflineSpeakerDiarization`
  (cấu hình "Mặc định kỹ thuật" + B3), tính embedding trung bình từng nhãn (≤ 30 s tiếng nói), báo tiến độ qua lớp
  callback viết rõ, trả lượt nói theo giây toàn bài.
  **Kiểm:** test thiết bị trên máy ảo với một bài thử công khai của sherpa-onnx (tải bằng task của A1, không commit): JSON
  trùng `run_sherpa.py` trên PC cùng tệp (mốc ±2 ms, cùng nhãn), như D2 của Stage 1.
- [x] **D2. Process `:diarize`:** service bound trong process riêng, `Messenger`: yêu cầu (đường PCM, các phần, cấu hình,
  thư mục ra), tiến độ, xong / lỗi. Ghi `nguoi_noi.<mã>.phan_NN.json` từng phần (ghi `.tmp` rồi đổi tên); phần đã có
  thì bỏ qua. Process chính mất liên lạc (process `:diarize` chết) thì báo lỗi rõ, không treo.
  **Kiểm:** test thiết bị: chạy 3 phần, giết `:diarize` giữa phần 2 → process chính sống, phần 1 còn tệp; chạy lại chỉ
  làm phần 2–3; `logcat -b crash` trống.
- [x] **D3. Bản Android của `SpeakerDiarizer`:** chia phần, gọi `:diarize`, nối người nói (C3), ghi
  `nguoi_noi.<mã>.json`; Dừng (huỷ coroutine) thì giết `:diarize` và trả về ngay.
  **Kiểm:** test thiết bị: huỷ giữa chừng → `:diarize` mất trong ≤ 1 s, coroutine kết thúc; chạy lại dùng phần đã có.

### E. Pipeline (`:core:pipeline`, JVM, `SpeakerDiarizer` giả)

- [x] **E1. Cấu hình và cache:** đọc cài đặt người nói vào `PipelineConfig`; port `speaker_hash`; luật giữ PCM (D75):
  bật tách giọng trên máy mà chưa có `nguoi_noi.<mã>.json` thì giữ `sach.pcm` hoặc `goc.pcm` (theo `run_on`) tới lúc có
  kết quả rồi xoá; các đoạn lấy từ cache mà thiếu PCM thì giải mã lại.
  **Kiểm:** golden `speaker_hash` so với desktop (thêm vào `gen_pipeline.py`); test: chạy xong thư mục tạm không còn PCM;
  dừng giữa lúc tách giọng → PCM còn; chạy lại khi đã có `nguoi_noi.<mã>.json` thì không giữ PCM.
- [x] **E2. Bước tách giọng:** port `diarize_speakers` và phần người nói của `transcribe_single_file` (`remap_timestamps`
  khi chạy trên âm thanh gốc, `process_turns`, `build_name_map`, log "Bản đồ người nói", "Dùng lại kết quả nhận diện
  người nói đã có"); bỏ chặn "chưa có trên điện thoại" của Stage 4; tiến độ có bước tách giọng như `ProgressTracker`
  desktop khi `has_pyannote`.
  **Kiểm:** test với bản giả: thứ tự gọi, log, tiến độ tăng dần; lỗi của bản giả → tệp lỗi, không dừng cả hàng đợi.
- [x] **E3. Chép lời có người nói:** phần người nói của `transcribe_chunks`: bản đồ từng đoạn, prompt có `{speakers}`
  (bản Kotlin của `prompt_diarization.txt`), `TIMESTAMP_INSTRUCTIONS`, gửi bản đồ bằng tệp hoặc chữ, `diarize` /
  `word_timestamps` cho model `*-transcribe`, tên tệp kết quả theo `_hash(shared_key, map_text)`, thử lại khi rỗng theo
  `has_speech(map_text)`, cảnh báo bỏ từ vựng / chế độ smart.
  **Kiểm:** port 4 ca của `test_transcribe.py` (`test_pyannote_sends_map_and_maps_times_to_original`,
  `test_pyannote_inlines_map_when_text_delivery`, `test_gemini_names_speakers_per_chunk`,
  `test_hybrid_assigns_names_from_pyannote`) với Gemini giả; golden tên tệp kết quả so với desktop.
- [x] **E4. Ghép:** `assemble_transcript` đủ 3 cách, phút nói trong đầu bản chép lời, `full_speaker_map` →
  `<tên>.nguoi_noi.txt` khi `save_speaker_map` (qua `OutputFolder`, cùng luật trùng tên).
  **Kiểm:** golden: desktop `assemble_transcript` + `format_header` trên cùng kết quả đoạn và lượt nói (3 cách, có / không
  mốc thời gian, `mark_chunks`) khớp bản Kotlin từng ký tự.
- [x] **E5. Ràng buộc model ↔ cách:** port `closest_alternative_method` (và phần tự chuyển của `gui/model_ui.py`): đổi
  model thì tự chuyển cách nhận diện, trả câu báo cho giao diện.
  **Kiểm:** test: mọi cặp model ↔ cách của desktop ra cùng kết quả (golden trong `gen_pipeline.py`).

### F. Service và giao diện (thử trên máy ảo `guzz_15t`, Gemini giả)

- [x] **F1. Nối vào hàng đợi:** `QueueController` dùng bản Android của `SpeakerDiarizer`; tên bước "nhận diện người
  nói" trên thông báo và hàng đợi; Dừng lúc đang tách giọng giết `:diarize`; `FakeTranscriber` trả mốc từng từ và nhãn
  `spk_N`; `queue_debug.ps1` in thêm bước tách giọng.
  **Kiểm:** trên máy ảo, `L1_20m.m4a`, cả 3 cách bằng Gemini giả → bản chép lời có tên người nói, `.nguoi_noi.txt` khi
  bật lưu bản đồ; Dừng lúc đang tách giọng → `dung`, Bắt đầu → log "dùng lại" cho phần đã xong; `-Kill` lúc đang tách
  giọng → mở app làm tiếp; `logcat -b crash` trống.
- [x] **F2. Trang Người nói rút gọn (D76, D71):** mở từ màn hình Hàng đợi; các mục của D76; cách không hợp model bị khoá
  kèm lý do; đổi model ở chip của Hàng đợi thì tự chuyển cách và báo (E5). vi / en trong `strings.xml`.
  **Kiểm:** trên máy ảo: đổi từng mục, quay lại thấy giữ nguyên; chọn model `*-transcribe` → cách "Tách giọng trên máy +
  Prompt" bị khoá và cách đang chọn tự đổi, có thông báo; `.\gradlew lint` xanh.

### G. Trên Xiaomi 15T

- [ ] **G1. Bài 2 giờ chia phần** (đo lại D5 của Stage 1 với ngưỡng thật): `L1_120m` và `L2` (5 người), Gemini giả,
  rút cáp, tắt màn hình. Ghi thời gian tách giọng, PSS đỉnh của `:diarize`, % pin, nhiệt đỉnh, và thời gian từ lúc bấm
  Dừng tới lúc `:diarize` mất (D7 cách 2 của Stage 1).
  **Kiểm:** bảng trong stage-01-results.md §2: ≤ 30 phút, PSS ≤ 1 GB, ≤ 45 °C, ≤ 10% pin (D30); Dừng ≤ 1 s. Không đạt thì
  ghi Nhật ký và hỏi người dùng (đổi P, số luồng).
- [ ] **G2. Gửi thật B4 × 3 cách (D77; hỏi người dùng ngay trước mỗi lần gửi):** cách "Tách giọng trên máy + Prompt" với
  `gemini-3.5-flash-lite`, hai cách kia với `gemini-3.5-transcribe`.
  **Kiểm:** 3 bản chép lời có tên người nói; số người và phút nói của giảng viên so với `ref.json` của desktop (D29: lệch
  ≤ 1 người, ≤ 5 điểm %); tổng ≤ 15 yêu cầu (log và sổ hạn mức).

### H. Đóng stage

- [x] **H1. Tài liệu:** CLAUDE.md thêm lệnh (test `:core:speaker`, `gen_speaker.py`, test thiết bị `:core:diarization`,
  cache AAR / model); README §3–4 sửa nếu module khác dự kiến; ghi giấy phép model (MIT / Apache-2.0) vào chỗ Stage 9 sẽ
  dùng.
  **Kiểm:** chạy lại từng lệnh đã chép.
- [ ] **H2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 5 → ✅; "Việc tiếp theo" → stage kế (6 hoặc 7, hỏi người
  dùng); commit, push.
  **Kiểm:** `git status -sb` báo `## main...origin/main`; `git grep -E "AIza[0-9A-Za-z_-]{30,}"` không ra gì; repo không
  có bản ghi thật, AAR hay model.

## Nhật ký

- **2026-11-14, plan.** Đọc `speaker.py`, phần người nói của `transcribe.py` (`speaker_hash`, `diarize_speakers`,
  `transcribe_chunks`, `assemble_transcript`, `transcribe_single_file`), mục `[SPEAKERS]` của `config.default.txt`,
  `prompt_diarization.txt`, `tests/test_speaker.py` (11 ca), 4 ca người nói của `tests/test_transcribe.py`, kết quả
  Stage 1 và code spike (`PieceLinker`, `run_sherpa.py`, `sweep.py`). Client Gemini của Stage 3 đã có sẵn gửi bản đồ
  (tệp / chữ), `diarize`, `word_timestamps`, `extractWords`. Hỏi người dùng 9 câu trắc nghiệm, cả 9 chọn Recommended
  (D71–D79). Các mục Phát sinh từ Stage 1, 2, 4 đã vào plan: ngưỡng, int8, âm thanh gốc, chia phần → B1–B4; đo trên
  máy → G1; lưu ý JNI và dừng → "Mặc định kỹ thuật", D2–D3; PCM tạm → D75, E1.
- **2026-11-14/15, A–C.**
  - A1: plugin settings `guzz.deps` (module riêng `build-logic/deps`, vì plugin settings nạp ở classloader không có AGP)
    đọc `gradle/guzz-deps.txt`, tải vào `<Gradle user home>/guzz-deps`, kiểm SHA-256, dựng repo Maven cục bộ
    `com.k2fsa.sherpa-onnx:sherpa-onnx:2.0.1` (AGP không cho module thư viện phụ thuộc thẳng tệp `.aar`). Việc kiểm chạy
    trong một `ValueSource` nên configuration cache vẫn kiểm lại. Đã kiểm: xoá cache → tải một lần; chạy lại không tải;
    sửa một chữ số SHA → build dừng, in hai mã băm. Model vào assets qua task `copyDiarizationModels`.
  - Interface `SpeakerDiarizer` (và `ChunkedDiarization`: chia phần, cache `phan_NN.json`, luật min–max) nằm ở
    `:core:speaker` thay vì `:core:pipeline`, để `:core:diarization` không phụ thuộc pipeline; pipeline vẫn chỉ thấy
    interface JVM.
  - B1 (bảng ở stage-01-results.md §1): không ngưỡng nào ≥ 3/5 bài → hỏi người dùng (D80). Thử thêm ngưỡng nối giữa các
    phần khác ngưỡng trong phần (`spikes/diarization/relink.py`, tính lại từ `.pieces.json`): 1,1 / 1,2 / 1,3 cho kết
    quả y hệt, chênh lệch nằm ở phân cụm trong phần.
  - B2: luật min 1 / max 6 không đổi DER bài nào quá 0,1 điểm (chỉ B3 có một phần > 6 cụm). Biết số người thì luật D72
    với min = max = N (cách hiểu `num_speakers` > 0 trên mobile). Phân cụm lại một phần với sherpa-onnx là chạy lại cả
    phần (segmentation + embedding), không chỉ phân cụm: API không cho lấy embedding ra; chỉ tốn khi luật kích hoạt.
  - Spike: `linking.py` (nối người nói, luật max, bỏ chồng lấn; chỉ cần numpy + scipy) để golden chạy bằng Python của
    desktop; `run_sherpa.py` thêm `--min-speakers / --max-speakers / --pieces-in / --pieces-out`, `sweep.py` thêm
    S5B1–S5B4.
- **2026-11-15, D–E.**
  - D1–D3 đạt trên máy ảo `guzz_15t` (`DiarizationDeviceTest`, tệp thử 4 người nói 57 s): sherpa-onnx trên máy trùng
    `run_sherpa.py` trên PC (ngưỡng 1,1 và số cụm 4; kết quả PC ở `testdata/golden/diarization/`); giết `:diarize`
    giữa phần 2 → `DiarizeProcessDiedException`, phần 1 còn tệp, chạy lại chỉ làm phần 2–4 (P = 20 s, O = 2 s ra 4
    phần, không phải 3); huỷ coroutine → `:diarize` mất trong ≤ 1 s; `logcat -b crash` trống. Lỗi gặp và sửa:
    linkToDeath và `onServiceDisconnected` cùng báo process chết trên hai luồng → resume hai lần, nay có cờ chỉ resume
    một lần.
  - Process `:diarize` ghi phần vừa xong ra tệp tạm, process chính đọc rồi ghi `phan_NN[.kN].json` (không gửi
    embedding qua binder). Phần chạy lại với số cụm cố định (luật min–max) có tệp riêng `.kN`.
  - E: thiếu PCM khi có đoạn trong cache thì xử lý âm thanh lại cả bài (như `need_clean` của desktop), không giải mã
    riêng cho tách giọng. Prompt người nói (`prompt_diarization.txt`) đóng gói trong `:core:gemini` cạnh `prompt.txt`.
    Bản đồ người nói cả bài `<tên>.nguoi_noi.txt` ghi cạnh bản chép lời, cùng tên gốc, có thì ghi đè như desktop.
- **2026-11-15, F (máy ảo `guzz_15t`, Gemini giả).**
  - F1: `L1_20m.m4a` cả 3 cách (Tách giọng trên máy + Prompt với `gemini-3.5-flash-lite`, hai cách kia với
    `gemini-3.5-transcribe`) ra bản chép lời có tên; bật lưu bản đồ thì có `<tên>.nguoi_noi.txt`. Tách giọng 13 phút âm
    thanh sạch mất 85–89 s trên máy ảo. `L1_120m.m4a` (5 phần), cách Kết hợp: Dừng lúc đang tách giọng → `dung`,
    `:diarize` mất ngay; Bắt đầu → "Dùng lại phần 1/5, 2/5 đã tách giọng"; `-Kill` lúc đang tách giọng → service tự khởi
    động lại, "Dùng lại phần 1/5 … 3/5", chạy xong (2 người, 408 s tách giọng cho các phần còn lại). `logcat -b crash`
    trống.
  - Lỗi tìm ra trên máy, sửa: regex ICU của Android không nhận cờ `(?U)` (PatternSyntaxException khi nạp `Speakers`),
    nay viết rõ `\p{Nd}`; tiến độ tách giọng tính theo số phần chạy trong lần này nên làm tiếp thì về 0, nay tính theo
    chỉ số phần và không cho lùi.
  - Thêm lệnh debug `queue_debug.ps1 -Speakers <cách|off> -Model … -Num … -SaveMap` (receiver `cmd=speakers`).
  - F2: trang Người nói (menu ⋮ và dòng "Người nói: …" ở màn hình Hàng đợi) kiểm bằng uiautomator: cách không hợp model
    bị khoá kèm lý do; đổi cách và số người, quay lại rồi mở lại vẫn giữ; bấm chip `gemini-3.5-flash-lite` → tự chuyển
    sang "Tách giọng trên máy + Prompt" và hiện câu báo. Lint 0 lỗi.
- **2026-11-15, G.** Điện thoại không nối được qua Tailscale (`adb connect` hết giờ); người dùng chọn để G1–G2 lại sau.
- **2026-11-15, B3–B4.** Bảng ở stage-01-results.md §1. int8 (DER TB 21,4) và âm thanh gốc (20,2) đều kém hơn sạch +
  fp32 (17,9) → giữ mặc định desktop (D78), không đổi "Mặc định kỹ thuật". Nguyên bài 15,3 (chênh 2,6 điểm > 2) → hỏi
  người dùng, chọn giữ P = 20 phút (D81).

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
- (Từ plan Stage 4, 2026-11-11) D66: pipeline của Stage 4 xoá `goc.pcm` / `sach.pcm` ngay sau khi cắt đoạn (thư mục tạm
  chỉ còn `doan_*.flac` + `danh_sach_doan.json`). Khi bật tách giọng, phải giữ PCM cần cho tách giọng tới lúc có
  `nguoi_noi.<mã>.json` (như `need_clean` của desktop), hoặc giải mã lại khi các đoạn lấy từ cache; mã băm người nói theo
  `speaker_hash` của desktop. Hỏi người dùng nếu đổi chính sách dọn của D66.
