# Stage 11 — Xử lý âm thanh trên server (Modal)

**Trạng thái:** 📝 đã plan chi tiết 2026-11-21 · **Cần xong trước:** 5, 7, 9

## Mục tiêu

Đưa phần nặng nhất ra khỏi điện thoại: **làm sạch âm thanh, cắt đoạn và tách giọng chạy trên một server riêng trên
Modal**. Điện thoại chỉ tách luồng âm thanh, tải lên, nhận về các đoạn FLAC cùng lượt nói, rồi gửi Google AI Studio như
hiện nay. Khi server không dùng được, điện thoại tự làm như cũ, coi đó là đường dự phòng.

Lý do: tách giọng trên máy vẫn lâu, tốn pin và làm nóng máy. Stage 9 đo bài 2 giờ trên bản release: 41 phút, `:diarize`
507 MB, pin 39,6 °C.

## Phạm vi

### Làm

- **Server** (`:server`, Kotlin JVM), đóng gói thành một tệp `.jar`:
  - giải mã bằng ffmpeg, rồi trộn kênh và resample bằng chính code của điện thoại;
  - làm sạch, cắt đoạn bằng `ChunkPreparer` / `AudioCleaner` của `:core:audio`; mã hoá đoạn FLAC bằng ffmpeg;
  - tách giọng bằng `ChunkedDiarization` của `:core:speaker` với sherpa-onnx 2.0.1 qua **Java API** (cùng model, cùng
    cấu hình D74, D80, D81);
  - ra đúng các tệp mà cache của điện thoại dùng: `danh_sach_doan.json`, `doan_NNN.flac`, `nguoi_noi.<mã>.json`,
    `sach.flac` khi bật lưu âm thanh sạch.
- **Vỏ Modal** (`server/modal_app.py`): image có ffmpeg + JRE 21 + `.jar` + model; API job (tải lên, xem tiến độ, tải
  kết quả về, xoá), có **proxy auth**; kết quả lưu ở Volume; dọn tệp theo D147.
- **Giao thức job** dùng chung cho app và server (`:core:remote`, JVM): yêu cầu, trạng thái, danh sách tệp kết quả, số
  phiên bản giao thức.
- **Điện thoại:**
  - tách luồng âm thanh của video bằng MediaMuxer (không giải mã); tệp âm thanh thì gửi nguyên;
  - client HTTP (OkHttp): tải lên dạng stream, hỏi tiến độ, tải về rồi giải nén vào thư mục tạm;
  - `FilePipeline` hỏi server trước bước (1); lỗi thì tự làm trên máy (D145); bị giết giữa chừng thì nối lại job (D64);
  - trang Cài đặt "Server xử lý": công tắc, URL, token, nút Kiểm tra kết nối; vi / en.
- Tài liệu `docs/server.md`: tạo tài khoản Modal, deploy, tạo token, nhập vào app, chi phí ước tính.

### Không làm

- Gửi Gemini từ server (D142). Key, hạn mức, đổi model, cache chép lời vẫn ở điện thoại.
- pyannote community-1 / GPU (đưa vào Phát sinh, theo ý người dùng).
- Công tắc "chỉ qua Wi-Fi" (D146).
- Tải lên tệp kế tiếp trong lúc đang chép lời tệp trước. Hàng đợi vẫn chạy lần lượt (D60), xem Phát sinh.
- Server cho nhiều người dùng, tài khoản, giao diện web.
- Bỏ đường xử lý trên máy: đây là đường dự phòng, giữ nguyên code và test.

## Nguồn tham khảo

- Mobile: `FilePipeline` (bước 1–2, cache theo `audioKey` / `speakerKey`), `ChunkSource`, `ChunkPreparer`,
  `ChunkedDiarization`, `PieceEngine`, `SherpaPieceDiarizer`, `AudioPreparation`, `AudioTracks`, `Downmix`,
  `ApiKeyStore`, `GeminiClient` (cách dùng OkHttp).
- Desktop: `audio_io.py` (lệnh ffmpeg), `diarization_worker.py` (dạng JSON lượt nói, báo tiến độ).
- Stage 1–2: `spikes/media/compare_pcm.py`, `tools/device/compare_ffmpeg.py`, số đo MediaCodec ↔ ffmpeg (SNR 70,4 dB
  m4a, 51,6 dB mp4).
- Modal: [Web endpoints](https://modal.com/docs/guide/webhooks), [Proxy auth](https://modal.com/docs/guide/webhook-proxy-auth),
  [Pricing](https://modal.com/pricing).

## Kết quả bàn giao

- Server chạy trên Modal của người dùng, deploy bằng một lệnh.
- Bản app mới: bật "Server xử lý" thì bài dài không còn tách giọng trên máy; server lỗi thì vẫn ra bản chép lời.

## Tiêu chí hoàn thành

- [ ] Bài 2 giờ trên Xiaomi 15T qua server (Wi-Fi, màn hình tắt): từ lúc bắt đầu tới lúc có đủ đoạn và lượt nói
  **≤ 10 phút**; phần này tốn **≤ 3% pin**, pin nóng thêm **≤ 3 °C**.
- [ ] Chi phí Modal cho một bài 2 giờ **≤ $0,25** (với $30 / tháng là ≥ 120 bài).
- [ ] Kết quả như đường điện thoại: đoạn FLAC của server so với điện thoại **SNR ≥ 50 dB**, mốc cắt lệch ≤ 1 khối; lượt
  nói **DER giữa server và điện thoại ≤ 5%** ở B1–B5; bài nào điện thoại đạt D29 thì server cũng đạt.
- [ ] Dự phòng: mất mạng, token sai, server lỗi 5xx, quá thời gian chờ, khác phiên bản giao thức, đều tự làm trên máy
  và ra `.txt`, log ghi lý do. Bấm Dừng thì không chuyển sang máy.
- [ ] Bị giết lúc server đang xử lý: mở lại thì nối lại job, không tải lên lại.
- [ ] Trên Volume không còn tệp nào cũ hơn 24 giờ (kiểm sau khi việc dọn chạy).
- [ ] `.\gradlew test` và `lint` qua; test cũ của đường trên máy vẫn qua.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt hết ngày 2026-11-21, xem mục [Quyết định](#quyết-định).

## Quyết định

Hỏi người dùng ngày 2026-11-21. D140 và D141 người dùng tự nêu; từ D142 đến D148 người dùng chọn phương án
Recommended. Mã số đánh tiếp bảng ở [README §2](README.md#2-quyết-định-đã-chốt).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D140 | Server miễn phí nào | **Modal**, gói Starter ($30 tín dụng / tháng, không cần thẻ); tách giọng **giữ sherpa-onnx** (D3); pyannote để sau (Phát sinh) | Chỉ chạy khi có việc. Oracle Always Free từ 15/06/2026 còn 2 nhân / 12 GB; gói CPU miễn phí của HF Spaces không còn đáng tin. sherpa-onnx đủ tốt và đủ nhanh với người dùng. |
| D141 | Server làm phần nào | **Làm sạch + cắt đoạn + tách giọng**; điện thoại là **dự phòng** | Bỏ hết phần nặng khỏi máy, không chỉ riêng tách giọng. |
| D142 | Ai gửi Google AI Studio | **Điện thoại**; server trả đoạn FLAC + lượt nói | Key, hạn mức, đổi model, cache chép lời giữ nguyên. Server không phải chờ hạn mức (chờ cũng tốn tín dụng). Tải về khoảng 10 MB / 10 phút bài. |
| D143 | Code server viết bằng gì | **Kotlin**, dùng lại `:core:audio`, `:core:speaker` + sherpa-onnx Java API, giải mã bằng ffmpeg; Modal là vỏ Python gọi `.jar` | Giống hệt đường dự phòng, golden test đã có. Không có bản Python thứ hai phải giữ cho khớp. |
| D144 | Điện thoại tải gì lên | **Tách luồng âm thanh, không giải mã** (MediaMuxer); tệp âm thanh gửi nguyên | Chỉ mất vài giây, gần như không tốn pin; video 1 GB còn vài chục MB. |
| D145 | Khi nào điện thoại tự làm | **Tự chuyển khi server lỗi**, có công tắc "Xử lý trên server" | Lúc nào cũng ra bản chép lời; log và thông báo ghi lý do. |
| D146 | Dùng mạng di động | **Vẫn dùng server**, không thêm công tắc | Như D118; các đoạn gửi Gemini vốn đã đi qua mạng di động. |
| D147 | Lưu tệp trên server | **Tệp gốc xoá khi xử lý xong; kết quả xoá khi điện thoại báo đã nhận; mỗi ngày dọn mọi thứ cũ hơn 24 giờ** | Điện thoại bị giết giữa chừng vẫn kịp nối lại, mà không giữ bản ghi lâu. |
| D148 | Thử bằng bài thật | **Được, hỏi trước lần đầu**: trước lần đầu tải bài thật lên Modal và trước lần deploy đầu, sau đó không hỏi lại | Đo được thời gian và chi phí thật. Modal là bên thứ ba. |

## Rủi ro

1. **sherpa-onnx 2.0.1 không có Java API / JNI cho linux-x64 (và win-x64 để chạy test trên PC), hoặc API khác bản
   Kotlin AAR.** → Kiểm đầu tiên (A2). Không có thì build JNI từ mã nguồn trong image, hoặc hỏi lại.
2. **Modal giới hạn kích thước body / thời gian của một yêu cầu HTTP.** Chưa tìm thấy con số trong tài liệu. → Đo ở A3;
   giới hạn nhỏ thì tải lên nhiều phần.
3. **Bộ giải mã AAC của ffmpeg và MediaCodec khác nhau một chút.** → Chấp nhận như Stage 2 (SNR ≥ 50 dB). Cache dùng
   chung `audioKey`: một bài chỉ có một bộ đoạn, server hay máy làm đều được.
4. **Gói miễn phí đổi điều kiện** (Oracle, HF đều đã cắt trong năm 2026). → Phần chính là `.jar` + ffmpeg, chạy được ở
   chỗ khác (Docker trên PC nhà qua Tailscale, Phát sinh). Điện thoại luôn còn đường dự phòng.
5. **Riêng tư:** bản ghi đi qua Modal. → Proxy auth, tệp gốc xoá ngay, kết quả không quá 24 giờ, log server không ghi
   nội dung.
6. **Lượt nói tính trên x86 lệch so với ARM** (số thực, số luồng khác). → Tiêu chí DER ≤ 5% giữa hai bên.

## Kế hoạch chi tiết

Thứ tự: A (kiểm những gì có thể làm đổ kế hoạch) → B, C (JVM, chạy hết trên PC) → D (Modal) → E, F (điện thoại) → G (đo)
→ H. Thư mục tạm, dữ liệu đo: `D:\GuzzMobileData\stage-11` (ngoài repo).

Thiết kế chính (để các bước bên dưới khớp nhau):

- Server làm **cả hai việc trong một job**: cắt đoạn và tách giọng trên cùng một âm thanh sạch, nên mốc lượt nói khớp
  TimeMap của các đoạn. Cache điện thoại thiếu phần nào (danh sách đoạn, hoặc lượt nói khi bật cách `pyannote` /
  `hybrid`) thì gọi job làm lại cả hai, ghi đè cả hai. Không ghép đoạn của bên này với lượt nói của bên kia.
- Điện thoại gửi kèm `audioKey` và `speakerKey` đã tính. Server ghi đúng tên tệp cache, nên sau khi giải nén, bước (1)
  và (2) của `FilePipeline` chỉ thấy cache trúng như bình thường, bước (3) trở đi không đổi.
- Lỗi nào thì chuyển sang máy (D145): mạng, HTTP 401 / 403 / 404 / 413 / 429 / 5xx, quá thời gian, khác phiên bản giao
  thức, tệp kết quả hỏng. **Không chuyển:** Dừng (CancellationException); "không có luồng âm thanh" / "không còn tiếng
  nói" do server báo, vì làm trên máy cũng ra đúng lỗi đó.

### A. Tài khoản và những thứ phải kiểm trước

- [ ] A1. Người dùng tạo tài khoản Modal (Starter). Cài CLI `modal` vào venv `D:\GuzzMobileData\tools\modal-venv`.
  Người dùng tự chạy `modal token new` (đăng nhập bằng trình duyệt). Kiểm: `modal profile current` ra đúng workspace.
- [ ] A2. Kiểm GitHub Releases của sherpa-onnx v2.0.1 có jar Java API và JNI cho `linux-x64` và `win-x64` không. Có thì
  thêm vào `gradle/guzz-deps.txt` kèm SHA-256 (D79). Kiểm: build tải về và khớp mã băm; một test JVM nhỏ nạp
  `OfflineSpeakerDiarization` rồi chạy `0-four-speakers-zh.wav` ra 4 người. Không có thì ghi Nhật ký và hỏi (Rủi ro 1).
- [ ] A3. (Hỏi trước lần deploy đầu, D148.) Spike `server/spike_app.py`: image debian-slim + ffmpeg + JRE 21, một
  endpoint `requires_proxy_auth=True`, tạo proxy token. Đo rồi ghi Nhật ký: tên header auth đúng (không token thì ra
  401); body lớn nhất nhận được (thử 100 MB và 300 MB); thời gian khởi động lạnh; giá CPU / RAM theo trang Usage. Xong
  thì `modal app stop` spike.

### B. Code dùng chung (JVM)

- [ ] B1. Chuyển `Downmix` (không phụ thuộc Android) từ `:core:media` sang `:core:audio`. Kiểm: `:core:media` và
  `:core:audio` test qua, `connectedDebugAndroidTest` của `:core:media` qua trên máy ảo.
- [ ] B2. Phần chung của `SherpaPieceDiarizer` không phụ thuộc kiểu API (lấy embedding trung bình ≤ 30 s, chuẩn hoá L2,
  dựng `Piece`) chuyển vào `:core:speaker`, để bản Android và bản JVM gọi chung. Kiểm: `:core:speaker:test` qua;
  `DiarizationDeviceTest` qua trên máy ảo.
- [ ] B3. Module mới `:core:remote` (JVM, kotlinx.serialization): `JobRequest` (phiên bản giao thức, `audioKey`,
  `speakerKey`, `ChunkSettings`, `CleanSettings`, `saveClean`, bật / tắt tách giọng, `SpeakerBounds`,
  `DiarizationSettings`, `runOnOriginal`, tên tệp gốc), `JobStatus` (`queued` / `running` / `done` / `failed`, bước,
  phần trăm, mã lỗi), `JobResult` (danh sách tệp, kích thước, SHA-256). Kiểm: test JSON đi rồi về; mẫu JSON trong
  `docs/server.md` đọc được bằng chính các class này.

### C. Server `:server` (JVM, chạy trên PC trước)

- [ ] C1. Module `:server` (Kotlin JVM, `application`) có task `serverJar` gom thành một `.jar`. Lệnh:
  `java -jar guzz-server.jar process --in <tệp> --request req.json --out <thư mục> --status status.json`. Kiểm: chạy
  trên tệp thử `testdata/media` ra đủ tệp.
- [ ] C2. Giải mã: ffmpeg ra float, giữ tần số mẫu và số kênh gốc → `Downmix` → `SincResampler` → `goc.pcm`, như
  `AudioPreparation`. Kiểm: so với `goc.pcm` điện thoại kéo về bằng `audio_debug.ps1 -PullPcm` (tệp thử và `L1_20m`):
  SNR ≥ 50 dB, độ dài lệch ≤ 0,02 s.
- [ ] C3. `ChunkEncoder` gọi `ffmpeg -c:a flac` (PCM đẩy vào stdin), rồi `ChunkPreparer` như trên máy. Kiểm: FLAC giải
  mã lại trùng từng bit với đoạn PCM; danh sách đoạn / TimeMap trùng với chạy `ChunkPreparer` trên cùng `goc.pcm`.
- [ ] C4. `SherpaJvmPieceEngine` (Java API) + `ChunkedDiarization`, số luồng = số nhân của container. Ra
  `nguoi_noi.<speakerKey>.json`. Kiểm: tệp thử có golden Stage 5 ra đúng; có `GUZZ_SPIKE_DATA` thì B1–B5: DER so với
  lượt nói điện thoại (Stage 5) ≤ 5%.
- [ ] C5. Tiến độ ghi `status.json` (ghi tệp tạm rồi đổi tên); nhận SIGTERM thì dừng ở điểm kiểm tiếp theo, thoát mã
  riêng; lỗi "không có luồng âm thanh" / "không còn tiếng nói" có mã riêng. Kiểm: test JVM cho từng mã thoát.

### D. Vỏ Modal (`server/modal_app.py`)

- [ ] D1. Image: debian-slim + ffmpeg + `openjdk-21-jre-headless` + `guzz-server.jar` + model. Model tải lúc build
  image theo `gradle/guzz-deps.txt`, sai SHA-256 thì dừng. Kiểm: `modal run` một hàm in phiên bản ffmpeg / java và
  băm model.
- [ ] D2. API (FastAPI, `requires_proxy_auth=True`): `GET /health` (phiên bản giao thức + server); `POST /jobs` (nhận
  `JobRequest` + tệp dạng stream vào Volume `guzz-jobs`, khởi chạy hàm xử lý, trả mã job); `GET /jobs/{id}`
  (`JobStatus`); `GET /jobs/{id}/result` (tar các tệp kết quả); `DELETE /jobs/{id}` (huỷ nếu đang chạy, xoá tệp). Hàm
  xử lý: số CPU / RAM theo số đo ở A3 (bắt đầu 4 nhân, 8 GB), timeout 1 giờ. Nếu A3 thấy giới hạn body nhỏ thì tải
  lên theo phần.
- [ ] D3. Dọn tệp (D147): tệp gốc xoá ngay khi job xong / lỗi; `DELETE` xoá kết quả; hàm chạy theo lịch hằng ngày xoá
  mọi job cũ hơn 24 giờ. Kiểm: job giả có mốc thời gian cũ bị xoá sau một lần gọi tay.
- [ ] D4. `tools/server/smoke.ps1`: gửi tệp thử lên server đã deploy, chờ xong, tải tar về, so với `.jar` chạy trên PC
  (đoạn FLAC trùng từng bit, lượt nói trùng), rồi `DELETE`. Kiểm: script ra OK; sai token thì ra 401.
- [ ] D5. `docs/server.md` (vi): tạo tài khoản, `modal deploy server/modal_app.py`, tạo proxy token, nhập URL / token
  vào app, chi phí ước tính theo số đo ở G3, cách tắt server. Thêm lệnh vào mục "Lệnh thường dùng" của CLAUDE.md.

### E. Điện thoại: client và pipeline

- [ ] E1. `RemoteProcessor` (interface ở `:core:pipeline`) + phần nối vào `FilePipeline`: bật server và cache thiếu
  thì gọi trước bước (1), giải nén vào thư mục tạm, rồi bước (1), (2) đọc cache như cũ. Lỗi thuộc danh sách chuyển
  sang máy thì log lý do và chạy đường cũ. Kiểm: test JVM với `RemoteProcessor` giả: thành công thì không gọi
  `ChunkSource.prepare` / `diarizer`; lỗi mạng thì gọi; Dừng thì không chuyển; "không còn tiếng nói" từ server thì xử lý
  như trên máy (`skipSilent`); cache đủ thì không gọi server.
- [ ] E2. Client OkHttp (`:core:remote`): tải lên dạng stream (không đọc cả tệp vào RAM), mỗi 5 giây hỏi trạng thái,
  tải tar về `.tmp` rồi giải nén, kiểm kích thước + SHA-256, xong thì `DELETE`. Kiểm: MockWebServer cho 401, 413,
  5xx, ngắt giữa lúc tải lên / tải về, tar thiếu tệp, khác phiên bản giao thức → đúng loại lỗi để E1 chuyển sang máy.
- [ ] E3. Làm tiếp (D64): mã job lưu `server_job.json` trong thư mục tạm. Chạy lại thì hỏi trạng thái job đó trước;
  job không còn (404) thì tải lên lại. Kiểm: test JVM; trên máy ảo giết app lúc server đang xử lý rồi mở lại thì log
  ghi "nối lại job", không có lần tải lên thứ hai.
- [ ] E4. Chuẩn bị tệp tải lên (`:core:media`): tệp âm thanh thì stream nguyên từ URI / đường dẫn; video thì chọn luồng
  theo `cfg.audioTrack` (`AudioTracks`), tách bằng MediaMuxer (AAC → `.m4a`; Opus / Vorbis → WebM / OGG khi API cho
  phép). Codec khác thì chạy trên máy và log lý do. Kiểm: test thiết bị với video trong `testdata/media`: độ dài luồng
  tách ra lệch ≤ 0,05 s so với gốc; video 1 giờ tách xong ≤ 10 giây.
- [ ] E5. Tiến độ: thêm bước con "Đang tải lên server", "Server đang xử lý" (kèm % của server), "Đang tải kết quả về"
  vào `FileProgress.step`, thông báo, trả lời lệnh `STATUS` (Stage 6). Kiểm: test JVM của `ProgressTracker`;
  `docs/intents.md` ghi thêm giá trị bước mới (test so tài liệu của `:feature:automation` qua).

### F. Cài đặt và giao diện

- [ ] F1. `SettingKeys`: `SERVER.enabled` (mặc định tắt), `SERVER.url`. Token lưu Keystore như `ApiKeyStore`. Xuất
  `config.txt`: có URL, không có token (như D112). Kiểm: `ConfigIniTest` qua; nhập `config.txt` desktop (không có mục
  `SERVER`) không lỗi.
- [ ] F2. Trang Cài đặt → "Server xử lý": công tắc, URL, token, nút Kiểm tra kết nối (`/health`, báo phiên bản / lỗi
  401 / không tới được), một dòng giải thích dự phòng. Chuỗi trong `strings.xml` vi / en. Kiểm:
  `pwsh .\tools\check_strings.ps1`; `ui_check.ps1` mặc định, `-Small -FontScale 2.0`, `-Dark -Lang en`.
- [ ] F3. Chuyển sang máy thì log ghi lý do; thông báo của mục ghi "Server lỗi (<lý do>), đang xử lý trên máy". Kiểm:
  máy ảo với URL sai.

### G. Thử và đo

- [ ] G1. Máy ảo `guzz_15t`, Gemini giả, server thật, tệp tổng hợp: luồng đủ ra `.txt`; năm kiểu lỗi ở Tiêu chí đều
  chuyển sang máy; Dừng giữa lúc tải lên / server xử lý / tải về thì không chuyển, và job trên server bị `DELETE`;
  E3 nối lại job.
- [ ] G2. (Hỏi trước lần đầu tải bài thật lên Modal, D148.) B1–B5 qua server: SNR đoạn FLAC và mốc cắt so với đường
  điện thoại; DER lượt nói so với điện thoại; D29 so với desktop. Ghi bảng vào Nhật ký.
- [ ] G3. Xiaomi 15T, bản release thử (`assembleSideloadRelease`, khoá debug), Wi-Fi, màn hình tắt, bài 2 giờ qua
  server: thời gian tới lúc có đoạn + lượt nói, pin, nhiệt (`measure_release.ps1`); chi phí Modal xem ở trang Usage.
  So với số đo Stage 9 (41 phút, 39,6 °C).
- [ ] G4. (Hỏi trước, gửi thật lên Google.) Một bài 1–3 phút qua server rồi Gemini thật, ≤ 3 yêu cầu: `.txt` đúng như
  đường điện thoại.

### H. Đóng stage

- [ ] H1. Kiểm đủ Tiêu chí; ghi `CHANGELOG.md`; nâng `guzz` lên 1.1.0.
- [ ] H2. (Hỏi trước tag / Release.) Phát hành 1.1.0 theo mục "Phát hành" trong CLAUDE.md.
- [ ] H3. Đổi trạng thái sang ✅ ở CLAUDE.md và README, cập nhật "Stage hiện tại" / "Việc tiếp theo".

## Nhật ký

## Phát sinh

- (Người dùng, 2026-11-21) **pyannote community-1 trên server** (GPU của Modal, cần token Hugging Face và đồng ý điều
  khoản model), như desktop: có thể cải thiện bài nhiều người (B4 trượt D29, D82) và bỏ được việc chia phần 20 phút
  (D81). Làm thành cách tách giọng thứ hai trên server, đo D29 trên B1–B5.
- Image Docker của cùng `.jar` để chạy trên PC ở nhà qua Tailscale, khi không muốn hoặc không dùng được Modal.
- Trong lúc chép lời tệp này, tải tệp kế tiếp lên server (D60 hiện chạy lần lượt).
- Extra intent (Stage 6) để ép một tệp chạy trên máy hoặc trên server.
