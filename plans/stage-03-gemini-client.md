# Stage 3 — Client Gemini, hạn mức, đổi model

**Trạng thái:** ✅ xong 2026-11-11 (plan chi tiết và làm cùng ngày) · **Cần xong trước:** 0 (làm song song được với Stage 1–2)

## Mục tiêu

Port phần gọi Google AI Studio, xử lý hạn mức và tự đổi sang model dự phòng, với cùng hành vi như desktop — kể cả chuỗi
lỗi đã ghi trong nhật ký ngày 17/09.

## Phạm vi

### Làm

- API key: nhập, kiểm tra, lưu mã hoá bằng Android Keystore. Không bao giờ ghi key ra log.
- Files API: tải lên kiểu resumable (start → upload, finalize), `wait_file_ready`, `delete_file`; `list_models`.
- Interactions API (`build_request`):
  - model đa năng: prompt + âm thanh + tệp văn bản đi kèm;
  - model `*-transcribe`: `transcription_config` (smart / verbatim, ngôn ngữ, từ vựng, tách người nói, mốc thời gian từng
    từ).
- Đọc kết quả: `extract_text`, `extract_words`, `check_status`, số token đầu vào.
- Phân loại lỗi `ERR_*` thành sealed class; đọc quota violation (`quotaId` theo ngày / theo phút), retry-after.
- `RateLimiter`: giới hạn token / phút và yêu cầu / phút.
- `QuotaTracker`: giờ tính lại hạn mức theo giờ Thái Bình Dương; khoá / mở khoá model; tự học hạn mức Google báo; đếm yêu
  cầu và token trong ngày; lịch sử 300 sự kiện gần nhất. Lưu trong app, không dùng chung tệp với desktop.
- `ChunkDispatcher`: chuỗi model dự phòng, chờ / khoá / đổi model, `max_all_locked_wait_min`, quay lại model chính.
- Ràng buộc model ↔ cách nhận diện người nói (`config.constraint_error`).
- Port test: `test_google_ai.py`, `test_quota.py` với HTTP giả lập (MockWebServer), gồm cả chuỗi lỗi 17/09.
- Màn hình tối thiểu: nhập key, Kiểm tra kết nối, chọn model.

### Không làm

- Trang Hạn mức & sử dụng đầy đủ (Stage 8).
- Gửi kèm bản đồ người nói (khung có sẵn, dùng thật ở Stage 5).

## Nguồn tham khảo trong Guzz desktop

- `google_ai.py`, `quota.py`
- `transcribe.py`: `ChunkDispatcher`, `tracker_for`
- `config.py`: `model_chain`, `constraint_error`, các mục `[GOOGLE_AI]`, `[FALLBACK]`, `[QUOTAS]`
- `tests/test_google_ai.py`, `tests/test_quota.py`, `tests/test_config.py`
- README desktop §6 (bảng xử lý lỗi)

## Kết quả bàn giao

- Module `:core:gemini` có test, chạy trên JVM.
- Gọi thật được một đoạn âm thanh từ màn hình debug.

## Tiêu chí hoàn thành

- [x] Test port từ desktop đều qua (danh sách ở bước C1–C2, B4–B5), golden khớp desktop.
- [x] Chạy lại chuỗi lỗi 17/09: hành vi giống hệt desktop.
- [x] Không có key trong log (kể cả ở mức DEBUG).
- [x] Gửi thật được một đoạn từ máy (D57): model đa năng và model `*-transcribe`.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-11, xem mục [Quyết định](#quyết-định): HTTP → D51; lưu hạn mức → D52; log DEBUG → D53; nhập từ
desktop → D54. Hỏi thêm lúc plan: sao lưu → D55; màn hình → D56; gửi thật → D57; model mặc định → D58.

## Quyết định

Hỏi người dùng ngày 2026-11-11. Người dùng chọn phương án Recommended ở cả 8 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D51 | Thư viện HTTP | **OkHttp** (5.5.1), test bằng MockWebServer | Đúng mặc định ở README; chạy cả JVM lẫn Android; port được test HTTP giả của desktop. |
| D52 | Lưu dữ liệu hạn mức | **Tệp JSON cùng cấu trúc `su_dung.json`**, ghi `.tmp` rồi đổi tên | Port và so golden dễ; chỉ app dùng nên bỏ phần khoá tệp / gộp tệp của desktop. |
| D53 | Log yêu cầu ở mức DEBUG | **Có, như desktop** (JSON yêu cầu, cắt 2 000 ký tự) | Dò được lỗi 400; key ở header nên không vào log, có test kiểm. |
| D54 | Nhập key / cài đặt từ desktop | **Không, nhập key tay** | Không chép tệp chứa key giữa máy; nhập / xuất cài đặt để Stage 8. |
| D55 | Sao lưu dữ liệu app | **`allowBackup="false"` toàn app** | Key, hạn mức không lên Drive; hết chuyện sao lưu đầy đủ giết foreground service (Stage 1). Đổi máy thì nhập lại key. |
| D56 | Màn hình | **Trang Google AI thật** (nhập key, Kiểm tra kết nối, chọn model) **+ nút gửi thử một đoạn ở màn hình thử bản debug** | Stage 4 cần trang nhập key thật; gửi thử chỉ để kiểm. |
| D57 | Gửi thật lên Gemini | **Khoảng 4 yêu cầu, hỏi người dùng ngay trước khi gửi**: `list_models`, một đoạn ngắn với model chính, một đoạn với `*-transcribe` (smart, rồi mốc từng từ) | Tài liệu Google hôm nay ghi định dạng yêu cầu `*-transcribe` khác bản desktop (xem "Thông tin bên ngoài"); chỉ gửi thật mới biết. |
| D58 | Model mặc định | **Giữ như desktop**; khi gửi thật chạy `list_models`, model nào đã mất thì ghi vào Phát sinh của Stage 8 | Mặc định trùng desktop (golden test); model 404 tự bị khoá rồi đổi model như desktop. |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Module `:core:gemini` JVM thuần: OkHttp, kotlinx.serialization (`JsonObject` cho yêu cầu / phản hồi, vì cấu trúc lỏng
  như dict của Python), coroutines. Hàm gọi mạng và chờ là `suspend`: nút Dừng là huỷ coroutine, thay cho `should_stop`
  / `Stopped` của desktop. Đồng hồ (`clock`) và hàm chờ truyền vào được, test chạy bằng thời gian ảo của
  `kotlinx-coroutines-test`.
- Mã loại lỗi giữ đúng chuỗi của desktop (`tam_thoi`, `qua_tai`, `han_muc`, `bi_chan`, `model`, `cau_hinh`, `khac`) vì ghi
  vào tệp hạn mức và log; trong Kotlin là enum có thuộc tính `code`.
- `[QUOTAS]` là section có khoá động (tên model). Lưu thành một mục `QUOTAS.lines` (chuỗi các dòng `model = tpm, rpm, rpd`,
  `sameAsDesktop = false`) và có golden test: đọc mặc định ra đúng `model_quotas` của desktop.
- Cài đặt thêm: `[GOOGLE_AI]` `model`, `transcribe_mode`, `languages`, `vocabulary`, `store_on_server`,
  `token_rate_limit`, `timeout_seconds`, `chunk_retries`, `delete_remote_file`; `[FALLBACK]` đủ 8 mục; `[SPEAKERS]`
  `enabled` và cách nhận diện (chỉ để tính chuỗi model / ràng buộc; dùng thật ở Stage 5). `prompt_file` của desktop
  không có trên mobile: prompt mặc định là nội dung `prompt.txt` của desktop, đóng gói làm resource; sửa prompt để Stage 8.
- API key: interface `ApiKeyStore` ở `:core:gemini` (port `mask_api_key`), bản thật ở `:app` mã hoá AES-GCM bằng khoá
  Android Keystore, bản mã lưu trong `filesDir`. Không biến môi trường, không tệp `api_key.txt` như desktop.
- Giờ Thái Bình Dương: `java.time` với `ZoneId.of("America/Los_Angeles")`; golden so với `pacific_date` /
  `next_daily_reset` (desktop tự tính quy tắc DST) trên nhiều mốc qua các lần đổi giờ 2025–2030.
- Không port phần dùng chung tệp với app khác của `quota.py` (khoá tệp, vân tay tệp, gộp `su_dung.json` cũ) và các test
  `TestSharedWithOtherApp`, `TestMergeLegacyFiles`: README §1 "Không làm".
- Test thiết bị cần cài APK test: HyperOS hay huỷ khi cài qua adb không dây, làm như lệnh ghi trong CLAUDE.md (Stage 2).

## Rủi ro

- Interactions API và các model `*-transcribe` có thể đã đổi so với lúc viết bản desktop → đầu stage kiểm lại tài liệu
  Google.

## Thông tin bên ngoài (kiểm 2026-11-11)

- Tài liệu Gemini API chuyển sang `aistudio.google.com/docs/…` (đường cũ `ai.google.dev/gemini-api/docs/…` chuyển hướng).
- Interactions API: `POST https://generativelanguage.googleapis.com/v1beta/interactions`, header `x-goog-api-key`, GA từ
  6/2026, là giao diện khuyên dùng. `store` mặc định lưu (Free Tier 1 ngày); `store = false` thì không lưu. Trang tổng
  quan không ghi kiểu các phần `input` hay tên trường `usage`. (`/docs/interactions-overview`)
- `gemini-3.5-transcribe` (`/docs/transcribe.md.txt`): ví dụ của Google dùng phần vào `{"type": "file", "file_uri": …}`
  và `mode` là chuỗi `"smart"`, còn desktop gửi `{"type": "audio", "uri", "mime_type"}` và `{"type": "smart"}` (chạy được
  9/2026). Ràng buộc trùng desktop: `custom_vocabulary` không đi cùng tách người nói / mốc từng từ; smart không đi cùng
  hai thứ đó. Mốc từng từ: annotation `word_info` có `text`, `speaker` (`spk_1`), `start_offset` / `end_offset`
  (`"0.100s"`), khớp `extract_words` của desktop. Tối đa 1 giờ mỗi yêu cầu, 30 phút khi tách người nói / mốc từng từ;
  tách người nói tối đa 8 người. MIME nhận: wav, mp3, aiff, aac, ogg, flac, mpeg, m4a, l16, opus, alaw, mulaw, webm.
- Danh sách model (`/docs/models`): có `gemini-3.5-flash-lite`, `gemini-3.6-flash`, `gemini-3.8-flash`,
  `gemini-3.5-transcribe`; **không thấy `gemini-3.5-flash`** (nằm trong chuỗi dự phòng mặc định của desktop, D58).
  `gemini-2.5-flash` sẽ chuyển sang `gemini-3.5-flash-lite` ngày 2026-12-01.
- Hạn mức (`/docs/rate-limits`): đo theo RPM, TPM (token vào), RPD; RPD tính lại lúc 0 giờ giờ Thái Bình Dương; hạn mức
  **theo project**, không theo key. Trang không ghi số cụ thể của gói miễn phí cho các model trên, cũng không mô tả
  `QuotaFailure` / `RetryInfo` → giữ cách đọc lỗi 429 của desktop (đã kiểm bằng lỗi thật 17/09).
- Diễn đàn (2026-08-29): tổ hợp `custom_vocabulary` + tách người nói / mốc từng từ bị Interactions API từ chối, đúng như
  desktop đã chặn.
- Thư viện: OkHttp 5.5.1, `mockwebserver3` 5.5.1 (Maven Central).

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Port hàm nào thì KDoc ghi nguồn (`Port của google_ai.py:
error_from_http`). Test port từ desktop giữ tên ca kiểm tra để đối chiếu.

Thứ tự: A → B (thuần JVM) → C (client, dispatcher, test HTTP giả) → D (Android, gửi thật) → E. Phần B và C chạy hết trên
PC, chưa cần điện thoại.

### A. Khung

- [x] **A1. Module `:core:gemini`** (plugin `guzz.jvm.library`): OkHttp, kotlinx.serialization, coroutines; test thêm
  `mockwebserver3`, `kotlinx-coroutines-test`. `:app` phụ thuộc vào nó. Phiên bản thêm vào `libs.versions.toml`.
  **Kiểm:** `.\gradlew projects` có `:core:gemini`; `.\gradlew assembleSideloadDebug test lint` xanh.
- [x] **A2. Cài đặt** `[GOOGLE_AI]`, `[FALLBACK]`, `[QUOTAS]`, `[SPEAKERS]` (mục "Mặc định kỹ thuật"), chặn trên / dưới
  theo `config.py`.
  **Kiểm:** `DesktopDefaultsTest` xanh với các mục mới; golden: `QUOTAS.lines` mặc định đọc ra đúng `model_quotas` của
  desktop.
- [x] **A3. Tắt sao lưu (D55):** `android:allowBackup="false"` trong manifest chính; bỏ mục Phát sinh từ Stage 0 về sao
  lưu (đã giải quyết).
  **Kiểm:** manifest đã gộp của cả hai flavor có `allowBackup="false"`; `.\gradlew lint` xanh.

### B. Hàm thuần và golden (`tools/golden/gen_gemini.py`)

Script chạy chính `google_ai.py`, `quota.py`, `config.py` của desktop trên các đầu vào dựng sẵn, ghi
`testdata/golden/gemini/*.json`.

- [x] **B1. Lỗi:** `ErrorKind`, `GoogleAIException` (`kind`, `httpStatus`, `retryAfter`, `violations`, `isDaily`,
  `stopQueue`, `isRetryable`), `QuotaViolation` (`isToken`, `unit`), `parseViolations`, `errorFromHttp`, `toSeconds`.
  Golden: các thân lỗi lấy nguyên từ `tests/test_google_ai.py` (gồm lỗi thật 17/09) cộng vài ca dựng thêm (Retry-After
  header, thân không phải JSON, 404 không nhắc "model", 408, 500…).
  **Kiểm:** `ErrorGoldenTest`: loại lỗi, thông điệp, thời gian chờ, danh sách vi phạm trùng desktop.
- [x] **B2. Yêu cầu / phản hồi:** `buildRequest` (model đa năng / `*-transcribe`; smart / verbatim; ngôn ngữ; từ vựng; tách
  người nói; mốc từng từ; tệp kèm; bản đồ người nói chèn vào prompt; `store`), `extractText`, `extractWords`,
  `checkStatus`, `parseInputTokens`, `guessMime`, `isTranscribeModel`, `maskApiKey`.
  **Kiểm:** golden trên một ma trận cài đặt → JSON yêu cầu trùng desktop (so như cây JSON); các phản hồi mẫu → chữ, từ,
  lỗi trùng desktop.
- [x] **B3. Giờ Thái Bình Dương:** `pacificDate`, `nextDailyReset`, `formatCountdown`, `formatLocalTime` (múi giờ máy
  truyền vào).
  **Kiểm:** golden trên khoảng 200 mốc quanh các lần đổi giờ 2025–2030; port `TestPacificTime`.
- [x] **B4. `RateLimiter`.**
  **Kiểm:** port đủ `TestRateLimiter` (7 ca) với đồng hồ giả.
- [x] **B5. `QuotaTracker`** (D52): đếm yêu cầu / token theo ngày Thái Bình Dương, khoá / mở khoá / hết khoá, học hạn mức,
  hạn mức khai báo / đã học / mặc định, đang chờ, lịch sử 300 sự kiện, ghi tệp `.tmp` rồi đổi tên.
  **Kiểm:** port `TestQuotaTracker` (5 ca); golden: cùng một chuỗi thao tác (đồng hồ giả) trên tracker desktop và
  Kotlin cho tệp JSON giống nhau (so như cây JSON).
- [x] **B6. Chuỗi model và ràng buộc:** port `model_chain`, `quota_for`, `valid_diarization_methods`,
  `model_matches_method`, `constraint_error`.
  **Kiểm:** golden trên ma trận (model chính × cách nhận diện × bật / tắt tự đổi model × danh sách dự phòng).

### C. Client và dispatcher (test HTTP giả bằng MockWebServer)

- [x] **C1. `GeminiClient`** (OkHttp): `upload` (resumable: start → upload, finalize), `waitFileReady`, `deleteFile`,
  `listModels`, `transcribeRaw` (chờ hạn mức trước khi tải lên, thử lại theo `chunk_retries` / `max_attempts`, chờ đúng
  thời gian Google yêu cầu, xoá tệp trên Google sau khi xong), log DEBUG như desktop (D53).
  **Kiểm:** port `TestRequest`, `TestErrorClassification`, `TestTranscribe`, `TestSpeakersAndAttachments` của
  `tests/test_google_ai.py` (bỏ `TestApiKey`: mobile không đọc tệp key); MockWebServer kiểm đúng đường dẫn, header
  `x-goog-api-key`, thân yêu cầu.
- [x] **C2. `ChunkDispatcher`** (port `transcribe.ChunkDispatcher` + `tracker_for`).
  **Kiểm:** port các ca của `TestAutoSwitchModel` thuộc phạm vi dispatcher (khoá theo ngày rồi đổi model, bộ lọc chặn một
  đoạn, quá tải khoá ngắn, tắt khoá quá tải, bộ lọc chặn mọi model, mọi model khoá lâu → dừng hàng đợi, khoá ngắn → chờ,
  chạm hạn mức ngày → khoá trước khi gửi, model không hợp cách nhận diện); ca "bản chép lời rỗng" là của pipeline →
  Phát sinh Stage 4.
- [x] **C3. Chuỗi lỗi 17/09:** port `TestReplayRealLog` (`test_daily_quota_then_high_demand_moves_to_flash_lite`,
  `test_input_blocked_does_not_stop_file`) chạy qua client thật + dispatcher, chỉ HTTP là giả.
  **Kiểm:** cùng thứ tự yêu cầu HTTP, cùng model cuối cùng, cùng sự kiện trong tracker như desktop.
- [x] **C4. Không lộ key:** mọi test của C1–C3 chạy với log mức DEBUG ghi vào bộ nhớ; thêm test quét toàn bộ log và thông
  điệp lỗi không chứa key.
  **Kiểm:** test xanh; cố ý cho thông điệp lỗi của Google chứa key (giả) thì vẫn không lọt (che bằng `maskApiKey`).

### D. Android và gửi thật

- [x] **D1. `ApiKeyStore` bản Keystore** ở `:app`: lưu, đọc, xoá; khoá AES-GCM trong Android Keystore.
  **Kiểm:** test thiết bị (`:app`): lưu rồi đọc ra đúng; xoá rồi đọc ra rỗng; tệp trên đĩa không chứa key dạng chữ.
- [x] **D2. Trang Google AI** (D56, Compose, vi / en): nhập key (hiện dạng che), Lưu / Xoá, Kiểm tra kết nối
  (`list_models` → số model hoặc lỗi theo loại), chọn model chính (danh sách lấy từ `list_models`, nhập tay được). Mở từ
  màn hình chính.
  **Kiểm:** trên máy: nhập key sai → báo lỗi cấu hình; key đúng → thấy danh sách model; đổi model → `GOOGLE_AI.model` đổi.
- [x] **D3. Gửi thử ở màn hình thử (bản debug):** nút + lệnh adb gửi `doan_001.flac` của một thư mục đã cắt (Stage 2) qua
  `ChunkDispatcher`; ghi kết quả (model, chữ, số từ có mốc, token, thời gian, sự kiện tracker) ra JSON.
  **Kiểm:** chạy bằng adb khi chưa lưu key hoặc key sai → JSON ghi lỗi loại `cau_hinh`, không gửi gì thêm; phần gửi
  thành công kiểm ở D4.
- [x] **D4. Gửi thật (D57), hỏi người dùng ngay trước khi gửi:** đoạn 1 phút (cắt bằng Stage 2 từ một bài thử):
  1. `list_models`: ghi model nào trong chuỗi mặc định không còn (D58);
  2. model chính (`gemini-3.5-flash-lite`) với prompt mặc định;
  3. `gemini-3.5-transcribe`, smart;
  4. `gemini-3.5-transcribe`, mốc từng từ.

  Yêu cầu nào bị 400 vì định dạng thì đổi theo tài liệu hôm nay (`file` / `file_uri`, `mode` chuỗi), ghi Nhật ký, gửi lại.
  **Kiểm:** 4 yêu cầu thành công; chữ trả về hợp lý; tracker ghi đúng số yêu cầu / token; tệp trên Google đã bị xoá.

### E. Đóng stage

- [x] **E1. Tài liệu:** CLAUDE.md thêm lệnh (golden Gemini, gửi thử bằng adb); README §3 nếu module khác dự kiến.
  **Kiểm:** chạy lại từng lệnh đã chép.
- [x] **E2. Đóng stage:** tick đủ "Tiêu chí hoàn thành"; Stage 3 → ✅; "Việc tiếp theo" → plan Stage 4; commit, push.
  **Kiểm:** `git status -sb` báo `## main...origin/main`; `git grep` không thấy key nào (chuỗi `AIza`).

## Nhật ký

- **2026-11-11, plan.** Đọc `google_ai.py`, `quota.py`, `transcribe.ChunkDispatcher`, các mục `[GOOGLE_AI]` /
  `[FALLBACK]` / `[QUOTAS]`, danh sách test của desktop; kiểm tài liệu Google hôm nay (mục "Thông tin bên ngoài"). Hỏi
  người dùng 8 câu trắc nghiệm, cả 8 chọn Recommended (D51–D58).
- **2026-11-11, A–C xong** (chạy hết trên PC). `:core:gemini` có `GeminiErrors`, `Interactions`, `GeminiConfig`,
  `PacificTime`, `RateLimiter`, `QuotaTracker`, `GeminiClient`, `ChunkDispatcher`, `ApiKeyStore`; `.\gradlew test` 857
  ca ở `:core:gemini` xanh; thử đỏ (`MAX_EVENTS` = 299) thì golden tracker đỏ. Khác kế hoạch / chi tiết đáng nhớ:
  - A2: `TIMEOUT_SECONDS`, `CHUNK_RETRIES` chặn dưới 0 (desktop không chặn) để số âm không lọt vào OkHttp. Dòng
    `QUOTAS.lines` hỏng thì bỏ qua dòng đó (desktop ném lỗi cả cấu hình).
  - A3: thêm `android:dataExtractionRules` loại mọi thư mục: từ Android 12, `allowBackup="false"` không chặn chuyển máy
    (device-to-device), mà D55 muốn key không đi sang máy khác. Thêm quyền `INTERNET` (trước giờ app chưa gọi mạng).
  - B: một script `tools/golden/gen_gemini.py` ra 6 tệp `testdata/golden/gemini/*.json` (khoảng 830 KB). Câu chữ của app
    (thông điệp lỗi do app đặt, chi tiết khoá "Đã gửi 2/2 yêu cầu…") viết có dấu ở bản Kotlin, desktop không dấu: test
    so phần do Google trả về nguyên văn, phần của app so sau khi bỏ dấu. Tệp hạn mức Kotlin bỏ trường `null` (desktop
    ghi `null`), golden coi null như không có; đọc lại vẫn ra đúng.
  - C1: key chỉ ở header; mọi thông điệp lỗi / log DEBUG ra khỏi client đều thay key bằng `maskApiKey` (C4). Bước
    "upload, finalize" gửi thân không có `Content-Type` (urllib của desktop tự thêm
    `application/x-www-form-urlencoded`): kiểm ở D4. Hạn giờ: OkHttp không đặt hạn đọc / ghi (mặc định 10 giây quá
    ngắn), mỗi lần gọi đặt `Call.timeout()` cho cả lần gọi (60 giây cho Files API, `timeout_seconds` cho Interactions).
    Bấm Dừng (huỷ coroutine) vẫn xoá tệp đã tải lên (`NonCancellable`), như khối `finally` của desktop.
  - C2: kiểm ràng buộc model ↔ cách nhận diện ngay khi tạo `ChunkDispatcher` (ném `ConstraintException`), thay vì ở
    đầu `transcribe_chunks`, để pipeline Stage 4 không quên.
  - C3: thay vì chỉ port hai test `TestReplayRealLog`, golden `replay.json` chạy 5 kịch bản (2 của desktop + 429 theo
    phút chờ rồi gửi lại, 404 khoá vĩnh viễn, chạm hạn mức ngày khai báo) trên `Client` + `ChunkDispatcher` thật của
    desktop với đồng hồ giả; Kotlin phải ra cùng thứ tự yêu cầu HTTP, cùng thời gian chờ, cùng sổ hạn mức. Hai test
    desktop vẫn giữ tên và khẳng định trong `ReplayGoldenTest`.
- **2026-11-11, D xong** (Xiaomi 15T qua USB).
  - D1: `KeystoreApiKeyStore` (AES-GCM 256, khoá `guzz_api_key` trong Android Keystore, bản mã ở
    `files/secrets/api_key.bin`; mất khoá thì coi như chưa có key và xoá tệp). Test thiết bị 4 ca xanh. HyperOS lại huỷ
    cài APK test qua `connectedSideloadDebugAndroidTest` (`INSTALL_FAILED_USER_RESTRICTED`) và bước dọn của Gradle gỡ
    luôn app: cài tay cả hai APK (máy mở khoá, bấm đồng ý) rồi `am instrument`, như lệnh trong CLAUDE.md.
  - D2: `GoogleAiActivity` mở từ nút "Google AI" ở màn hình chính. Kiểm trên máy: key đúng → 62 model; key bịa → "Key
    sai hoặc không có quyền" (`cau_hinh`, HTTP 400 API_KEY_INVALID); chọn model trong danh sách → log "đổi model chính".
  - D3: gửi thử nằm trong màn hình thử của Stage 2 (`GeminiDebug`, nút + adb, `tools/device/gemini_debug.ps1`), ghi
    `files/debug-gemini/<bài>_<model>_<chế độ>.json` gồm cả các lần gọi HTTP (phương thức, đường dẫn, mã; không header).
    Chạy khi chưa lưu key → `cau_hinh`, không có lần gọi HTTP nào. Prompt mặc định: `prompt.txt` của desktop đóng gói
    làm resource của `:core:gemini` (`DefaultPrompt`, golden so với desktop).
  - **Sự cố lúc thử:** khi chưa tìm thấy trang Google AI, API key bị dán vào ô "Model" của màn hình thử và bấm Gửi. App
    không gửi gì (chưa có key) nhưng chuỗi đó vào logcat, `guzz.log` và tên tệp kết quả. Đã dọn trên máy (xoá
    `debug-gemini`, `guzz.log*`, `adb logcat -c`), báo người dùng nên thu hồi key. Sửa: `isValidModelName` (chữ thường,
    số, `.`, `_`, `-`) chặn ở ô model của cả trang Google AI lẫn màn hình thử, chuỗi sai không được lưu hay ghi log; có
    test trong `KeyLeakTest`.
  - D4 (người dùng đồng ý 4 yêu cầu + thử key bịa), đoạn `B1_1m` = phút 10–11 của bài B1 (47,9 giây sau cắt lặng), dữ
    liệu ở `D:\GuzzMobileData\stage-03`:
    1. `list_models`: 62 model, đủ 4 model của chuỗi dự phòng mặc định (kể cả `gemini-3.5-flash` mà trang tài liệu không
       liệt kê) → D58 không có gì cho Phát sinh Stage 8.
    2. `gemini-3.5-flash-lite` + prompt mặc định: 562 ký tự (Việt + Anh), 1317 token, 9,0 s.
    3. `gemini-3.5-transcribe` smart: 170 ký tự, 1199 token, 7,1 s.
    4. `gemini-3.5-transcribe` mốc từng từ: 86 ký tự, 15 từ có mốc (`word_info`, `start_offset` / `end_offset` đọc đúng),
       1199 token, 8,0 s.

    Cả 4 trả 200 ngay với định dạng yêu cầu của desktop (`{"type": "audio", "uri", "mime_type"}`, `mode` là object):
    không phải đổi theo tài liệu mới. Bước "upload, finalize" không có `Content-Type` vẫn được. Mỗi lần gửi: 2 POST
    tải lên, 1 POST Interactions, 1 DELETE trả 200 (tệp đã xoá). Tracker: flash-lite 1 yêu cầu / 1317 token,
    transcribe 2 yêu cầu / 2398 token, không sự kiện lỗi. `guzz.log`, logcat và JSON kết quả không chứa key.
    Chất lượng: `*-transcribe` không có `languages` cho chữ rất thưa trên đoạn lớp học ồn, lẫn Việt / Anh (smart chỉ ra
    câu tiếng Anh) → Phát sinh Stage 8.

## Phát sinh

- (Từ Stage 0, 2026-10-31) Manifest của `:app` chưa khai báo `android:allowBackup` / `dataExtractionRules`, tức Android
  mặc định sao lưu dữ liệu app (gồm DataStore `files/datastore/settings.preferences_pb`) lên Google Drive và chuyển sang
  máy mới. Khi bắt đầu lưu API key, chốt chính sách sao lưu: ít nhất loại key ra khỏi bản sao lưu và chuyển máy.
  → Đã chốt lúc plan: D55, tắt sao lưu toàn app. Đã làm ở A3 (kèm `dataExtractionRules`).
