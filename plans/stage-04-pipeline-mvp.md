# Stage 4 — Pipeline + hàng đợi nền + UI tối thiểu (MVP)

**Trạng thái:** 🔨 đang làm (từ 2026-11-11; plan chi tiết 2026-11-11) · **Cần xong trước:** 2, 3

## Mục tiêu

**Bản đầu tiên dùng được hằng ngày:** chọn bài giảng 1–2 giờ → ra .txt; chạy được khi tắt màn hình; dừng rồi làm tiếp thì
không gửi lại các đoạn đã chép xong. Bản này chưa có nhận diện người nói.

## Phạm vi

### Làm

- `:core:pipeline`, port từ `transcribe.py`:
  - `transcribe_single_file` (chưa có bước người nói), `transcribe_chunks`;
  - mã băm cache (`audio_hash`, kết quả từng đoạn) để làm tiếp; thư mục tạm riêng cho mỗi tệp, không nằm trong
    `cacheDir` vì hệ thống có thể xoá thư mục đó;
  - `assemble_transcript`, `format_header`, mẫu tên tệp (`{name}` `{recorded}` `{date}` `{time}` `{model}`), xử lý khi
    trùng tên, kiểu xuống dòng, encoding;
  - lỗi theo loại: dừng cả hàng đợi (key sai, ràng buộc model…) hay làm tiếp tệp sau;
  - logic hàng đợi (port `gui/threads.py: TranscribeThread`), lưu bằng tệp JSON (D63).
- `:feature:queue`:
  - foreground service (Android 15+: loại `mediaProcessing`; bản cũ hơn: `dataSync`), thông báo tiến độ có nút Dừng;
  - tiến trình bị giết thì tự làm tiếp từ đoạn đang dở (D64).
- Lưu kết quả vào thư mục người dùng chọn một lần qua SAF (D59).
- UI:
  - màn hình Hàng đợi: thêm tệp bằng trình chọn tệp, tiến độ từng tệp, mở bản chép lời, Chạy lại, Bỏ khỏi danh sách;
  - cài đặt nhanh: model, cắt khoảng lặng, thư mục lưu;
  - xem bản chép lời trong app, mở bằng app khác (D61), chia sẻ tệp .txt (D62).

### Không làm

- Nhận diện người nói (Stage 5). Intent, Chia sẻ tới Guzz, lưu cạnh tệp nguồn (Stage 6). Làm sạch đầy đủ (Stage 7).
- `[VIDEO] save_extracted` (D68) và giao diện cho các mục `[OUTPUT]` / `[QUEUE]` còn lại (Stage 8). Đo tốc độ trên bản
  release (Stage 9).

## Nguồn tham khảo trong Guzz desktop

- `transcribe.py` (toàn bộ, trừ phần người nói)
- `gui/pages/transcribe.py`, `gui/threads.py`: hành vi hàng đợi, Dừng / Bắt đầu lại
- `tests/test_transcribe.py`
- `config.py`: `output_filename`, `output_folder`, `sanitize_filename`, các mục `[OUTPUT]`, `[QUEUE]`, `[SYSTEM]`
- README desktop §4, §9

## Kết quả bàn giao

- APK dùng thật được từ đầu đến cuối.

## Tiêu chí hoàn thành

- [ ] Test pipeline trên âm thanh tổng hợp với Gemini giả (16 ca port từ `test_transcribe.py`, bước C2–C3); golden B1–B3
  khớp desktop.
- [ ] Chạy thật bài B5 (84,5 phút) trên máy thật, tắt màn hình suốt lúc chạy; service sống liền ≥ 60 phút (D67), nhịp
  log không hở quá 2 phút, không bị giết.
- [ ] Bấm Dừng giữa chừng rồi Bắt đầu lại: các đoạn đã chép không bị gửi lại (xem trong log).
- [ ] Giết app giữa chừng: tự làm tiếp (D64), các đoạn đã chép không bị gửi lại.
- [ ] `onTimeout` (Phát sinh từ Stage 1): service dừng trong ≤ 1 s, không crash, mở app thì làm tiếp.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-11, xem mục [Quyết định](#quyết-định): nơi lưu → D59; lần lượt hay song song → D60; xem bản chép
lời → D61; chia sẻ → D62. Hỏi thêm lúc plan: lưu hàng đợi → D63; bị giết giữa chừng → D64; gửi thật → D65; dọn thư mục
tạm → D66; chạy ≥ 60 phút → D67; `save_extracted` → D68.

## Quyết định

Hỏi người dùng ngày 2026-11-11. Người dùng chọn phương án Recommended ở cả 10 câu. D69–D70 hỏi thêm lúc làm (2026-11-12,
2026-11-13), cũng chọn Recommended. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D59 | Lưu bản chép lời mặc định ở đâu | **Thư mục người dùng chọn một lần qua SAF** (`OpenDocumentTree`, gợi ý tạo `Documents/Guzz`); chưa chọn thì app hỏi lúc bấm Bắt đầu. Lưu cạnh tệp nguồn → Stage 6 | Một cách ghi cho cả `sideload` lẫn `play`, không cần quyền đặc biệt; liệt kê được thư mục nên đánh số / ghi đè / bỏ qua khi trùng tên đúng như desktop. Cạnh tệp nguồn cần quyền "Truy cập mọi tệp" và đường dẫn tuyệt đối, là phần của Stage 6. |
| D60 | Chạy lần lượt hay song song | **Lần lượt từng tệp, như desktop** | Hạn mức theo phút dùng chung; RAM, pin, chỗ trống tạm thấp; làm tiếp sau khi bị giết đơn giản. |
| D61 | Xem bản chép lời | **Màn hình xem trong app** (chỉ đọc, chọn và chép được chữ) **+ nút Mở bằng app khác** | Xem nhanh không cần app khác; muốn sửa thì dùng trình soạn thảo trên máy. |
| D62 | Nút chia sẻ | **Có, chia sẻ tệp .txt** (`ACTION_SEND` kèm URI) | Gửi Zalo / Drive / email như tệp đính kèm; muốn dán chữ thì chọn và chép ở màn hình xem. |
| D63 | Lưu hàng đợi | **Tệp JSON** (`files/queue/hang_doi.json`, ghi `.tmp` rồi đổi tên), thay cho Room mà README dự kiến | Danh sách nhỏ (vài chục tệp); logic hàng đợi ở module JVM, test trên PC; không thêm KSP / sinh mã (cùng tinh thần D13, D52). |
| D64 | Bị hệ thống giết giữa chừng | **Tự làm tiếp**: hàng đợi đang chạy dở thì tự chạy lại khi mở app và khi hệ thống khởi động lại service (`START_STICKY`, nếu Android cho); đã bấm Dừng thì không tự chạy | Bài chạy đêm bị giết thì sáng ra vẫn xong; cache từng đoạn nên không gửi lại. |
| D65 | Gửi thật lên Gemini | **B5 + B4, tổng ≤ 15 yêu cầu**, model mặc định `gemini-3.5-flash-lite`; vẫn hỏi người dùng ngay trước mỗi lần gửi | B5 (84,5 phút, khoảng 6 đoạn) cho phép thử chạy liền lúc tắt màn hình; B4 (78,9 phút, khoảng 5 đoạn) cho Dừng / Bắt đầu lại và giết app. |
| D66 | Dọn thư mục tạm | **Cắt đoạn xong thì xoá ngay `goc.pcm` / `sach.pcm`; tệp xong thì xoá cả thư mục tạm; tệp lỗi / đã dừng giữ để làm tiếp, xoá khi bỏ khỏi danh sách** (`SYSTEM.delete_temp_when_done` mặc định `true`) | Bài 2 giờ chiếm khoảng 200 MB thay vì 660 MB trong lúc gửi; Stage 4 không cần PCM nữa. Câu hỏi ghi "như desktop", nhưng `config.default.txt` của desktop đặt `delete_temp_when_done = false` (dataclass mới mặc định `true`): mobile chọn xoá, đánh `sameAsDesktop = false`. Stage 5 cần âm thanh cho tách giọng thì tự quyết lại. |
| D67 | Tiêu chí service sống liền ≥ 60 phút (Stage 1, D42) trong khi B5 chỉ chạy 10–15 phút | **Bản debug: lệnh adb thêm tệp có tham số chờ giữa các đoạn** (khoảng 10 phút), không phải cài đặt | Vẫn 6 yêu cầu mà service sống ≥ 60 phút; mỗi lần gửi sau một quãng chờ thử luôn mạng trong Doze. |
| D68 | `[VIDEO] save_extracted` (D47 để cho stage này) | **Để Stage 8** | Cần công tắc trên giao diện, và thử trên máy cần gửi thêm một bài video. Vì D66 xoá `goc.pcm` sớm, Stage 8 phải mã hoá ra FLAC ngay sau khi cắt đoạn (hoặc giải mã lại). |
| D69 | Android 12+ không cho receiver ở nền khởi động foreground service khi app chưa được miễn tối ưu pin ("No restrictions" của HyperOS không đưa app vào danh sách `deviceidle whitelist`) | **Người dùng tự miễn tối ưu pin cho bản debug** (`adb shell dumpsys deviceidle whitelist +io.github.cozycode890.guzz.debug`, hoàn tác `… -io.github.cozycode890.guzz.debug`); lệnh `start` của adb giữ qua receiver | Thử được lúc máy khoá và `START_STICKY` đúng điều kiện thật; MacroDroid (Stage 6) cũng cần bước này → Phát sinh Stage 6. |
| D70 | Thử D–E trên đâu | **Máy ảo `guzz_15t`** (Android 16 / API 36, `google_apis` x86_64, 1280×2772, 520 dpi, RAM 4 GB, như Xiaomi 15T); điện thoại thật chỉ ở đợt thử cuối (F và các mục chỉ máy thật mới kiểm được) | Thử trên điện thoại bất tiện (phải nối adb, mang máy theo); máy ảo cùng bản Android nên hạn chế FGS và `onTimeout` sát thật. HyperOS (giết app nền, tắt wake lock) chỉ kiểm được trên máy thật. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- **Module.** `:core:pipeline` (JVM thuần, `guzz.jvm.library`; phụ thuộc `:core:common`, `:core:audio`, `:core:gemini`):
  mã băm cache, chuẩn bị đoạn có cache, chép lời từng đoạn, ghép, đặt tên, một tệp; cả mô hình / lưu / chạy hàng đợi.
  Nhận interface: `ChunkSource` (chuẩn bị đoạn; trên máy bọc `AudioPreparation`, test thì đọc WAV tổng hợp rồi chạy
  `ChunkPreparer` với bộ ghi WAV), `OutputFolder` (liệt kê tên, ghi, mở; trên máy là SAF, test và lệnh adb của bản debug
  dùng thư mục thường), `ChunkTranscriber` (của `:core:gemini`). `:feature:queue` (Android library): `QueueService`,
  thông báo, wake lock, các bản Android của interface trên, `QueueController` (thêm, Bắt đầu, Dừng, Bỏ, Chạy lại;
  `StateFlow` cho giao diện; Stage 6 gọi vào đây). Màn hình nằm ở `:app`.
- **Dừng** = huỷ coroutine như Stage 3; bước âm thanh dừng qua `checkCancelled`.
- **Cache giữ tên tệp và khoá JSON của desktop**: `danh_sach_doan.json` thêm khoá `ma` (ghi trong cùng lần ghi cuối của
  `ChunkPreparer`), kết quả đoạn `doan_NNN.<mã>.json` với `van_ban`, `tu`, `model`. `_hash` port đúng `json.dumps(…,
  ensure_ascii=False, sort_keys=True, default=str)` → SHA-1 → 10 ký tự đầu (golden). Vân tay: đường dẫn →
  `đường dẫn|cỡ|mtime giây`; content:// → `uri|cỡ|last_modified giây` (provider không cho thì 0). `audio_hash` của mobile:
  vân tay + các tham số mobile có (16 kHz, `target_dbfs`, `trim_silence`, `min_silence_len`, `silence_thresh_offset`,
  `keep_silence`, `chunk_minutes`, `search_seconds`, `min_last_chunk_seconds`, `format`) + luồng âm thanh đã chọn nếu là
  video. Không cần trùng mã với desktop (không dùng chung thư mục tạm), chỉ cần cùng cách băm; Stage 7 thêm tham số DSP.
- **Thư mục tạm:** `getExternalFilesDir(null)/tmp/<tên>_<mã 8 ký tự>` (thiếu bộ nhớ ngoài thì `filesDir/tmp`). Hệ thống
  không tự xoá (khác `cacheDir`), và `adb pull` xem được cache lúc thử, như log (D15).
- **Tệp vào:** trình chọn tệp `OpenMultipleDocuments` (`audio/*`, `video/*`) + `takePersistableUriPermission` để đọc lại
  được sau khi app bị giết (D64); trả quyền khi bỏ khỏi danh sách và không còn mục nào dùng URI đó. Đường dẫn tuyệt đối
  (lệnh adb, Stage 6) đi qua `MediaInput.Path` như Stage 2. Thêm trùng một tệp đang chờ thì bỏ qua.
- **Video hay âm thanh** (dòng đầu của phần đầu bản chép lời): theo MIME `video/*` của provider, không có thì theo đuôi
  như `[VIDEO] extensions` mặc định của desktop.
- **`{recorded}`:** mtime (đường dẫn) hoặc `COLUMN_LAST_MODIFIED` (URI), không có thì giờ hiện tại. Desktop dùng
  `getctime` (trên Windows là lúc tạo tệp). Mọi giờ theo múi giờ máy.
- **Cài đặt thêm:** `[OUTPUT]` `output_dir` (URI cây SAF, `sameAsDesktop = false`), `filename_template`, `extension`,
  `on_conflict`, `encoding`, `newline`, `add_header`, `mark_chunks`; `[VIDEO] skip_silent`; `[QUEUE] on_file_error`,
  `notify_when_done`; `[SYSTEM] delete_temp_when_done` (D66). Mặc định như `config.default.txt` trừ chỗ ghi rõ. Giao diện
  Stage 4 chỉ có model, cắt lặng, thư mục lưu; còn lại để Stage 8. Không làm ở stage này: `save_speaker_map` (Stage 5),
  `save_clean_audio`, `open_when_done`, `sound_when_done`, `include_subfolders`, `browse_folder`; `keep_awake` không cần
  (service luôn giữ wake lock).
- **Ghi qua SAF:** liệt kê tên trong thư mục một lần rồi tính tên không trùng như `unique_path`; tạo tài liệu với MIME theo
  đuôi (`.txt` → `text/plain`) để provider không tự thêm đuôi hay "(1)"; ghi đè thì mở chế độ `"wt"`. SAF không đổi tên
  nguyên tử như `os.replace`: ghi hỏng giữa chừng thì xoá tài liệu dở.
- **Người nói:** Stage 4 chỉ có nhánh "tắt nhận diện" của `transcribe_chunks` / `assemble_transcript`. `SPEAKERS.enabled =
  true` (chưa có giao diện) thì báo lỗi cấu hình, dừng hàng đợi trước khi xử lý âm thanh.
- **Service:** loại FGS theo API như `AudioDebugService`; partial wake lock suốt lúc chạy; thông báo tiến độ có nút Dừng,
  chạm mở Hàng đợi; xong hàng đợi thì một thông báo tổng kết (`notify_when_done`). `onTimeout` → dừng như bấm Dừng nhưng
  giữ cờ "đang chạy" để mở app thì làm tiếp (D64), thông báo lý do. Nhịp log mỗi 60 s (tệp, bước, đoạn, màn hình, Doze,
  mạng, pin) cho phép thử chạy dài. Bước âm thanh không có tiến độ quá 60 s → log WARNING và thông báo gợi ý kiểm cài đặt
  pin (Phát sinh Stage 2). Không chạy WorkManager cùng lúc (Phát sinh Stage 1).
- **Bản debug:** màn hình thử của Stage 2–3 giữ nguyên (`audio_debug.ps1`, `gemini_debug.ps1` còn dùng); quyền FGS,
  thông báo, wake lock chuyển sang manifest chính. Thêm receiver adb cho hàng đợi thật và `tools/device/queue_debug.ps1`
  (Stage 6 mới làm intent thật), cùng chế độ **Gemini giả** (chỉ bản debug, bật bằng adb hoặc menu debug): trả "Đoạn i."
  sau vài giây, không gọi mạng. Thử service, Dừng, giết app, giao diện đều chạy bằng Gemini giả; chỉ bước F gửi thật.
- **Test:** `FakeGemini` và harness MockWebServer của `:core:gemini` chuyển sang `testFixtures` để `:core:pipeline` dùng
  chung. Âm thanh tổng hợp do Kotlin tự sinh cùng hình dạng `make_audio` của desktop (60 s, ba khoảng có tiếng); không
  cần trùng từng mẫu vì ranh giới đoạn đã có golden ở Stage 2. Test thiết bị cài tay như CLAUDE.md (HyperOS).

## Rủi ro

- Giới hạn thời gian chạy của foreground service và cách mỗi hãng giết app nền → dựa vào cache từng đoạn để làm tiếp,
  hướng dẫn tắt tối ưu pin.
- Từ Android 12, khởi động lại FGS từ nền bị hạn chế: `START_STICKY` có thể không lên lại được → vẫn tự chạy lại lúc mở
  app (D64); ghi kết quả thử vào Nhật ký.
- SAF: provider có thể tự đổi tên / thêm đuôi; app nhận chia sẻ có thể không đọc được URI của thư mục SAF → lùi về
  `FileProvider` (bản sao trong `cacheDir`).
- HyperOS chặn mạng của app nền trong Doze dù có FGS → thấy ở F1 (log có trạng thái mạng / Doze mỗi nhịp).

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Port hàm nào thì KDoc ghi nguồn (`Port của transcribe.py:
assemble_transcript`). Test port từ desktop giữ tên ca kiểm tra để đối chiếu. Log giữ câu của desktop (viết có dấu).

Thứ tự: A → B, C (JVM, chạy hết trên PC) → D (service, lệnh adb, thử trên máy bằng Gemini giả) → E (giao diện) → F (gửi
thật, hỏi trước) → G. D, E thử trên máy ảo `guzz_15t` (D70); F và các mục cần HyperOS thử trên Xiaomi 15T. Không có bước
đo riêng.

**Máy ảo (D70):** `tools/device/emulator.ps1` tạo AVD `guzz_15t` (lần đầu), khởi động, cài APK debug, miễn tối ưu pin
(D69, máy ảo nên script tự chạy), đẩy tệp thử vào `files/input/`. Các script `queue_debug.ps1`, `audio_debug.ps1` dùng
`$env:ANDROID_SERIAL = 'emulator-5556'` (cổng do script in ra). AVD `itc_test` có sẵn trên PC là của dự án khác: không
đụng.

### A. Khung

- [x] **A1. Module** `:core:pipeline` (JVM) và `:feature:queue` (Android library) như "Mặc định kỹ thuật"; `:app` phụ
  thuộc `:feature:queue`. `FakeGemini` và harness MockWebServer của `:core:gemini` chuyển sang `testFixtures`.
  **Kiểm:** `.\gradlew projects` có hai module mới; `.\gradlew assembleSideloadDebug test lint` xanh; `:core:gemini`
  vẫn đủ 857 ca.
- [x] **A2. Cài đặt** (mục "Mặc định kỹ thuật") vào `SettingKeys`, chặn như `load_config`; `PipelineConfig.load(settings)`
  đọc lại mỗi tệp như desktop.
  **Kiểm:** `DesktopDefaultsTest` xanh với mục mới; mục `sameAsDesktop = false` có lý do trong KDoc.
- [ ] **A3. Manifest:** quyền `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK` chuyển từ manifest debug sang
  manifest chính; khai báo `QueueService` (`mediaProcessing|dataSync`, không exported).
  **Kiểm:** manifest đã gộp của cả hai flavor có quyền + service; `.\gradlew lint` xanh; `audio_debug.ps1` chạy một tệp
  ngắn vẫn ra `ket_qua.json`.

### B. Hàm thuần và golden (`tools/golden/gen_pipeline.py`)

Script chạy chính `transcribe.py`, `config.py` của desktop trên các đầu vào dựng sẵn (giờ cố định), ghi
`testdata/golden/pipeline/*.json`.

- [x] **B1. Mã băm cache:** `pyHash` (port `_hash`), `fingerprint`, tên thư mục tạm (`temp_dir_for`), `audioHash`, khoá
  chung của kết quả đoạn (`shared_key` nhánh không người nói).
  **Kiểm:** golden: chuỗi JSON và mã trùng desktop trên ma trận đầu vào (chữ có dấu, số thực `10.0` / `1e-05` / `-16.0`,
  bool, `None`, tuple / list lồng); đổi một tham số âm thanh thì mã đổi.
- [x] **B2. Tên tệp và nội dung:** `outputFilename` (biến mới và tên cũ `{ten}` `{ngay_ghi}` `{ngay}` `{gio}`),
  `sanitizeFilename`, `uniqueName` (trên tập tên có sẵn, kiểu `unique_path`), `encodeText` (crlf / lf, utf-8 /
  utf-8-sig, như `write_text`).
  **Kiểm:** golden trên ma trận mẫu tên × tên nguồn (ký tự cấm, dấu chấm cuối, tên rỗng) × giờ cố định; bytes ghi ra trùng
  desktop.
- [x] **B3. Ghép:** `hms`, `formatHeader`, `assembleTranscript` (nhánh không người nói), `countModels`, `hasSpeech`.
  **Kiểm:** golden: phần đầu (âm thanh / video, một / nhiều model) và thân (đánh dấu đoạn bật / tắt, đoạn rỗng bị bỏ) trùng
  desktop từng ký tự.

### C. Pipeline một tệp và hàng đợi (`:core:pipeline`, JVM)

- [x] **C1. Chuẩn bị đoạn có cache:** khoá `ma` trong `ChunkList`; `readChunkList(folder, digest)` như desktop (sai mã hoặc
  thiếu tệp đoạn → `null`); có thì dùng lại, không thì gọi `ChunkSource`; xong thì xoá `goc.pcm` / `sach.pcm` (D66).
  **Kiểm:** test JVM: chạy hai lần cùng tham số → lần hai không gọi `ChunkSource`; đổi `chunk_minutes` hoặc xoá một
  `doan_*.flac` → làm lại; golden Stage 2 (`ChunkPreparerTest`, `ChunkerGoldenTest`) vẫn xanh.
- [x] **C2. `transcribeChunks`** (nhánh không người nói): đọc / ghi `doan_NNN.<mã>.json`, một `ChunkDispatcher` mỗi tệp,
  prompt (`DefaultPrompt.text` cho model đa năng, `null` cho `*-transcribe`), bản chép lời rỗng thì thử model khác
  (`initialSkip`), không cache kết quả rỗng, log từng đoạn như desktop, thêm một dòng "dùng lại kết quả đã có" để kiểm
  được việc không gửi lại.
  **Kiểm:** test JVM với `FakeGemini`: đoạn đã có kết quả thì không gọi lại; port
  `test_empty_transcript_retries_with_another_model`, `test_empty_result_is_not_cached`.
- [x] **C3. `FilePipeline`** (port `transcribe_single_file`): kiểm key, ràng buộc model, người nói trước khi xử lý âm
  thanh; trùng tên mà `on_conflict = skip` thì bỏ qua; không có luồng âm thanh + `skip_silent` thì bỏ qua (Phát sinh
  Stage 2); tiến độ như `ProgressTracker` (âm thanh 0–0,15, chép lời 0,15–0,98, ghép tới 1); ghi qua `OutputFolder`; xong
  thì xoá thư mục tạm nếu `delete_temp_when_done`; trả `FileResult`.
  **Kiểm:** port 14 ca còn lại không có người nói của `tests/test_transcribe.py` qua `FilePipeline` với âm thanh tổng hợp:
  3 ca `TestTranscribe` (`test_no_speakers_writes_next_to_source_crlf_bom` đổi "cạnh tệp nguồn" thành thư mục ra,
  `test_output_dir_and_template`, `test_resume_after_error_does_not_resend_done_chunks`); 9 ca `TestAutoSwitchModel`
  (2 ca dùng `method = gemini` chỉ để chuỗi còn một model thì đổi thành tắt tự đổi model, ghi trong KDoc); 2 ca
  `TestReplayRealLog` qua `GeminiClient` thật + MockWebServer. Tiến độ tăng dần và kết thúc ở 1,0.
- [x] **C4. Hàng đợi:** `QueueItem` (nguồn, tên hiển thị, thời lượng, trạng thái `cho` / `dang_chay` / `xong` / `loi` /
  `dung` / `bo_qua`, tiến độ, chi tiết, kết quả, lỗi và loại lỗi), `QueueStore` (D63; tiến độ ghi thưa, khi đổi bước
  hoặc đoạn), `QueueRunner` (port `TranscribeThread.run` và phần kiểm trước của `_start`: chưa có key, ràng buộc, mọi
  model khoá quá `max_all_locked_wait_min` thì không bắt đầu; lần lượt từng tệp (D60), thêm tệp lúc đang chạy được; lỗi
  `stopQueue` hoặc ràng buộc thì dừng hàng đợi; lỗi khác thì tệp lỗi, chạy tiếp hay dừng theo `on_file_error`; Dừng thì
  tệp đang chạy về `dung`; Bắt đầu chạy mọi tệp `cho` / `loi` / `dung` như `_RUNNABLE`). Khôi phục (D64): tệp
  `dang_chay` về `cho`, cờ "đang chạy" của hàng đợi quyết có tự chạy lại không.
  **Kiểm:** test JVM: chuỗi trạng thái cho từng loại lỗi; dựng lại `QueueStore` từ tệp giữa chừng (giả lập bị giết) →
  chạy tiếp đúng tệp, đoạn đã xong không gửi lại; tệp JSON hỏng → hàng đợi rỗng + log, không ném lỗi.

### D. Service và lệnh adb (thử bằng Gemini giả, không gửi gì lên Google)

- [x] **D1. `QueueService` + `QueueController`** ở `:feature:queue`: bản Android của `ChunkSource` (bọc `AudioPreparation`,
  vân tay content:// qua `OpenableColumns.SIZE` / `COLUMN_LAST_MODIFIED`), FGS, wake lock, thông báo tiến độ + nút Dừng,
  thông báo tổng kết, nhịp log 60 s, báo đứng ở bước âm thanh, `onTimeout`, `START_STICKY` và tự chạy lại lúc mở app
  (D64). `AppContainer` dựng client Gemini mỗi tệp (`GeminiConfig.load` + `geminiClient()`), bản debug thay bằng Gemini
  giả khi bật.
  **Kiểm:** `.\gradlew assembleSideloadDebug lint` xanh; chạy thật ở D3.
- [x] **D2. Lệnh adb (chỉ bản debug):** receiver exported nhận `add` (tệp trong `files/input/` hoặc đường dẫn tuyệt đối;
  tham số chờ giữa các đoạn của D67; ra thư mục SAF đã chọn hoặc `files/output/` của app), `start`, `stop`, `status` (ghi
  `files/debug-queue/trang_thai.json`), `fake on|off`. Script `tools/device/queue_debug.ps1`: `-Add`, `-ChunkDelayMin`,
  `-Start`, `-Stop`, `-Status`, `-Kill`, `-ScreenOff`, `-Fake`, `-Wait` (chờ hàng đợi xong rồi in trạng thái).
  **Kiểm:** Gemini giả bật: `-Add <tệp ngắn> -Start -Wait` → `files/output/<tên>.txt` có đủ "Đoạn i."; log không có lần
  gọi HTTP nào tới Google.
- [x] **D3. Thử chạy nền trên máy ảo** (D70; Gemini giả, chờ giữa đoạn vài chục giây cho đủ lâu):
  1. `-ScreenOff`: chạy hết một bài 20 phút với màn hình tắt;
  2. `-Stop` lúc ở đoạn 2, `-Start` lại → đoạn 1 dùng lại kết quả;
  3. `-Kill` lúc ở đoạn 2 (`am kill`; không giết được process có FGS thì `kill -9` qua `run-as`) → xem service có tự lên
     lại không (`START_STICKY`), rồi mở app → tự làm tiếp;
  4. `onTimeout`: `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 120000`, chạy
     `L1_120m` (vẫn đang xử lý âm thanh ở phút thứ 2) → nhật ký có `onTimeout`, service dừng ≤ 1 s, `adb logcat -d -b
     crash` trống; mở app thì làm tiếp; hoàn tác bằng `device_config delete …`.

  **Kiểm:** đủ 4 mục trên, log xác nhận; ghi Nhật ký `START_STICKY` có lên lại FGS được không. Mục 1 và 3 làm lại trên
  Xiaomi 15T ở F (HyperOS giết app / tắt wake lock khác máy ảo).

### E. Giao diện (Compose, vi / en) — thử bằng Gemini giả trên máy ảo (D70)

- [ ] **E1. Màn hình Hàng đợi** là màn hình chính: thêm tệp (`OpenMultipleDocuments`, quyền URI bền), danh sách (tên, thời
  lượng, trạng thái + chi tiết bước / đoạn / model, thanh tiến độ), Bắt đầu / Dừng, menu từng dòng (Xem, Mở bằng app
  khác, Chia sẻ, Chạy lại, Bỏ khỏi danh sách), Xoá tệp đã xong. Dải báo: chưa có key (nút sang trang Google AI), chưa
  chọn thư mục lưu, chưa miễn tối ưu pin (mở trang thông tin app), thiếu quyền thông báo (xin lúc bấm Bắt đầu lần đầu,
  API 33+). Trang Google AI và màn hình thử (debug) mở từ menu.
  **Kiểm:** trên máy: thêm 2 tệp bằng trình chọn tệp, Bắt đầu, thấy tiến độ từng đoạn; Dừng / Bắt đầu lại; Bỏ một tệp đã
  dừng thì thư mục tạm của nó bị xoá; vuốt app khỏi đa nhiệm rồi mở lại không mất danh sách.
- [ ] **E2. Thư mục lưu và cài đặt nhanh:** chọn thư mục (`OpenDocumentTree`, gợi ý Documents; D59), model chính (danh
  sách như trang Google AI, nhập tay qua `isValidModelName`), cắt khoảng lặng. Chưa chọn thư mục mà bấm Bắt đầu thì mở
  trình chọn.
  **Kiểm:** trên máy: chọn `Documents/Guzz`; chạy cùng tệp hai lần → `<tên>.txt` và `<tên> (2).txt` thấy được trong app
  Files, tên không bị thêm đuôi; xoá thư mục đã chọn bằng app Files rồi Bắt đầu → báo lỗi rõ, app không chết.
- [ ] **E3. Xem và chia sẻ** (D61, D62): màn hình xem (đọc qua URI, bỏ BOM, đoạn văn chọn chép được, cuộn mượt với bản
  chép lời 2 giờ), Mở bằng app khác (`ACTION_VIEW`), Chia sẻ (`ACTION_SEND` + `EXTRA_STREAM`, cấp quyền đọc).
  **Kiểm:** trên máy: mở bản chép lời của E2 trong app; Mở bằng app khác mở được bằng một trình soạn thảo; chia sẻ sang
  một app (vd Drive) nhận đúng tệp, đúng tên. App nhận không đọc được URI SAF thì đổi sang `FileProvider`, ghi Nhật ký.

### F. Gửi thật (D65; hỏi người dùng ngay trước mỗi lần gửi)

- [ ] **F1. B5, tắt màn hình suốt:** `adb push` B5 vào `files/input/`, `queue_debug.ps1 -Add B5 -ChunkDelayMin 10 -Start
  -ScreenOff` (D67), rút cáp / `adb disconnect` để máy vào Doze, chờ khoảng 75 phút, nối lại, kéo log.
  **Kiểm:** bản chép lời B5 trong thư mục đã chọn, đủ 6 đoạn có chữ; nhịp log liền ≥ 60 phút, không hở quá 2 phút; một
  PID suốt lúc chạy (service không bị giết); log ghi Doze / mạng ở các lần gửi; ≤ 7 yêu cầu Interactions.
- [ ] **F2. B4, Dừng và giết app:** bấm Dừng trên thông báo lúc đang ở đoạn 2–3, Bắt đầu lại; rồi `-Kill` lúc đang ở đoạn
  4 → tự làm tiếp (D64).
  **Kiểm:** log: mỗi `doan_NNN` chỉ một lần gửi thành công, đoạn đã xong chỉ có dòng "dùng lại kết quả"; bản chép lời đủ
  5 đoạn; thư mục tạm của B4 bị xoá khi xong (D66); tổng cả stage ≤ 15 yêu cầu (đếm trong log và sổ hạn mức).

### G. Đóng stage

- [ ] **G1. Tài liệu:** CLAUDE.md thêm lệnh (test `:core:pipeline`, golden pipeline, `queue_debug.ps1`); README §3–4 đã
  sửa lúc plan (không Room, hàng đợi JVM ở `:core:pipeline`), chỉ sửa thêm nếu module khác dự kiến.
  **Kiểm:** chạy lại từng lệnh đã chép.
- [ ] **G2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 4 → ✅; "Việc tiếp theo" → plan Stage 5; commit, push.
  **Kiểm:** `git status -sb` báo `## main...origin/main`; `git grep AIza` không ra gì; repo không có bản ghi thật.

## Nhật ký

- **2026-11-11, plan.** Đọc `transcribe.py`, `tests/test_transcribe.py`, `gui/threads.py`, `gui/pages/transcribe.py`
  (`_start`, `_RUNNABLE`), các mục `[OUTPUT]` / `[QUEUE]` / `[SYSTEM]` của `config.py` và `config.default.txt`, cùng
  `:core:audio`, `:core:media`, `:core:gemini` và `AudioDebugService` hiện có. Hỏi người dùng 10 câu trắc nghiệm, cả 10
  chọn Recommended (D59–D68). Chuyển đi: lưu cạnh tệp nguồn → Phát sinh Stage 6; `save_extracted` (D68), chỉ gửi qua
  Wi-Fi → Phát sinh Stage 8; đo tốc độ bản release → Phát sinh Stage 9 (người dùng không muốn bước đo riêng); thư mục tạm
  không còn PCM (D66) → Phát sinh Stage 5.
- **2026-11-12, A–C.** Xong A1, A2, B1–B3, C1–C4 trên PC: `:core:pipeline` 38 ca (9 golden, 16 ca port từ
  `test_transcribe.py`, 4 ca cache / dừng, 9 ca hàng đợi), cả repo `test assembleSideloadDebug lint` xanh. Khác kế
  hoạch / desktop:
  - A1: `:core:gemini` đếm được 859 ca (kế hoạch ghi 857), không mất ca nào khi chuyển `FakeGemini`, `ClientHarness`,
    `TestSupport` sang `testFixtures`.
  - B2: mẫu tên tệp chỉ nhận `{biến}`, `{{`, `}}`; `{name!r}`, `{name:>12}`, `{name[0]}`, `{name.upper}` desktop nhận
    (cái cuối còn ra địa chỉ bộ nhớ) còn mobile coi là mẫu sai. So tên trùng không phân biệt hoa thường (Windows và bộ
    nhớ dùng chung của Android đều vậy).
  - C1: chữ ký video trong mã âm thanh là `["track", <[VIDEO] audio_track>]` thay vì luồng đã chọn: chọn luồng phải mở tệp,
    mà luồng chọn ra tất định theo tệp + cài đặt. `goc.pcm` / `sach.pcm` bị xoá cả khi dừng / lỗi giữa bước âm thanh
    (D66 chỉ nói "cắt đoạn xong"): lần chạy lại không có danh sách đoạn thì giải mã lại từ đầu, PCM cũ vô dụng.
  - C3: thêm `PipelineException` (mẫu tên sai, bật người nói, thư mục lưu hỏng; có `stopQueue`) và `NoAudioTrackException`
    (JVM, thay cho `NoAudioException` của `:core:media`).
  - C4: thêm `StartProblem` (kiểm trước khi bắt đầu như `_start`), `prepareStop(resumeLater)` cho `onTimeout` (D64);
    tệp hàng đợi hỏng đổi tên thành `.hong` để xem lại.
- **2026-11-12, thử trên Xiaomi 15T.** D2 đạt: `-Fake on`, `-Add B1_1m.m4a -Start -Wait` → `files/output/B1_1m.txt` là
  "Đoạn 1.", log có "Gemini GIẢ", không dòng HTTP nào, thư mục tạm bị xoá. Gặp: `ForegroundServiceStartNotAllowedException`
  khi receiver khởi động service (app chưa miễn tối ưu pin) → D69; `trang_thai.json` thiếu giá trị mặc định → ghi bằng
  `encodeDefaults`. D3.1 (bài `L1_20m.m4a` cắt từ `L1_120m.m4a`, đặt ở `D:\GuzzMobileData\stage-04`) chạy được tới phần
  giải mã lúc tắt màn hình thì người dùng phải ra ngoài; chuyển adb sang Wi-Fi 5555 (Tailscale trên điện thoại đang
  tắt). Người dùng chọn thử D–E trên máy ảo (D70).
- **2026-11-13, D3 trên máy ảo `guzz_15t`** (Gemini giả, `L1_20m.m4a`, chờ 1 phút giữa đoạn). Đạt cả 4 mục:
  1. tắt màn hình suốt: xử lý âm thanh 173 s, 2 đoạn, nhịp log mỗi 60 s ("màn hình tắt"), bản chép lời "Đoạn 1. / Đoạn
     2.", thư mục tạm bị xoá;
  2. Dừng lúc chờ đoạn 2 rồi Bắt đầu: "Các đoạn âm thanh đã có sẵn", "Đoạn 1/2: dùng lại kết quả đã có", chỉ gửi đoạn
     2;
  3. `kill -9` lúc chờ đoạn 2: **`START_STICKY` lên lại FGS được** (app đã miễn tối ưu pin, D69) sau khoảng 3 s, log
     "Hệ thống khởi động lại service hàng đợi (START_STICKY…)", đoạn 1 dùng lại. Thêm: `am force-stop` (không tự lên
     lại) rồi mở app → "tự làm tiếp", đoạn 1 dùng lại;
  4. `onTimeout` (`media_processing_fgs_timeout_duration` = 120000, bài `L1_120m.m4a`): ở phút thứ 2 có "FGS
     (mediaProcessing) timed out", service dừng sau 16 ms, `logcat -b crash` trống, tệp về `cho`, cờ đang chạy giữ.
     Lỗi tìm ra: mở lại app khi màn hình chính còn trong ngăn xếp thì không tự làm tiếp (`resumeIfNeeded` chỉ ở
     `onCreate`) → chuyển sang `onResume`, thử lại đạt. Đã hoàn tác `device_config` (`get` trả `null`).
  Mục 1 và 3 làm lại trên Xiaomi 15T ở đợt thử cuối (HyperOS).
- **2026-11-13, review.** Workflow review code D–E (6 hướng, mỗi phát hiện 3 người kiểm phản bác) chỉ chạy xong một phần
  vì hết hạn mức 5 giờ. Một lỗi được cả 3 xác nhận: thư mục tạm chỉ theo nguồn, nên `remove()` / `clearFinished()` một
  mục cũ (xong / lỗi) của cùng tệp xoá thư mục của bản đang chạy lại → sửa ở bước D1. Chạy lại review đủ 6 hướng khi có
  hạn mức.
- **2026-11-12, D–E (chưa thử trên máy).** Đã viết `QueueController`, `QueueService`, `AndroidChunkSource`,
  `SafOutputFolder`, `FakeTranscriber`, receiver adb + `tools/device/queue_debug.ps1`, màn hình Hàng đợi và màn hình xem
  (commit `6d3824b`); build + lint xanh cả hai flavor. Chưa tick D1–E3: điện thoại không nối được adb, người dùng dừng
  ở đây. Lần sau: nối máy, làm tiếp từ D2 (thử bằng Gemini giả), rồi kiểm A3 trên máy. Thêm so với kế hoạch:
  `FileProvider` cho bản chép lời ghi bằng đường dẫn (thư mục `files/output` của lệnh adb); quyền
  `ACCESS_NETWORK_STATE` cho nhịp log.

## Phát sinh

- (2026-11-13) Chạy lại workflow review code D–E đủ 6 hướng (service, trạng thái hàng đợi, pipeline, SAF, giao diện,
  công cụ debug); lần đầu bị cắt vì hết hạn mức. Làm trước bước G.

- (Từ Stage 1, 2026-11-10) Hai phép thử chạy nền của Stage 1 chuyển sang đây, làm trên foreground service thật (D42):
  - **Chạy dài** (G4 cũ): màn hình tắt suốt lúc chạy, nhật ký ghi nhịp liền ≥ 60 phút, không hở quá 2 phút, service không
    bị giết; ghi lại lúc chạy có mạng hay không (gọi Gemini trong Doze). Gộp vào tiêu chí "Chạy thật một bài 1 giờ".
  - **`onTimeout`** (G5 cũ): người dùng tự chạy
    `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 120000`, chạy một bài; nhật ký có
    `onTimeout` vào khoảng phút thứ 2, service dừng trong ≤ 1 s, `adb logcat -d -b crash` trống. Hoàn tác:
    `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.
  - → Plan: chạy dài ở F1 (D67), `onTimeout` ở D3.
- (Từ Stage 1, 2026-11-10) Điều đã biết từ app spike:
  - FGS không giữ CPU thức lúc màn hình tắt → service giữ partial wake lock (`WAKE_LOCK`) trong lúc chạy;
  - `BackupManagerService` giết process app để sao lưu đầy đủ khi máy sạc và rảnh, dù đang có FGS → đặt
    `android:allowBackup="false"` (khớp Phát sinh của Stage 3 về sao lưu API key) → đã làm ở Stage 3 (A3, D55);
  - Android 16: job (kể cả WorkManager) chạy song song với FGS bị tính vào quota → không chạy việc dài bằng WorkManager
    cùng lúc với service.
  - Tham khảo `spikes/android/.../bench/BenchService.kt` và `automation/HeartbeatService.kt`.
  - → Plan: wake lock và không WorkManager ở D1 ("Mặc định kỹ thuật").
- (Từ plan Stage 2, 2026-11-10) Việc Stage 2 để lại cho stage này:
  - `[VIDEO] save_extracted` (D47): chép tiếng của video ra cạnh bản chép lời. Mobile có sẵn `goc.pcm` trong thư mục
    tạm, chỉ cần mã hoá ra FLAC khi lưu kết quả;
  - `[VIDEO] skip_silent`: Stage 2 ném `NoAudioError`, stage này quyết bỏ qua tệp hay báo lỗi;
  - mã băm cache (`audio_hash`) cho thư mục tạm của Stage 2 (`goc.pcm`, `sach.pcm`, `doan_*.flac`,
    `danh_sach_doan.json`, chưa có khoá `ma`); bài 2 giờ chiếm khoảng 660 MB tạm, cần chính sách dọn;
  - màn hình thử và foreground service chỉ có ở bản debug của Stage 2 (D49): thay bằng màn hình Hàng đợi và service
    thật.
  - → Plan: `save_extracted` để Stage 8 (D68); `skip_silent` như desktop (bỏ qua tệp) ở C3; mã băm ở B1, C1; dọn tạm D66;
    màn hình thử giữ lại ở bản debug ("Mặc định kỹ thuật"), màn hình Hàng đợi ở E1.
- (Từ Stage 2, 2026-11-11) Đo trên Xiaomi 15T, xem Nhật ký Stage 2:
  - khi app chưa được đặt pin "No restrictions", HyperOS tắt partial wake lock của FGS lúc màn hình tắt ("DISABLED,
    reason: Process Priority") và việc giải mã đứng im → hướng dẫn cài đặt (Stage 6) phải có bước này, và service nên
    phát hiện bị đứng (không tiến độ trong N giây) để báo người dùng;
  - FGS chạy chậm hơn nhiều khi người dùng đang dùng app khác (giải mã ×19 → ×6) và khi tắt màn hình nếu luồng bị
    chặn; giữ kiểu 3 luồng của `AudioDecoder` cho mọi bước nặng;
  - bài 2 giờ tốn khoảng 660 MB tạm (`goc.pcm` 230 MB, `sach.pcm` tới 230 MB, FLAC khoảng 200 MB): dọn `goc.pcm` /
    `sach.pcm` khi không cần cho Stage 5 nữa;
  - đo lại tốc độ trên bản release (không debuggable) khi có service thật.
  - → Plan: báo đứng ở D1, dải báo "chưa miễn tối ưu pin" ở E1; dọn PCM ở C1 (D66); đo bản release → Phát sinh Stage 9.
- (Từ plan Stage 3, 2026-11-11) Ca "bản chép lời rỗng thì thử model khác cho đoạn đó, và không cache kết quả rỗng"
  (`test_empty_transcript_retries_with_another_model`, `test_empty_result_is_not_cached` của desktop) thuộc pipeline:
  dùng tham số `initial_skip` của `ChunkDispatcher.send` (Stage 3 port sẵn) và port hai test này ở stage này.
  → Plan: C2.
- (Từ Stage 3, 2026-11-11) Dùng client Gemini trong pipeline: `ChunkDispatcher(GeminiConfig.load(settings),
  container.geminiClient()!!)` mỗi tệp (đọc lại cài đặt mỗi tệp như desktop); chưa có key thì báo lỗi `cau_hinh` và dừng
  hàng đợi trước khi xử lý âm thanh. Dispatcher tự kiểm ràng buộc model ↔ cách nhận diện (`ConstraintException`, dừng
  hàng đợi). `send` cần số giây của đoạn (`ket_thuc_giay - bat_dau_giay`) cho bộ giãn nhịp; prompt khi tắt nhận diện
  người nói là `DefaultPrompt.text` cho model đa năng, `null` cho `*-transcribe` (như `prompt_for` của
  `transcribe_chunks`). Lỗi có `stopQueue` thì dừng cả hàng đợi, còn lại báo tệp lỗi. Nút Dừng = huỷ coroutine (client
  vẫn xoá tệp đã tải lên). Màn hình thử của bản debug có thêm phần gửi thử Gemini (`GeminiDebug`): thay hoặc giữ cùng
  lúc với màn hình thử âm thanh.
  → Plan: C2–C4, D1; màn hình thử giữ nguyên.
