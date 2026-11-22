# Stage 11 — Xử lý âm thanh trên server (Modal)

**Trạng thái:** 🔨 đang làm (plan chi tiết 2026-11-21) · **Cần xong trước:** 5, 7, 9

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
Recommended. D149–D151 hỏi trong lúc làm (2026-11-21, 2026-11-22). Mã số đánh tiếp bảng ở [README §2](README.md#2-quyết-định-đã-chốt).

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
| D149 | Model CAM++ trên GitHub bị thay ngày 2026-11-19 (D1) | **Dùng tệp cũ `aa3c…` từ cache guzz-deps của máy deploy** | Giống hệt APK 1.0.0; bản mới xuất lại bằng PyTorch 2.14.1, khác nội dung. |
| D150 | Tải lên khi mạng chậm (G2) | **Tải lên theo phần** (~8 MB / phần, đứt thì gửi lại phần đó, bị giết thì nối tiếp từ phần đã có) | Modal cắt một yêu cầu kéo quá ~13 phút; mạng 85 KB/s không gửi nổi bài 170 MB, di động chậm cũng vậy. |
| D151 | B1, B2, B4 của G2 chưa gửi được | **Gửi lại khi mạng nhanh** (người dùng báo) | B1, B2 là hai bài điện thoại đạt D29. |

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

- [x] A1. Người dùng tạo tài khoản Modal (Starter). Cài CLI `modal` vào venv `D:\GuzzMobileData\tools\modal-venv`.
  Người dùng tự chạy `modal token new` (đăng nhập bằng trình duyệt). Kiểm: `modal profile current` ra đúng workspace.
- [x] A2. Kiểm GitHub Releases của sherpa-onnx v2.0.1 có jar Java API và JNI cho `linux-x64` và `win-x64` không. Có thì
  thêm vào `gradle/guzz-deps.txt` kèm SHA-256 (D79). Kiểm: build tải về và khớp mã băm; một test JVM nhỏ nạp
  `OfflineSpeakerDiarization` rồi chạy `0-four-speakers-zh.wav` ra 4 người. Không có thì ghi Nhật ký và hỏi (Rủi ro 1).
- [x] A3. (Hỏi trước lần deploy đầu, D148.) Spike `server/spike_app.py`: image debian-slim + ffmpeg + JRE 21, một
  endpoint `requires_proxy_auth=True`, tạo proxy token. Đo rồi ghi Nhật ký: tên header auth đúng (không token thì ra
  401); body lớn nhất nhận được (thử 100 MB và 300 MB); thời gian khởi động lạnh; giá CPU / RAM theo trang Usage. Xong
  thì `modal app stop` spike.

### B. Code dùng chung (JVM)

- [x] B1. Chuyển `Downmix` (không phụ thuộc Android) từ `:core:media` sang `:core:audio`. Kiểm: `:core:media` và
  `:core:audio` test qua, `connectedDebugAndroidTest` của `:core:media` qua trên máy ảo.
- [x] B2. Phần chung của `SherpaPieceDiarizer` không phụ thuộc kiểu API (lấy embedding trung bình ≤ 30 s, chuẩn hoá L2,
  dựng `Piece`) chuyển vào `:core:speaker`, để bản Android và bản JVM gọi chung. Kiểm: `:core:speaker:test` qua;
  `DiarizationDeviceTest` qua trên máy ảo.
- [x] B3. Module mới `:core:remote` (JVM, kotlinx.serialization): `JobRequest` (phiên bản giao thức, `audioKey`,
  `speakerKey`, `ChunkSettings`, `CleanSettings`, `saveClean`, bật / tắt tách giọng, `SpeakerBounds`,
  `DiarizationSettings`, `runOnOriginal`, tên tệp gốc), `JobStatus` (`queued` / `running` / `done` / `failed`, bước,
  phần trăm, mã lỗi), `JobResult` (danh sách tệp, kích thước, SHA-256). Kiểm: test JSON đi rồi về; mẫu JSON trong
  `docs/server.md` đọc được bằng chính các class này.

### C. Server `:server` (JVM, chạy trên PC trước)

- [x] C1. Module `:server` (Kotlin JVM, `application`) có task `serverJar` gom thành một `.jar`. Lệnh:
  `java -jar guzz-server.jar process --in <tệp> --request req.json --out <thư mục> --status status.json`. Kiểm: chạy
  trên tệp thử `testdata/media` ra đủ tệp.
- [ ] C2. Giải mã: ffmpeg ra float, giữ tần số mẫu và số kênh gốc → `Downmix` → `SincResampler` → `goc.pcm`, như
  `AudioPreparation`. Kiểm: so với `goc.pcm` điện thoại kéo về bằng `audio_debug.ps1 -PullPcm` (tệp thử và `L1_20m`):
  SNR ≥ 50 dB, độ dài lệch ≤ 0,02 s.
- [x] C3. `ChunkEncoder` gọi `ffmpeg -c:a flac` (PCM đẩy vào stdin), rồi `ChunkPreparer` như trên máy. Kiểm: FLAC giải
  mã lại trùng từng bit với đoạn PCM; danh sách đoạn / TimeMap trùng với chạy `ChunkPreparer` trên cùng `goc.pcm`.
- [ ] C4. `SherpaJvmPieceEngine` (Java API) + `ChunkedDiarization`, số luồng = số nhân của container. Ra
  `nguoi_noi.<speakerKey>.json`. Kiểm: tệp thử có golden Stage 5 ra đúng; có `GUZZ_SPIKE_DATA` thì B1–B5: DER so với
  lượt nói điện thoại (Stage 5) ≤ 5%.
- [x] C5. Tiến độ ghi `status.json` (ghi tệp tạm rồi đổi tên); nhận SIGTERM thì dừng ở điểm kiểm tiếp theo, thoát mã
  riêng; lỗi "không có luồng âm thanh" / "không còn tiếng nói" có mã riêng. Kiểm: test JVM cho từng mã thoát.

### D. Vỏ Modal (`server/modal_app.py`)

- [x] D1. Image: debian-slim + ffmpeg + `openjdk-21-jre-headless` + `guzz-server.jar` + model. Model tải lúc build
  image theo `gradle/guzz-deps.txt`, sai SHA-256 thì dừng. Kiểm: `modal run` một hàm in phiên bản ffmpeg / java và
  băm model.
- [x] D2. API (FastAPI, `requires_proxy_auth=True`): `GET /health` (phiên bản giao thức + server); `POST /jobs` (nhận
  `JobRequest` + tệp dạng stream vào Volume `guzz-jobs`, khởi chạy hàm xử lý, trả mã job); `GET /jobs/{id}`
  (`JobStatus`); `GET /jobs/{id}/result` (tar các tệp kết quả); `DELETE /jobs/{id}` (huỷ nếu đang chạy, xoá tệp). Hàm
  xử lý: số CPU / RAM theo số đo ở A3 (bắt đầu 4 nhân, 8 GB), timeout 1 giờ. Nếu A3 thấy giới hạn body nhỏ thì tải
  lên theo phần.
- [x] D3. Dọn tệp (D147): tệp gốc xoá ngay khi job xong / lỗi; `DELETE` xoá kết quả; hàm chạy theo lịch hằng ngày xoá
  mọi job cũ hơn 24 giờ. Kiểm: job giả có mốc thời gian cũ bị xoá sau một lần gọi tay.
- [x] D4. `tools/server/smoke.ps1`: gửi tệp thử lên server đã deploy, chờ xong, tải tar về, so với `.jar` chạy trên PC
  (đoạn FLAC trùng từng bit, lượt nói trùng), rồi `DELETE`. Kiểm: script ra OK; sai token thì ra 401.
- [ ] D5. `docs/server.md` (vi): tạo tài khoản, `modal deploy server/modal_app.py`, tạo proxy token, nhập URL / token
  vào app, chi phí ước tính theo số đo ở G3, cách tắt server. Thêm lệnh vào mục "Lệnh thường dùng" của CLAUDE.md.

### E. Điện thoại: client và pipeline

- [x] E1. `RemoteProcessor` (interface ở `:core:pipeline`) + phần nối vào `FilePipeline`: bật server và cache thiếu
  thì gọi trước bước (1), giải nén vào thư mục tạm, rồi bước (1), (2) đọc cache như cũ. Lỗi thuộc danh sách chuyển
  sang máy thì log lý do và chạy đường cũ. Kiểm: test JVM với `RemoteProcessor` giả: thành công thì không gọi
  `ChunkSource.prepare` / `diarizer`; lỗi mạng thì gọi; Dừng thì không chuyển; "không còn tiếng nói" từ server thì xử lý
  như trên máy (`skipSilent`); cache đủ thì không gọi server.
- [x] E2. Client OkHttp (`:core:remote`): tải lên dạng stream (không đọc cả tệp vào RAM), mỗi 5 giây hỏi trạng thái,
  tải tar về `.tmp` rồi giải nén, kiểm kích thước + SHA-256, xong thì `DELETE`. Kiểm: MockWebServer cho 401, 413,
  5xx, ngắt giữa lúc tải lên / tải về, tar thiếu tệp, khác phiên bản giao thức → đúng loại lỗi để E1 chuyển sang máy.
- [x] E3. Làm tiếp (D64): mã job lưu `server_job.json` trong thư mục tạm. Chạy lại thì hỏi trạng thái job đó trước;
  job không còn (404) thì tải lên lại. Kiểm: test JVM; trên máy ảo giết app lúc server đang xử lý rồi mở lại thì log
  ghi "nối lại job", không có lần tải lên thứ hai.
- [x] E4. Chuẩn bị tệp tải lên (`:core:media`): tệp âm thanh thì stream nguyên từ URI / đường dẫn; video thì chọn luồng
  theo `cfg.audioTrack` (`AudioTracks`), tách bằng MediaMuxer (AAC → `.m4a`; Opus / Vorbis → WebM / OGG khi API cho
  phép). Codec khác thì chạy trên máy và log lý do. Kiểm: test thiết bị với video trong `testdata/media`: độ dài luồng
  tách ra lệch ≤ 0,05 s so với gốc; video 1 giờ tách xong ≤ 10 giây.
- [x] E5. Tiến độ: thêm bước con "Đang tải lên server", "Server đang xử lý" (kèm % của server), "Đang tải kết quả về"
  vào `FileProgress.step`, thông báo, trả lời lệnh `STATUS` (Stage 6). Kiểm: test JVM của `ProgressTracker`;
  `docs/intents.md` ghi thêm giá trị bước mới (test so tài liệu của `:feature:automation` qua).

### F. Cài đặt và giao diện

- [x] F1. `SettingKeys`: `SERVER.enabled` (mặc định tắt), `SERVER.url`. Token lưu Keystore như `ApiKeyStore`. Xuất
  `config.txt`: có URL, không có token (như D112). Kiểm: `ConfigIniTest` qua; nhập `config.txt` desktop (không có mục
  `SERVER`) không lỗi.
- [x] F2. Trang Cài đặt → "Server xử lý": công tắc, URL, token, nút Kiểm tra kết nối (`/health`, báo phiên bản / lỗi
  401 / không tới được), một dòng giải thích dự phòng. Chuỗi trong `strings.xml` vi / en. Kiểm:
  `pwsh .\tools\check_strings.ps1`; `ui_check.ps1` mặc định, `-Small -FontScale 2.0`, `-Dark -Lang en`.
- [x] F3. Chuyển sang máy thì log ghi lý do; thông báo của mục ghi "Server lỗi (<lý do>), đang xử lý trên máy". Kiểm:
  máy ảo với URL sai.

### G. Thử và đo

- [x] G1. Máy ảo `guzz_15t`, Gemini giả, server thật, tệp tổng hợp: luồng đủ ra `.txt`; năm kiểu lỗi ở Tiêu chí đều
  chuyển sang máy; Dừng giữa lúc tải lên / server xử lý / tải về thì không chuyển, và job trên server bị `DELETE`;
  E3 nối lại job.
- [x] G2. (Hỏi trước lần đầu tải bài thật lên Modal, D148.) B1–B5 qua server: SNR đoạn FLAC và mốc cắt so với đường
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

- 2026-11-21 A1: CLI `modal` 1.6.4 cài bằng `uv` vào `D:\GuzzMobileData\tools\modal-venv` (Python 3.12); người dùng
  tự chạy `modal token new`, `modal profile current` ra workspace của người dùng.
- 2026-11-21 A2: release v2.0.1 có `sherpa-onnx-jvm-2.0.1.jar` (Java API, builder thay cho tham số đặt tên của AAR) và
  `sherpa-onnx-native-lib-{linux,win}-x64-2.0.1.jar` (JNI + onnxruntime, `LibraryUtils` tự giải nén từ classpath).
  Mã băm lấy từ trường `digest` của asset trong GitHub API, khớp với tệp tải về; thêm 3 dòng `jvm`, `jni-linux`,
  `jni-win` vào `gradle/guzz-deps.txt`. Test JVM ra trùng `run_sherpa.py` (±2 ms, cùng nhãn) với cả 4 cụm cố định (4
  người) lẫn ngưỡng 1,1 (tệp 57 s này ra 1 người, golden cũng vậy; kế hoạch ghi "ra 4 người" là với số cụm = 4). Java
  API mặc định `debug = true` (in cấu hình ra stderr): đặt `setDebug(false)`.
- 2026-11-21 B3: `ChunkSettings`, `CleanSettings`, `SpeakerBounds`, `DiarizationSettings` thêm `@Serializable` để đi
  thẳng trong `JobRequest`. Thêm so với kế hoạch: `streamIndex` (luồng âm thanh thứ mấy, `-map 0:a:N`), `saveExtracted`
  (`am_thanh.flac`, vì cache điện thoại đòi tệp này khi bật lưu âm thanh tách từ video), `sourceName` (khoá `nguon`).
  `JobStatus` mang luôn `JobResult` khi xong. Mẫu JSON nằm ở `docs/server.md`, test đọc lại bằng chính các class.
- 2026-11-21 C1–C5: `:server` nằm ở `server/` (cùng thư mục với vỏ Modal sau này). Giải mã: ffmpeg ra WAV float ra
  stdout (tần số / số kênh đọc từ tiêu đề, không cần ffprobe vì ffmpeg của desktop không kèm ffprobe), rồi `Downmix` →
  `SincResampler` → `Pcm16.fromFloatRounded` như `AudioDecoder`. Mã thoát: 0 xong, 1 lỗi khác, 2 yêu cầu sai, 3 huỷ,
  4 không có luồng âm thanh, 5 không còn tiếng nói. SIGTERM bắt bằng `sun.misc.Signal` (shutdown hook không đổi được
  mã thoát), sherpa-onnx không dừng giữa một phần được nên sau 20 s thì `halt`. Số luồng sherpa-onnx = số nhân JVM thấy
  (không nằm trong `signature`, mã cache không đổi). Log server không ghi tên tệp.
- 2026-11-21 A3 (người dùng đồng ý deploy lần đầu và tạo proxy token): `server/spike_app.py` (debian-slim Python 3.12 +
  apt `ffmpeg` 7.1.5 + `openjdk-21-jre-headless` 21.0.13, FastAPI) build image 10,6 s, deploy 61 s. Không token / sai
  `Modal-Secret` / sai `Authorization: Bearer <key>.<secret>` đều ra **401**; có hai cách gửi token: cặp header
  `Modal-Key` + `Modal-Secret`, hoặc một header `Authorization: Bearer <key>.<secret>` (app dùng cách sau). Khởi động
  lạnh **6,4 s** (gọi `/health` có chạy ffmpeg và java), ấm 1,0 s. Tải lên một yêu cầu (dữ liệu ngẫu nhiên, mạng nhà):
  100 MB trong 35,6 s, **300 MB trong 50,5 s**, SHA-256 khớp, không gặp giới hạn cỡ body → giữ một yêu cầu cho cả tệp,
  không chia phần (bài 2 giờ m4a vài chục tới hơn 100 MB). Giá (trang Pricing, 2026-11-21): CPU $0,0000131 / nhân vật
  lý / s (1 nhân = 2 vCPU, tối thiểu 0,125 nhân), RAM $0,00000222 / GiB / s, Volume $0,09 / GiB / tháng (1 TiB đầu miễn
  phí), Starter $30 tín dụng / tháng, 100 container. Hàm xử lý 4 nhân + 4 GiB ≈ $0,22 / giờ, nên tiêu chí $0,25 / bài 2
  giờ cần xử lý xong trong khoảng 1 giờ. Token lưu ở `D:\GuzzMobileData\keys\modal-proxy.json` (ngoài repo). Spike đã
  `modal app stop`.
- 2026-11-21 D1–D4 (người dùng đồng ý deploy `guzz-server`; theo D148 các lần deploy sau không hỏi lại): build image
  tải model embedding từ GitHub thì **sai SHA-256**: sherpa-onnx đã thay tệp
  `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` ngày 2026-11-19 (xuất lại bằng PyTorch 2.14.1, mã băm
  mới `53cab20b…`, khác nội dung chứ không chỉ khác tiêu đề). Hỏi người dùng → D149: image lấy đúng tệp `aa3c…` từ
  cache guzz-deps của máy deploy (`add_local_file`), `fetch_models` kiểm lại mã băm cả hai model; segmentation vẫn tải
  theo `guzz-deps.txt` (tệp tar không đổi). Trạng thái job đi qua `modal.Dict` (`guzz-job-status`), không phải Volume,
  để không phải commit Volume mỗi lần báo tiến độ; jar ghi `status.json` tối đa 1 lần / s. Khác phiên bản giao thức thì
  `POST /jobs` trả 409. Script thử viết bằng Python (`tools/server/smoke.py`, cần tar / SHA / giải mã FLAC) thay cho
  `smoke.ps1`. Kết quả: không token / sai token → 401; khởi động lạnh `/health` 5,6 s; `tone_wav_22k.wav` xong 6,3 s,
  `0-four-speakers-zh.wav` (tách giọng) 10,8 s tính cả khởi động hàm xử lý; danh sách đoạn và TimeMap trùng jar chạy
  trên PC, đoạn FLAC giải mã ra trùng từng mẫu (ffmpeg 7.1.5 của Debian và ffmpeg của desktop), lượt nói trùng ±2 ms;
  DELETE xong GET → 404. D3: job giả `created` cũ 25 giờ bị `cleanup` (gọi tay bằng `Function.from_name(...).remote()`)
  xoá, job mới giữ lại; Volume sạch sau thử.
- 2026-11-21/22 E1–E5, F1–F3 (code + test JVM; phần trên máy ảo / điện thoại còn chờ G1): `RemoteProcessor`,
  `UploadSource`, `RemoteClient` đặt ở `:core:remote` (không ở `:core:pipeline` như kế hoạch) để client và interface cùng
  chỗ; `:core:pipeline` phụ thuộc `:core:remote`. `ChunkSource.upload` mặc định `null` (nguồn không hỗ trợ thì làm trên
  máy). Bật / tắt server đi qua `PipelineConfig.serverEnabled` (`[SERVER] enabled`), đọc lại mỗi tệp; URL / token đọc lại
  mỗi tệp trong `ServerRemote` (`:feature:queue`). Token lưu `KeystoreApiKeyStore` riêng (`secrets/server_token.bin`,
  alias `guzz_server_token`), không vào `config.txt`; URL và công tắc thì có. `ProgressTracker` không cho tiến độ lùi
  (dùng server xong thì bước (1), (2) đọc cache báo mốc thấp hơn), trừ khi chuyển sang máy. F3: `QueueItem.serverFallback`
  (mã lý do) → thông báo / màn hình Hàng đợi thêm dòng "Server lỗi (<lý do>), đang xử lý trên máy". E5: thêm extra
  `step` cho sự kiện STATUS, giá trị ghi ở `docs/intents.md`. Test: `RemoteClientTest` 17 ca (thành công, 401, 413, 5xx,
  khác phiên bản ở 409 và trong trạng thái, ngắt lúc tải lên / tải về, tar thiếu tệp, mất mạng, quá giờ, nối lại job,
  job cũ 404 thì tải lên lại, Dừng thì DELETE, tar có header pax); MockWebServer cắt socket lúc tải lên vẫn có thể trả
  200 rỗng nên ca đó chấp nhận `network` hoặc `server` (đều chuyển sang máy). `RemotePipelineTest` 8 ca. `.\gradlew test
  assembleSideloadDebug`: 1754 test qua.
- 2026-11-22 G1 (máy ảo `guzz_15t`, Gemini giả, server thật, tệp tổng hợp ở `D:\GuzzMobileData\stage-11`: mẫu 4 người
  57 s của sherpa-onnx, bản ghép 20 phút của nó, `two_tracks.mp4`, video 1 giờ hình đen + sin; không dùng bản ghi của
  người dùng). Thêm lệnh adb `server` cho bản debug (`queue_debug.ps1 -Server on -ServerUrl … -ServerTokenFile …`:
  token đi qua tệp tạm, app đọc xong thì xoá). Kết quả:
  - luồng đủ (`four.wav`, tách giọng `pyannote`): tải lên 1,8 MB, server xong sau 27 s (tính cả khởi động lạnh), không
    cắt đoạn / tách giọng trên máy, ra `.txt`. Bài ghép 20 phút: tải lên 10,3 MB ~46 s (mạng của máy ảo), server ~2 phút;
  - chuyển sang máy, log ghi lý do: host không có (`network`), token sai (`auth`, 401), URL sai đường dẫn (`http`, 404).
    5xx, quá giờ, khác phiên bản giao thức chỉ thử bằng test JVM (`RemoteClientTest`), không dựng được trên server thật;
  - Dừng lúc tải lên / server đang xử lý / tải về: không chuyển sang máy, job bị xoá (GET → 404). Lỗi tìm ra và sửa:
    (1) huỷ tải lên để lại thư mục tải dở trên Volume → `POST /jobs` xoá thư mục khi luồng đọc lỗi; (2) Dừng lúc tải về:
    luồng ghi tar của server kẹt ở pipe, giữ tệp mở nên `DELETE` không commit được Volume (500) → ghi tar ngay trong
    generator, `DELETE` thử commit lại tối đa 10 lần; (3) điện thoại đọc nốt cả phần tải về rồi mới dừng (~27 s) →
    `RemoteClient` huỷ cuộc gọi khi coroutine bị huỷ, và lỗi do chính Dừng gây ra không ghi thành "Server lỗi". Sau sửa:
    Dừng lúc tải về dừng trong 1 s, job bị xoá;
  - E3: giết app lúc server đang xử lý, hệ thống khởi động lại service → log "Nối lại job …", chỉ một lần tải lên;
  - E4: `two_tracks.mp4` với `audio_track` = 1 qua server: `am_thanh.flac` là luồng 880 Hz (luồng thứ 2), dài 3,008 s so
    với 3,0 s. Video 1 giờ: tách luồng AAC 44 MB mất **33 s trên máy ảo** (tiêu chí ≤ 10 s): đo lại trên Xiaomi 15T ở G3;
  - B1, B2: `:core:media` 9/9, `:core:diarization` 3/3 test thiết bị trên máy ảo; F2: `check_strings.ps1` sạch, trang
    Server xử lý chụp ở chữ ×2 / 360 dp và tối / tiếng Anh, không cắt chữ (ảnh ở `D:\GuzzMobileData\stage-11\f2`).
  - C2 (so `goc.pcm` của server với điện thoại) chưa làm riêng: gộp vào G2, so SNR đoạn FLAC của hai đường trên B1–B5.
- 2026-11-22 G2 (người dùng đồng ý tải bài thật lên Modal, D148), làm dở: `tools/server/g2_real.py` gửi bài, tải kết quả
  về `D:\GuzzMobileData\stage-11\g2`; `tools/server/g2_compare.py` so. Cấu hình như lần chạy sherpa-onnx trên PC của
  Stage 5 (S5B1, t 1,1, P 20, không đặt số người tối thiểu / tối đa), làm sạch / cắt đoạn mặc định.
  - Tham chiếu thay cho "chạy lại điện thoại": đoạn / âm thanh sạch so với golden chuỗi làm sạch đủ của Stage 7 (Kotlin
    trên điện thoại đã khớp chuỗi này ≥ 86 dB); SNR tính theo khối 10 s có dò độ trễ ±64 mẫu, vì cắt lặng hai bên có thể
    lệch 1 ms ở một khoảng (B5: khoảng 82) làm cả phần sau lệch 16 mẫu. Lượt nói: bản PC S5B1 chạy trên `sach.flac` cũ
    của desktop (Stage 1, cắt lặng khác hẳn: B3 5118 s so với 6882 s), nên DER "server ↔ máy" ở đây lẫn cả khác biệt do
    âm thanh sạch khác, **không dùng được cho tiêu chí DER ≤ 5%**. D29 chấm bằng `evaluate.py --remap` (mốc server đổi về
    âm thanh gốc).

    | Bài | Đoạn (server / desktop) | Mốc cắt lệch tối đa | SNR trung vị / p5 | Khối < 50 dB | D29 server (DER) | D29 điện thoại, Stage 5 (DER) | Server xử lý |
    | --- | --- | --- | --- | --- | --- | --- | --- |
    | B3 (115 phút) | 12 / 12 | 5 mẫu | 70,2 / 57,8 dB | 0/688 | trượt (23,6%; 7 người / 5) | trượt (16,8%) | 700 s |
    | B5 (84 phút) | 9 / 9 | 21 mẫu | 65,6 / 56,2 dB | 2/498 | **đạt** (5,5%) | trượt (người chính) | 614 s |

  - B1, B2, B4 chưa tải lên được: lần đầu gửi song song cả 5 bài (~580 MB) thì 3 bài lớn bị ngắt SSL; gửi lại từng bài
    vẫn bị ngắt, curl đo tốc độ tải lên của mạng lúc đó chỉ **85 KB/s** (lúc A3 là 6 MB/s) và server cắt kết nối sau
    ~800 s ("Connection was reset"), B4 còn gặp lỗi phân giải tên miền. Tức là một yêu cầu tải lên kéo quá ~13 phút sẽ
    bị cắt: mạng chậm (di động) có thể không gửi nổi bài dài, khi đó điện thoại chuyển sang làm trên máy.
  - Sửa server trong lúc chạy: `GET /result` chép kết quả ra đĩa riêng của container trước khi phát (tệp trên Volume mở
    lâu chặn mọi commit / reload, kể cả `DELETE` job khác chạy song song: 500), `DELETE` bắt mọi lỗi commit để thử lại.
  - Server xử lý bài 84–115 phút mất 10–12 phút ở 4 nhân: tiêu chí "≤ 10 phút từ lúc bắt đầu tới lúc có đủ đoạn" cho
    bài 2 giờ sẽ cần thêm nhân (đo ở G3).
- 2026-11-22 G2 xong (B1, B2, B4 gửi lại trên Wi-Fi "Hiên coffee 2 5G", ~2,5–5 MB/s tải lên). Lỗi "EOF occurred in
  violation of protocol" lúc trước là của urllib khi gửi tệp lớn qua TLS: cùng tệp, curl gửi được (B4 120 MB trong 48 s),
  nên `g2_real.py` chuyển sang curl. Thêm `--job <mã>=<id>` để chờ job đã tạo. `g2_compare.py` dò độ trễ ±800 mẫu (1
  khối, như tiêu chí) bằng tương quan chéo FFT: B4 lệch 187 mẫu ở chỗ cắt lặng, dò ±64 thì SNR ra âm.

  | Bài | Đoạn | Mốc cắt lệch | SNR trung vị / p5 | Khối < 50 dB | D29 server (DER) | D29 điện thoại (Stage 5) | DER so với PC Stage 5* | Server (4 nhân) |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | B1 (114 phút) | 9 / 9 | 16 mẫu | 84,2 / 79,8 dB | 4/514 | **đạt** (2,5%) | đạt | 3,6% | 559 s |
  | B2 (112 phút) | 10 / 10 | 22 mẫu | 81,1 / 78,3 dB | 3/569 | **đạt** (0,7%) | đạt | 5,0% | 594 s |
  | B3 (115 phút) | 12 / 12 | 5 mẫu | 70,0 / 57,8 dB | 0/688 | trượt (23,6%) | trượt (16,8%) | 36% | 700 s |
  | B4 (79 phút) | 5 / 5 | 187 mẫu | 75,0 / 60,6 dB | 11/284 | trượt (29,5%) | trượt (44%) | 38% | 276 s |
  | B5 (84 phút) | 9 / 9 | 21 mẫu | 65,6 / 56,2 dB | 2/498 | **đạt** (5,5%) | trượt | 18% | 614 s |

  \* Bản PC của Stage 5 chạy trên `sach.flac` cũ của desktop (cắt lặng khác), nên cột này lẫn cả khác biệt do âm thanh
  sạch, không phải DER thuần giữa server và điện thoại. Kết luận: đoạn và mốc cắt đạt (SNR trung vị ≥ 65 dB, mốc ≤ 1
  khối); bài nào điện thoại đạt D29 (B1, B2) thì server cũng đạt, thêm B5. Tiêu chí "DER server ↔ điện thoại ≤ 5%" chỉ
  kiểm được gần đúng (B1, B2 ≤ 5%), xét lại ở H1. Chi phí ước tính (4 nhân + 4 GiB, $0,0000613 / s): bài 2 giờ ~600 s
  ≈ $0,04. Server xử lý bài 2 giờ 9–12 phút ở 4 nhân, sát tiêu chí ≤ 10 phút cả lượt → nâng hàm xử lý lên 8 nhân (đo ở
  G3; ~$0,08 / bài nếu nhanh gấp đôi).
- 2026-11-22 D150 (tải lên theo phần): `POST /jobs?request=…&parts=1` tạo job `uploading`; `PUT /jobs/{id}/parts/{k}`
  (app dùng 8 MB / phần) chỉ nhận phần kế tiếp hoặc gửi lại phần cũ, phần dở bị bỏ; `POST /jobs/{id}/start` thì gọi hàm
  xử lý, hàm đó ghép các phần ra đĩa container rồi xoá trên Volume. `JobStatus` thêm `uploading` và `received`. Client:
  mỗi phần thử lại 3 lần (chờ 2 / 5 / 10 s) khi mạng / 5xx lỗi, token sai thì không thử lại; nối lại job đang
  `uploading` thì gửi tiếp từ phần server đã có. Vẫn giữ cách cũ (cả tệp trong một yêu cầu) cho script trên PC. Lỗi tìm ra
  khi smoke: server gọi hàm xử lý trước khi commit `meta.json` có số phần, hàm khởi động nhanh đọc meta cũ, lỗi và job
  kẹt `queued` → commit trước rồi mới gọi; lỗi ngoài jar trong hàm xử lý giờ báo `failed` (app chuyển sang máy ngay). Test:
  `RemoteClientTest` 20 ca (thêm thử lại 5xx, hết lượt thử lại, token sai không thử lại, nối tiếp từ phần 2); smoke qua
  hai lần liền (3 và 37 phần, bỏ cách phần → 409, gửi lại phần 0).

## Phát sinh

- (Người dùng, 2026-11-21) **pyannote community-1 trên server** (GPU của Modal, cần token Hugging Face và đồng ý điều
  khoản model), như desktop: có thể cải thiện bài nhiều người (B4 trượt D29, D82) và bỏ được việc chia phần 20 phút
  (D81). Làm thành cách tách giọng thứ hai trên server, đo D29 trên B1–B5.
- Image Docker của cùng `.jar` để chạy trên PC ở nhà qua Tailscale, khi không muốn hoặc không dùng được Modal.
- Trong lúc chép lời tệp này, tải tệp kế tiếp lên server (D60 hiện chạy lần lượt).
- Extra intent (Stage 6) để ép một tệp chạy trên máy hoặc trên server.
- (D149) Model CAM++ zh_en advanced trên GitHub của sherpa-onnx đã bị thay ngày 2026-11-19: máy mới build APK (plugin
  guzz.deps) hay deploy server sẽ dừng ở bước kiểm SHA-256. Cần bản sao tệp `aa3c…` (Apache-2.0) ở chỗ mình giữ, vd đính
  kèm một Release của GuzzMobile, rồi trỏ `gradle/guzz-deps.txt` tới đó (hỏi trước khi đăng).
