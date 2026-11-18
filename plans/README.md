# Guzz Mobile — kế hoạch tổng

> Tệp này là kế hoạch tổng. Mỗi stage có một tệp riêng trong thư mục này (`stage-NN-*.md`) ghi mục tiêu, phạm vi,
> tiêu chí hoàn thành và các câu hỏi còn để ngỏ. **Kế hoạch chi tiết của từng stage được viết ở một session riêng**
> khi bắt đầu stage đó (cách làm: xem [CLAUDE.md](../CLAUDE.md)).

## 1. Mục tiêu

Port **Guzz** (app desktop Windows, Python + PySide6, nằm ở `D:\Guzz`) sang **Android**, giữ nguyên ý tưởng:
**chọn tệp âm thanh hoặc video → bấm Bắt đầu → ra bản chép lời**.

```
bai giang.m4a / bai giang.mp4  (chọn tệp, Chia sẻ tới Guzz, hoặc intent từ MacroDroid)
   │
   ├─ (0) là video: lấy luồng âm thanh (MediaExtractor)                 Stage 2
   ├─ (1) làm sạch: cắt khoảng lặng, chuẩn hoá âm lượng                 Stage 2
   │      (đủ bộ: bandpass, khử ồn, ghìm tiếng động đột ngột…)          Stage 7
   ├─ (2) cắt đoạn ~10 phút ở chỗ lặng nhất                              Stage 2
   ├─ (3) (nếu bật) nhận diện người nói trên máy bằng sherpa-onnx       Stage 5
   ├─ (4) gửi từng đoạn lên Google AI Studio, hết hạn mức thì đổi model  Stage 3
   └─ (5) ghép lại → bai giang.txt, báo sự kiện cho MacroDroid          Stage 4, 6
```

Thêm so với desktop: **intent** để các app tự động hoá (MacroDroid, Tasker, Automate) điều khiển Guzz và nhận kết quả.

**Không làm:**

- iOS.
- Nhận dạng giọng nói ngay trên máy (ASR offline): việc chép lời vẫn do Gemini làm.
- Dùng chung `su_dung.json` với Guzz desktop, GoogleAITranscribe và Header: điện thoại không đọc được tệp đó.
- Tự theo dõi thư mục hay chạy theo thời khoá biểu: việc này để MacroDroid làm (trigger "File thay đổi" → gửi intent).

## 2. Quyết định đã chốt

| # | Ngày | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- | --- |
| D1 | 2026-10-31 | Nền tảng | **Chỉ Android** | Intent và MacroDroid chỉ có trên Android; làm một nền tảng thì đi nhanh nhất. |
| D2 | 2026-10-31 | Ngôn ngữ / framework | **Kotlin + Jetpack Compose** | Native: intent, foreground service, MediaCodec dùng trực tiếp; sherpa-onnx và ONNX Runtime đều có API Kotlin. |
| D3 | 2026-10-31 | Model tách giọng | **sherpa-onnx**: pyannote segmentation-3.0 + embedding 3D-Speaker + phân cụm | Nhẹ (khoảng 6 MB + 7–40 MB), chạy CPU, mã nguồn mở (MIT / Apache-2.0), có sẵn API Android, kiến trúc gần pyannote 3.x của desktop. |
| D4 | 2026-10-31 | Phân phối | **APK tự cài** (GitHub Releases), có flavor `play` để sau này lên Play | Không vướng chính sách Play, nên được xin quyền "Truy cập mọi tệp". |
| D5 | 2026-10-31 | Cách để MacroDroid gọi Guzz | **Broadcast intent trước**, plugin Tasker/Locale là stage tuỳ chọn cuối | Đơn giản, app tự động hoá nào cũng gửi/nhận được. |
| D6 | 2026-10-31 | Quyền đọc tệp | **Cả hai**: có quyền "Truy cập mọi tệp" thì nhận đường dẫn tuyệt đối; không có thì nhận content:// URI hoặc tệp trong thư mục đã cấp qua SAF | MacroDroid truyền đường dẫn được, mà flavor `play` vẫn chạy được. |
| D7 | 2026-10-31 | Làm sạch âm thanh | **Làm theo bậc**: bản đầu chỉ cắt lặng và chuẩn hoá âm lượng; Stage 7 port đủ DSP, kiểm tra khớp bản Python | Có bản chạy được sớm, rồi mới đuổi theo chất lượng của desktop. |
| D8 | 2026-10-31 | Ưu tiên sau khi có bản chạy được đầu tiên | **Tách giọng trên máy** | Rủi ro kỹ thuật cao nhất, nên làm sớm. |
| D9 | 2026-10-31 | `applicationId` (Stage 0) | **`io.github.cozycode890.guzz`**, package Kotlin cùng gốc | Theo tài khoản GitHub CozyCode890, khớp GitHub Releases; namespace người dùng sở hữu thật. |
| D10 | 2026-10-31 | Hậu tố cho bản debug (Stage 0) | **`.debug`**, tên app "Guzz (debug)" | Debug và release cài song song. Macro thử trên bản debug phải trỏ package `.debug`. |
| D11 | 2026-10-31 | Tạo module (Stage 0) | **Tạo dần**: Stage 0 chỉ `:app`, `:core:common`, `:core:audio` | Danh sách module có thể đổi sau Stage 1; `:core:audio` chứa golden test mẫu. |
| D12 | 2026-10-31 | Cấu hình build chung (Stage 0) | **Convention plugin** trong `build-logic/` | Module mới chỉ vài dòng build, cấu hình không lệch nhau. |
| D13 | 2026-10-31 | Dependency injection (Stage 0) | **Tạo tay** (`AppContainer`) | Không thư viện, không sinh mã; app ít màn hình. |
| D14 | 2026-10-31 | Log (Stage 0) | **Tự viết**: interface ở `:core:common`, ghi Logcat + tệp xoay vòng như desktop | Module JVM thuần không dùng được Timber. |
| D15 | 2026-10-31 | Chỗ để tệp log (Stage 0) | **`Android/data/<package>/files/logs/`** | Không cần quyền; `adb pull` / USB lấy được, kể cả bản release. |
| D16 | 2026-10-31 | Lưu Settings (Stage 0) | **Preferences DataStore, giá trị có kiểu**, key `SECTION.key` như `config.default.txt` | Sai kiểu lộ lúc biên dịch; chạy được trên JVM nên test trên máy tính. |
| D17 | 2026-10-31 | Khung test (Stage 0) | **JUnit 5 + kotlin.test** ở module JVM, **JUnit 4** ở module Android | Golden test cần test tham số hoá; test thiết bị vẫn cần JUnit 4. |
| D18 | 2026-10-31 | Dữ liệu golden (Stage 0) | **Commit vào `testdata/golden/`** (WAV 16-bit nhỏ + JSON) | `gradlew test` chạy trên mọi bản clone, không cần Guzz desktop. |
| D19 | 2026-10-31 | JDK cho Gradle (Stage 0) | **Temurin 21 có sẵn**, `JAVA_HOME` của tài khoản trỏ về đó, ghim bằng `gradle-daemon-jvm.properties` | `JAVA_HOME` cũ trỏ tới JDK 25 đã bị gỡ; JDK 21 hợp Gradle/AGP. |
| D20 | 2026-10-31 | Cài Android SDK (Stage 0) | **Chỉ cmdline-tools**, `%LOCALAPPDATA%\Android\Sdk` | Cài bằng lệnh, ghi lại được, nhẹ. Android Studio để sau nếu cần. |
| D21 | 2026-10-31 | GitHub (Stage 0) | **Private `CozyCode890/GuzzMobile`**, có thể public ở Stage 9 (→ public, D123) | An toàn khi đang làm. |
| D22 | 2026-10-31 | Máy thật để thử (Stage 0) | **Xiaomi 15T**: MediaTek MT6899, 8 nhân, RAM 12 GB, Android 16 (API 36), HyperOS 3.0, chỉ `arm64-v8a` | Đặt mục tiêu hiệu năng ở Stage 1; Xiaomi giết app nền mạnh nên thử chạy nền có ý nghĩa. |
| D23 | 2026-10-31 | `SYSTEM.old_log_count = 0` (Stage 0) | **Đầy thì xoá trắng `guzz.log`**, không giữ tệp cũ | Python với `backupCount = 0` không bao giờ xoay; trên điện thoại dung lượng log phải bị chặn. |
| D24 | 2026-10-31 | Bộ phiên bản Gradle / AGP (Stage 0) | **AGP 9.3.3 + Gradle 9.7.1**, Kotlin 2.4.21 | Nằm trong bảng tương thích của Kotlin 2.4.21; AGP 9.5 để sau khi Kotlin 2.5 ra. |
| D25 | 2026-10-31 | Bản JUnit cho module JVM (Stage 0) | **JUnit 6.1.3**, vẫn dùng `kotlin-test-junit5` | Cùng API Jupiter với JUnit 5, là dòng đang phát triển. |
| D26 | 2026-10-31 | Ghim JDK 21 cho daemon (Stage 0) | **Viết tay** `gradle-daemon-jvm.properties` (`toolchainVersion=21`), không plugin foojay | `updateDaemonJvm` đòi kho tải JDK; JDK 21 cài tay như D19, không thêm phụ thuộc. |
| D27 | 2026-11-01 | Kiểm tệp cài đặt trên máy khi chưa ghi cài đặt nào (Stage 0) | **Lưu versionCode lúc mở app** vào `APP.last_version_code` (chỉ có trên mobile, không xuất / nhập) | DataStore chỉ tạo tệp ở lần ghi đầu; Stage 9 cần biết vừa cài đè bản mới để chuyển dữ liệu cũ. |
| D28 | 2026-11-01 | Bản ghi để đo (Stage 1) | **5 bài đã có kết quả pyannote của desktop** (2, 3, 5, 4 và 1 người nói), chép ra `D:\GuzzMobileData\stage-01` cùng tệp gốc; thêm bài 2 giờ để đo tốc độ | Có sẵn tham chiếu; chép ra thì desktop chạy lại cũng không ghi đè. |
| D29 | 2026-11-01 | Độ chính xác chấp nhận (Stage 1) | Mỗi bài, sau `process_turns`: **DER so với desktop ≤ 15%** (collar 0,5 s), người nói chính lệch ≤ 5 điểm %, số người lệch ≤ 1 | Đủ để ghi đúng giảng viên và người hỏi. |
| D30 | 2026-11-01 | Tốc độ / RAM trên máy (Stage 1) | **Bài 2 giờ ≤ 30 phút, PSS đỉnh ≤ 1 GB**, pin ≤ 45 °C, tốn ≤ 10% pin | Chạy nền được; HyperOS khó giết vì thiếu RAM. |
| D31 | 2026-11-01 | App đo trên điện thoại (Stage 1) | **App spike tự viết** (`...guzz.spike`), AAR sherpa-onnx 2.0.1 | Đổi được cấu hình, đo được RAM; dùng chung cho mọi phép đo. |
| D32 | 2026-11-01 | Chỗ để code spike (Stage 1) | **`spikes/` trên `main`, giữ lại**; `spikes/android` là build Gradle riêng | Đo lại được; Stage 5 dùng lại script so độ chính xác. |
| D33 | 2026-11-01 | Model embedding thử (Stage 1) | **3D-Speaker (Apache-2.0) + NeMo TitaNet small, WeSpeaker ResNet34-LM (CC-BY-4.0)** | WeSpeaker cùng họ với embedding của desktop; CC-BY chỉ cần ghi công. |
| D34 | 2026-11-01 | Bài dài quá heap Java (Stage 1) | **Chia phần ≤ 30–60 phút có chồng lấn, nối người nói**; Kotlin, không NDK | Kotlin API nhận cả bài trong một `FloatArray`, heap tối đa 512 MB, bản Android không có C API. |
| D35 | 2026-11-01 | Tiêu chí chạy nền (Stage 1) | **Sau khi cài một lần** (pin không hạn chế, Autostart): ≥ 1 cách lên FGS 10/10 lần khi màn hình tắt, Doze; chạy liền ≥ 60 phút | Cài một lần là chấp nhận được; thử thêm khi chưa cài gì. |
| D36 | 2026-11-01 | Định dạng đoạn đưa vào đo (Stage 1) | **FLAC, OGG-Opus, M4A-AAC, WAV** | M4A-AAC chạy được từ minSdk 26; Opus cần API 29. |
| D37 | 2026-11-01 | Gửi thử Gemini (Stage 1) | **Có, khoảng 5 yêu cầu** bằng `google_ai.py` của desktop; hỏi lại trước khi gửi | Biết định dạng nén có làm kém bản chép lời không. |
| D38 | 2026-11-01 | Resampler đưa vào đo (Stage 1) | **Windowed-sinc tự viết + `SonicAudioProcessor` của Media3** | Stage 2 chọn theo SNR và tốc độ. |
| D39 | 2026-11-10 | Rút gọn Stage 1: phần tách giọng còn lại | **Chuyển sang Stage 5** (ngưỡng phân cụm, int8, âm thanh gốc, chia phần trên PC, đo lại trên máy, Dừng giữa các phần). **Đi tiếp với sherpa-onnx**; Stage 5 chốt ngưỡng, không đạt D29 thì hỏi lại | Không đủ thời gian cho spike dài. Phần quyết định cả kế hoạch đã có số: bài 2 giờ chia phần 20 phút chạy 23 phút, 556 MB, đạt D30. Stage 2–4 không phụ thuộc tách giọng. |
| D40 | 2026-11-10 | Resampler; giải mã chưa đo xong (Stage 1) | **`SincResampler` tự viết**, loại Sonic. Giải mã MP4, đối chiếu thời lượng và SNR tệp thật với ffmpeg → bước kiểm của Stage 2 | Sonic không lọc chống alias (10 / 15 kHz giữ nguyên 0 dB); sinc suy giảm −96 dB. |
| D41 | 2026-11-10 | Định dạng đoạn gửi Gemini (Stage 1) | **FLAC** (tự ghi tệp `.flac`), bỏ phép thử gửi Gemini (F3) | Không mất dữ liệu, giống desktop, nên không cần so chất lượng; 9,6 MB / 10 phút, mã hoá 4–6 s. Giảm dung lượng bằng Opus để Stage 8 xét. |
| D42 | 2026-11-10 | Rút gọn Stage 1: chạy nền và MacroDroid | **Chạy dài lúc màn hình tắt và `onTimeout` → Stage 4; macro, Doze và sự kiện DONE → Stage 6**, chỉ thử một đêm với máy đã cài đặt pin, bỏ đêm "chưa cài gì" | Thử trên code thật thay vì app spike; hướng dẫn cài đặt ghi thẳng các bước bắt buộc. Tiêu chí D35 giữ nguyên, áp ở Stage 6. |
| D43 | 2026-11-10 | PCM của cả bài (Stage 2) | **Tệp tạm PCM 16-bit 16 kHz mono, đọc / ghi theo khối** | RAM không tăng theo độ dài bài; giống desktop đọc s16 từ ffmpeg. |
| D44 | 2026-11-10 | Tệp dài hơn 3 giờ (Stage 2) | **Không giới hạn**; ước lượng dung lượng tạm trước khi giải mã, thiếu thì báo lỗi rõ | Chỉ dung lượng trống mới giới hạn độ dài. |
| D45 | 2026-11-10 | Ngưỡng tốc độ (Stage 2) | Bài 2 giờ → các đoạn FLAC, Xiaomi 15T, màn hình tắt: **≤ 10 phút, PSS ≤ 300 MB** | Phải tối ưu resampler, không cần vắt kiệt. |
| D46 | 2026-11-10 | Dữ liệu golden (Stage 2) | **Tổng hợp trong repo + bản ghi thật chạy tại máy** (tự bỏ qua khi thiếu dữ liệu) | Repo không chứa bản ghi thật mà vẫn kiểm trên bài 1–2 giờ. |
| D47 | 2026-11-10 | `[VIDEO] extract_first`, `extract_format`, `save_extracted` (Stage 2) | **Bỏ hai mục đầu; `save_extracted` → Stage 4** | Mobile luôn giải mã một lần ra PCM tạm. |
| D48 | 2026-11-10 | `[CHUNKING] format` (Stage 2) | **Chỉ `flac`** | Đúng D41; Android không có bộ mã hoá MP3. |
| D49 | 2026-11-10 | Màn hình thử của Stage 2 | **Tối giản, chỉ bản debug**, nhận thêm lệnh adb | Thử được trình chọn tệp và content:// URI. |
| D50 | 2026-11-11 | Bài 2 giờ lúc tắt màn hình mất 16,6 phút, quá D45 (Stage 2) | **Tối ưu ngay rồi đo lại** (đọc mẫu nén và xử lý PCM chạy song song với bộ giải mã; đo bản không debuggable); vẫn quá thì hỏi lại | Nút thắt là đọc / nạp mẫu nén nối tiếp với xử lý PCM trên một luồng. |
| D51 | 2026-11-11 | Thư viện HTTP (Stage 3) | **OkHttp**, test bằng MockWebServer | Đúng mặc định ở mục dưới; port được test HTTP giả của desktop. |
| D52 | 2026-11-11 | Lưu dữ liệu hạn mức (Stage 3) | **Tệp JSON cùng cấu trúc `su_dung.json`** | Port và golden dễ; không dùng chung với app khác nên bỏ khoá tệp. |
| D53 | 2026-11-11 | Log yêu cầu ở mức DEBUG (Stage 3) | **Có, như desktop** | Dò được lỗi 400; key ở header, có test kiểm không lọt. |
| D54 | 2026-11-11 | Nhập key / cài đặt từ desktop (Stage 3) | **Không, nhập key tay** | Nhập / xuất cài đặt để Stage 8. |
| D55 | 2026-11-11 | Sao lưu dữ liệu app (Stage 3) | **`allowBackup="false"` toàn app** | Key không lên Drive; sao lưu đầy đủ từng giết FGS (Stage 1). |
| D56 | 2026-11-11 | Màn hình của Stage 3 | **Trang Google AI thật** (key, Kiểm tra kết nối, chọn model) **+ gửi thử ở màn hình thử bản debug** | Stage 4 cần trang nhập key thật. |
| D57 | 2026-11-11 | Gửi thật lên Gemini (Stage 3) | **Khoảng 4 yêu cầu, hỏi trước khi gửi** | Tài liệu hôm nay ghi định dạng `*-transcribe` khác desktop. |
| D58 | 2026-11-11 | Model mặc định (Stage 3) | **Giữ như desktop**, kiểm bằng `list_models`, model mất ghi vào Phát sinh Stage 8 | Model 404 tự bị khoá rồi đổi model như desktop. |
| D59 | 2026-11-11 | Nơi lưu bản chép lời (Stage 4) | **Thư mục chọn một lần qua SAF** (gợi ý `Documents/Guzz`); cạnh tệp nguồn → Stage 6 | Một cách ghi cho cả hai flavor, không cần quyền đặc biệt; trùng tên xử lý đúng như desktop. |
| D60 | 2026-11-11 | Chạy lần lượt hay song song (Stage 4) | **Lần lượt từng tệp, như desktop** | Hạn mức theo phút dùng chung; RAM, pin, chỗ trống tạm thấp. |
| D61 | 2026-11-11 | Xem bản chép lời (Stage 4) | **Xem trong app + nút Mở bằng app khác** | Xem nhanh không cần app khác; sửa bằng trình soạn thảo trên máy. |
| D62 | 2026-11-11 | Nút chia sẻ (Stage 4) | **Có, chia sẻ tệp .txt** | Gửi như tệp đính kèm; dán chữ thì chép ở màn hình xem. |
| D63 | 2026-11-11 | Lưu hàng đợi (Stage 4) | **Tệp JSON**, thay cho Room | Danh sách nhỏ; logic ở module JVM test trên PC; không KSP / sinh mã. |
| D64 | 2026-11-11 | Bị hệ thống giết giữa chừng (Stage 4) | **Tự làm tiếp** khi mở app và khi service được khởi động lại; đã bấm Dừng thì không | Bài chạy đêm vẫn xong; cache từng đoạn nên không gửi lại. |
| D65 | 2026-11-11 | Gửi thật lên Gemini (Stage 4) | **B5 + B4, tổng ≤ 15 yêu cầu** (2026-11-14 nới lên ≤ 20: B5 ra 9 đoạn, B4 khoảng 8), hỏi trước mỗi lần gửi | B5 cho chạy liền lúc tắt màn hình, B4 cho Dừng / giết app. |
| D66 | 2026-11-11 | Dọn thư mục tạm (Stage 4) | **Cắt đoạn xong xoá PCM; tệp xong xoá thư mục tạm; tệp lỗi / dừng giữ tới khi bỏ khỏi danh sách** | Bài 2 giờ còn khoảng 200 MB tạm thay vì 660 MB; khác `config.default.txt` desktop (giữ tạm). |
| D67 | 2026-11-11 | Tiêu chí service sống ≥ 60 phút khi B5 chỉ chạy 10–15 phút (Stage 4) | **Bản debug: lệnh adb có tham số chờ giữa các đoạn** | Vẫn 6 yêu cầu; mỗi lần gửi sau quãng chờ thử luôn mạng trong Doze. |
| D68 | 2026-11-11 | `[VIDEO] save_extracted` (Stage 4) | **Để Stage 8** | Cần công tắc trên giao diện và một bài video gửi thật để thử. |
| D69 | 2026-11-12 | Receiver ở nền không được khởi động FGS (Stage 4) | **Người dùng tự miễn tối ưu pin cho bản debug** (`deviceidle whitelist`) | "No restrictions" của HyperOS không đủ; MacroDroid (Stage 6) cũng cần. |
| D70 | 2026-11-13 | Thử D–E của Stage 4 ở đâu | **Máy ảo `guzz_15t`** (API 36, 1280×2772, 520 dpi, RAM 4 GB); điện thoại thật chỉ ở đợt thử cuối | Đỡ phải nối adb với điện thoại; cùng bản Android. HyperOS chỉ thử được trên máy thật. |
| D71 | 2026-11-14 | Tên các cách nhận diện (Stage 5) | **"Tách giọng trên máy + Prompt", "Gemini tự nhận diện", "Kết hợp (trên máy + Gemini)"**; giá trị cài đặt giữ `pyannote` / `gemini` / `hybrid` | Nói đúng việc chạy ở đâu; giữ giá trị để dùng lại ràng buộc và test của desktop. |
| D72 | 2026-11-14 | Không biết số người (Stage 5) | **Ngưỡng phân cụm; ra ngoài [min, max] thì phân cụm lại với số cụm = min / max** | Giữ cài đặt min–max của desktop, tốn thêm tối đa một lần phân cụm. |
| D73 | 2026-11-14 | Cách Kết hợp: song song hay tuần tự (Stage 5) | **Tuần tự như desktop** | Một đường chạy, Dừng / làm tiếp đơn giản. |
| D74 | 2026-11-14 | Chọn model embedding (Stage 5) | **Không: chỉ CAM++ zh_en advanced** trong APK | APK nhỏ, ít thứ phải thử. |
| D75 | 2026-11-14 | PCM tạm khi tách giọng (Stage 5, đổi D66) | **Giữ PCM cần cho tách giọng tới lúc có kết quả rồi xoá**; đoạn từ cache mà thiếu PCM thì giải mã lại | Không giải mã hai lần; chỗ trống chỉ tăng lúc tách giọng. |
| D76 | 2026-11-14 | Giao diện người nói (Stage 5) | **Trang Người nói rút gọn**; ngưỡng, mẫu đoạn văn, prompt → Stage 8 | Đủ dùng hằng ngày. |
| D77 | 2026-11-14 | Gửi thật lên Gemini (Stage 5) | **B4 × 3 cách, ≤ 15 yêu cầu**, hỏi trước mỗi lần gửi | 4 đoạn, 4 người, có kết quả desktop để so. |
| D78 | 2026-11-14 | Âm thanh gốc / sạch, int8 (Stage 5) | **Theo số đo trên PC**: giữ mặc định desktop (sạch, fp32) trừ khi cách kia DER TB thấp hơn ≥ 1 điểm và không bài nào trượt thêm D29 | Không đổi mặc định vì chênh lệch nhỏ. |
| D79 | 2026-11-14 | Lấy AAR sherpa-onnx và model khi build (Stage 5) | **Task Gradle tự tải, kiểm SHA-256**, cache ngoài repo | Repo nhẹ; tệp tải về không đổi lén được. |
| D80 | 2026-11-15 | Ngưỡng phân cụm khi không biết số người không đạt D29 (Stage 5 B1, D39) | **Ngưỡng 1,1 + ưu tiên nhập số người**: biết số người thì luật min = max = N | 1,1 giữ được người hỏi (1,2 trở lên gần như luôn ra 1 người); biết số người đạt 3/5 bài như Stage 1. |
| D81 | 2026-11-15 | Chia phần tăng DER TB 2,6 điểm so với nguyên bài (Stage 5 B4) | **Giữ P = 20 phút** | Đạt D29 nhiều bài hơn; nguyên bài 2 giờ hết RAM trên điện thoại. |
| D82 | 2026-11-15 | Tách giọng trượt D29 ở B4 với cả 3 cách (Stage 5 G2) | **Đóng Stage 5, ghi trượt B4**; cải thiện bài nhiều người để sau. Stage kế: 7 | B4 trượt ở mọi cấu hình; bài ít người đạt; 3 cách chạy đúng trên máy thật. |
| D83 | 2026-11-15 | FFT cho khử ồn (Stage 7) | **Tự viết FFT Kotlin** radix-2 | JVM thuần, golden chạy trên PC, không thêm thư viện / JNI. |
| D84 | 2026-11-15 | Hệ số Butterworth (Stage 7) | **Port `scipy.signal.butter`** sang Kotlin | Tần số nào cũng được; golden so với scipy. |
| D85 | 2026-11-15 | Sai số so với Python (Stage 7) | **SNR ≥ 60 dB**, mốc cắt lệch ≤ 1 khối | Không nghe ra khác biệt. |
| D86 | 2026-11-15 | Nhiều luồng khi khử ồn (Stage 7) | **Một luồng**, đo rồi mới tính | Mát máy, ít pin. |
| D87 | 2026-11-15 | Preset âm thanh mặc định (Stage 7) | **Như desktop: Giảng viên đi lại** | Trùng `config.default.txt`. |
| D88 | 2026-11-15 | Ngưỡng tốc độ làm sạch đủ (Stage 7) | Bài 1 giờ, Xiaomi 15T, màn hình tắt: **thêm ≤ 10 phút, PSS ≤ 400 MB** | Vẫn ngắn hơn thời gian chép lời. |
| D89 | 2026-11-15 | Giao diện âm thanh (Stage 7) | **Trang Âm thanh rút gọn** (preset, bật / tắt bước, lưu âm thanh sạch); ô số → Stage 8 | Đủ dùng hằng ngày. |
| D90 | 2026-11-15 | Nghe thử (Stage 7) | **Cặp tệp A/B**, người dùng nghe | Tiêu chí nghe cần tai người. |
| D91 | 2026-11-17 | Tên action / sự kiện (Stage 6) | **Gốc cố định `io.github.cozycode890.guzz.action.*` / `.event.*` cho mọi bản**; macro chọn bản bằng ô Package | Macro thử trên debug dùng lại cho bản thật; receiver manifest đằng nào cũng cần Package. |
| D92 | 2026-11-17 | Sự kiện tiến độ (Stage 6) | **Không có `FILE_PROGRESS`**; lệnh `STATUS` trả %, đoạn i/n | Ít broadcast, ít việc. |
| D93 | 2026-11-17 | Mã bí mật (Stage 6) | **Tuỳ chọn**: ô trống thì không kiểm | Cài macro nhanh; công tắc cho phép mặc định tắt. |
| D94 | 2026-11-17 | Cách nhận lệnh (Stage 6) | **Cả hai**: broadcast chính, activity trong suốt dự phòng | Broadcast cần miễn tối ưu pin; activity khởi động service được. |
| D95 | 2026-11-17 | Extra ghi đè cài đặt (Stage 6) | **Người nói, preset âm thanh, thư mục lưu**; không ghi đè model / chế độ | Đủ cho macro theo lớp / phòng; chỉ áp cho tệp đó. |
| D96 | 2026-11-17 | Nơi lưu khi không có `output_dir` (Stage 6) | **Như cài đặt app (SAF)** + lựa chọn "Lưu cạnh tệp nguồn" (cần "Truy cập mọi tệp"), mặc định tắt | Một chỗ lưu cho mọi cách thêm tệp. |
| D97 | 2026-11-17 | Đường dẫn trong sự kiện (Stage 6) | **Có, tắt được** | Macro mở tệp chạy ngay; app nào cũng nghe được sự kiện. |
| D98 | 2026-11-17 | Chia sẻ tới Guzz (Stage 6) | **Thêm và bắt đầu ngay**, không cần công tắc cho phép | Người dùng tự bấm Chia sẻ. |
| D99 | 2026-11-17 | Chia việc trang Tự động hoá với Stage 8 | **Stage 6 làm trang gọn** (Composable ở `:feature:automation`); Stage 8 gắn vào điều hướng | Thử được bằng tay; ít đụng tệp chung. |
| D100 | 2026-11-17 | Gemini thật trong đêm thử MacroDroid (Stage 6) | **Một bài 1–3 phút, ≤ 3 yêu cầu**, lúc Doze; hỏi trước khi gửi | Biết HyperOS có chặn mạng của FGS trong Doze không. |
| D101 | 2026-11-17 | Macro mẫu (Stage 6) | **Bảng hướng dẫn + tệp `.macro` xuất từ máy thật** | Nhập được ngay; bảng để đọc và sửa. |
| D102 | 2026-11-17 | Broadcast bị chặn khởi động service (Stage 6) | **Giữ tệp**, phát `ERROR` `background_blocked`, thông báo "Bấm để bắt đầu" | Không mất lệnh; macro gửi lại bằng activity. |
| D103 | 2026-11-17 | Tệp chia sẻ không giữ được quyền đọc bền (Stage 6) | **Chép vào thư mục riêng của Guzz**, xoá khi xong / bỏ | Tự làm tiếp sau khi bị giết (D64) vẫn đọc được. |
| D104 | 2026-11-17 | Bản play nhận đường dẫn (Stage 6) | **"Thư mục nguồn" cấp qua SAF**, đường dẫn trong đó đổi sang URI tài liệu | Trigger "Tệp thay đổi" của MacroDroid chỉ cho đường dẫn. |
| D105 | 2026-11-18 | Gộp Stage 6 vào `main` khi nào (Stage 8 đang chờ) | **Gộp ngay sau A–E** (thử trên máy ảo), nhóm F (MacroDroid trên máy thật, đêm thử) làm sau trên `main` | Stage 8 làm tiếp được nhóm G, H; F chủ yếu là thử. |
| D106 | 2026-11-18 | Zalo chia sẻ tệp với kiểu `application/octet-stream` (Stage 6) | **Nhận thêm mọi kiểu `application/…`, lọc theo đuôi tệp** âm thanh / video (Zalo gửi vd `application/m4a`) | `*/*` làm Guzz hiện ở mọi lần chia sẻ |
| D107 | 2026-11-18 | Tiêu chí đêm thử D35 (Stage 6) | **Chấp nhận buổi chiều 2026-11-18**: 28/28 DONE, 11 lần màn hình tắt, không hỏng | Chưa đủ 10/10 lúc màn hình tắt, không thử thêm đêm |
| D108 | 2026-11-18 | Tệp `.macro` mẫu (Stage 6 F5) | **Bỏ, chỉ giữ bảng** trong `docs/intents.md` | 3 macro mẫu chưa có trên máy; tạo qua adb tốn nhiều lượt |
| D110 | 2026-11-17 | Điều hướng (Stage 8) | **Thanh dưới 3 tab**: Chuyển đổi · Hạn mức · Cài đặt (danh sách trang con) | Một tay bấm được, vừa 360 dp. |
| D111 | 2026-11-17 | Màu (Stage 8) | **Material You + bộ màu Guzz dự phòng** cho máy dưới Android 12, không công tắc | Ít việc nhất. |
| D112 | 2026-11-17 | Xuất / nhập cài đặt (Stage 8) | **INI như `config.txt` desktop**, nhập được `config.txt` desktop, không xuất API key, golden với `configparser` | Một định dạng cho cả hai app; key đã theo desktop (D16). |
| D113 | 2026-11-17 | Máy tính bảng (Stage 8) | **Không**, chỉ giữ không vỡ (giới hạn bề ngang nội dung) | Không có máy để thử. |
| D114 | 2026-11-17 | Tổ chức màn hình (Stage 8) | **Một Activity + Navigation Compose**; Activity cũ thành route, màn hình thử bản debug giữ Activity riêng | Back, giữ trạng thái tab, đổi ngôn ngữ / chủ đề gọn hơn. |
| D115 | 2026-11-17 | Đổi ngôn ngữ (Stage 8) | **Chọn trong app** bằng per-app locale của AppCompat | Chạy từ Android 8, không khởi động lại. |
| D116 | 2026-11-17 | Hướng dẫn lần đầu (Stage 8) | **Thẻ "Thiết lập ban đầu" trên màn hình chính**, tự ẩn khi đủ; cùng danh sách ở Cài đặt → Hệ thống | Không chặn dùng app. |
| D117 | 2026-11-17 | Mục ít dùng (Stage 8) | **Phần "Nâng cao" gập cuối từng trang** | Trang ngắn, mục vẫn cạnh mục liên quan. |
| D118 | 2026-11-17 | Opus tiết kiệm dữ liệu, chỉ gửi khi có Wi-Fi (Stage 8) | **Không làm** | Tính năng mới, ngoài phạm vi. |
| D119 | 2026-11-17 | `languages` cho `*-transcribe` (Stage 8) | **Gợi ý bấm để điền `vi-VN, en-US`**, mặc định giữ trống như desktop, không gửi thử | Không đổi mặc định, không gửi gì lên Google. |
| D120 | 2026-11-17 | `[VIDEO] save_extracted` (Stage 8, D68) | **Làm, thử bằng Gemini giả** | Lưu FLAC không phụ thuộc Gemini. |
| D121 | 2026-11-17 | Kiểm giao diện (Stage 8) | **Máy ảo + `ui_check.ps1` (`android layout`, ảnh) + vài Compose UI test**, không thư viện screenshot | Đủ kiểm tiêu chí, không thêm phụ thuộc. |
| D122 | 2026-11-18 | Build release ở đâu (Stage 9) | **Build tay trên PC** bằng `tools/release.ps1` (test, lint, build, ký, `apksigner verify`, SHA-256), không CI | Keystore không rời máy; ít việc thiết lập nhất. |
| D123 | 2026-11-18 | Repo khi phát hành (Stage 9, đổi D21) | **Chuyển `CozyCode890/GuzzMobile` sang public**, hỏi ngay trước khi chuyển | Ai cũng tải được APK ở Releases. |
| D124 | 2026-11-18 | Lịch sử git khi public (Stage 9) | **Giữ lịch sử**; gitleaks sạch thì chỉ đưa IP Tailscale / đường dẫn cá nhân ở tệp hiện tại sang `CLAUDE.local.md`; có bí mật thật thì dừng, hỏi | Email tác giả đã công khai qua repo desktop; không đổi mã commit. |
| D125 | 2026-11-18 | Giấy phép mã nguồn (Stage 9) | **MIT** | Ngắn, hợp với mọi phụ thuộc. |
| D126 | 2026-11-18 | Tự kiểm tra bản mới (Stage 9) | **Không**; trang Giới thiệu có link tới Releases | Không thêm kết nối mạng ngầm. |
| D127 | 2026-11-18 | Tên và icon (Stage 9) | **"Guzz" + icon desktop** vẽ lại thành adaptive icon vector, có lớp monochrome | Hai app trông giống nhau. |
| D128 | 2026-11-18 | Dung lượng APK (Stage 9) | **≤ 50 MB, nén `.so`**, bỏ `.so` C API thừa | Tải qua GitHub nhẹ hơn (~45 MB); tốn thêm ~27 MB bộ nhớ máy khi cài. |
| D129 | 2026-11-18 | Keystore (Stage 9) | **Ngoài repo + bản sao trong trình quản lý mật khẩu**; mật khẩu qua biến môi trường; người dùng tự chạy `keytool` | Mất keystore là không cài đè được nữa. |
| D130 | 2026-11-18 | Số phiên bản (Stage 9) | **1.0.0, tag `v1.0.0`**; `versionCode = MAJOR×10000 + MINOR×100 + PATCH`, khai một chỗ | versionCode luôn tăng, không sửa tay. |
| D131 | 2026-11-18 | Thử máy (Stage 9) | **Xiaomi 15T + máy ảo API 26 và API 30**; đo bài 2 giờ trên bản release lúc thử | Chỉ có một máy thật; phủ minSdk 26. |
| D132 | 2026-11-18 | Rà lỗ hổng (Stage 9) | **Checklist Android + đọc kỹ mặt tấn công, gitleaks, osv-scanner**; không review toàn bộ bằng agent | Phủ phần app khác gọi vào và thứ sắp công khai, ít token. |
| D133 | 2026-11-18 | Tinh gọn (Stage 9) | **Code chết, tài nguyên, phụ thuộc thừa, hết cảnh báo lint, code debug không lọt release**; không dọn `spikes/`, không `/simplify` | Theo công cụ, ít rủi ro sửa nhầm. |
| D134 | 2026-11-18 | Nâng AGP / Gradle / thư viện (Stage 9) | **Bộ mới nhất Kotlin đã phủ**, commit riêng đầu stage | Theo Phát sinh Stage 0. |
| D135 | 2026-11-18 | Pin và nhiệt (Stage 9) | **Không thêm tính năng, chỉ đo** bài 2 giờ trên bản release | Đã có ô số luồng; người dùng ít thời gian. |

**Mặc định chưa hỏi** (stage liên quan sẽ hỏi lại nếu cần):

- minSdk 26 (Android 8.0). targetSdk lấy bản mới nhất lúc làm. Chỉ đóng gói ABI `arm64-v8a`; bản debug thêm `x86_64` để chạy trên emulator.
- Giải mã âm thanh/video bằng MediaExtractor + MediaCodec của Android, **không** đóng gói FFmpeg: ffmpeg-kit đã ngừng
  phát triển, và FFmpeg làm APK nặng thêm nhiều.
- Thư viện: OkHttp + kotlinx.serialization để gọi API, coroutines để chạy bất đồng bộ, foreground service để chạy nền.
  Hàng đợi bền lưu bằng tệp JSON, không dùng Room (D63).

## 3. Kiến trúc dự kiến

```
:app                  Compose UI, điều hướng, thông báo, ghép các module
:core:common          Settings, TimeMap, kiểu dữ liệu chung, log                  JVM thuần
:core:audio           chunker, cắt lặng, chuẩn hoá, resampler, đọc / ghi PCM 16-bit,
                      ChunkPreparer; (Stage 7) AudioCleaner + gói dsp/ (FFT,
                      Butterworth, sosfilt, STFT, noisereduce)                     JVM thuần
:core:media           giải mã, ghi đoạn FLAC, liệt kê / chọn luồng, kiểm chỗ trống Android (MediaCodec)
:core:gemini          Files API, Interactions API, phân loại lỗi, RateLimiter,
                      QuotaTracker, đổi model dự phòng                             JVM thuần
:core:speaker         port speaker.py: làm mượt, gán người nói, đặt tên, định dạng JVM thuần
:core:diarization     bọc sherpa-onnx, nạp model từ assets                         Android (JNI)
:core:pipeline        xử lý một tệp, cache để làm tiếp, ghép bản chép lời;
                      hàng đợi (lưu JSON, D63) và cách chạy lần lượt              JVM thuần (nhận interface)
:feature:queue        foreground service, thông báo, SAF, QueueController          Android
:feature:automation   intent nhận lệnh / phát sự kiện; (Stage 10) plugin Tasker    Android
```

Nguyên tắc: **thuật toán port từ Python nằm ở module JVM thuần**, nên chạy test ngay trên máy tính được và so với kết quả
của chính bản Python trên cùng đầu vào (golden test). Chỉ phần nào bắt buộc đụng tới Android (MediaCodec, JNI của
sherpa-onnx, service, intent) mới nằm ở module Android. Stage 0 có thể chỉnh lại danh sách module này.

## 4. Bản đồ port (desktop → mobile)

| Desktop (`D:\Guzz`) | Mobile | Stage |
| --- | --- | --- |
| `audio_io.py`: đọc, transcode, đo thời lượng bằng ffmpeg | `:core:media`: MediaExtractor + MediaCodec + resampler | 2 |
| `video_io.py`: liệt kê, chọn, trích luồng âm thanh | `:core:media` | 2 |
| `chunker.py` | `:core:audio`, port nguyên thuật toán | 2 |
| `audio_processing.py`: `find_speech_segments`, `trim_silence`, `boost_and_normalize`, `measure_dbfs`, TimeMap | `:core:audio` | 2 |
| `audio_processing.py`: `bandpass_filter`, `reduce_noise` (noisereduce), `suppress_transients`, `level_loudness` | `:core:audio` (`AudioCleaner`, `dsp/`) | 7 |
| `gui/presets.py` | `:core:common` (`AudioPresets`) | 7 |
| `google_ai.py` | `:core:gemini` | 3 |
| `quota.py` (chỉ còn dùng trong app, không chung tệp với app khác) | `:core:gemini` | 3 |
| `transcribe.py`: `ChunkDispatcher` (đổi model dự phòng) | `:core:gemini` | 3 |
| `transcribe.py`: `transcribe_single_file`, cache, `assemble_transcript`, `format_header` | `:core:pipeline` | 4 |
| `gui/threads.py`: `TranscribeThread` (hàng đợi lần lượt, dừng hàng đợi theo loại lỗi) | `:core:pipeline` + `:feature:queue` (service) | 4 |
| `config.py`, `config.default.txt` | `:core:common` Settings + DataStore | khung ở 0, mỗi stage thêm mục của mình |
| `speaker.py` | `:core:speaker` | 5 |
| `diarization.py`, `diarization_worker.py` (subprocess pyannote, token Hugging Face) | `:core:diarization`: sherpa-onnx chạy ngay trong app, không cần token | 5 |
| `gui/pages/*` | `:app` Compose | màn hình tối thiểu theo từng stage, đủ bộ ở 8 |
| `gui/i18n.py` | `strings.xml` vi / en | 8 |
| `tests/*` | JUnit + golden test | mọi stage |

## 5. Các stage

| Stage | Tên | Xong stage thì có gì | Cần xong trước |
| --- | --- | --- | --- |
| 0 | [Khởi tạo dự án](stage-00-setup.md) | Repo git, build ra APK rỗng, khung module, công cụ golden test | — |
| 1 | [Spike kỹ thuật](stage-01-spike.md) | Số đo thật: sherpa-onnx đúng/nhanh tới đâu, giải mã nhanh tới đâu, intent có khởi động được việc nền khi màn hình tắt không; quyết định đi tiếp hay đổi hướng. Đã rút gọn (D39–D42): phần chưa đo chuyển sang Stage 2, 4, 5, 6 | 0 |
| 2 | [Lõi âm thanh tối giản](stage-02-audio-core.md) | Tệp âm thanh / video → các đoạn + TimeMap, khớp chunker của Python | 1 |
| 3 | [Client Gemini, hạn mức, đổi model](stage-03-gemini-client.md) | Gửi một đoạn lên Gemini; xử lý đủ các loại lỗi như desktop | 0 |
| 4 | [Pipeline + hàng đợi nền — **MVP**](stage-04-pipeline-mvp.md) | Chọn tệp → .txt, chạy được khi tắt màn hình, dừng rồi làm tiếp không gửi lại đoạn đã xong | 2, 3 |
| 5 | [Tách giọng trên máy](stage-05-diarization.md) | 3 cách nhận diện người nói như desktop | 4 |
| 6 | [Intent cho MacroDroid](stage-06-intents.md) | Lệnh + sự kiện, Chia sẻ tới Guzz, hai chế độ quyền đọc tệp, tài liệu + macro mẫu | 4 |
| 7 | [Làm sạch âm thanh đầy đủ](stage-07-audio-cleaning.md) | DSP + preset như desktop, khớp bản Python | 4 |
| 8 | [Hoàn thiện giao diện](stage-08-ui.md) | Đủ các trang cài đặt, hạn mức, nhật ký; vi / en; sáng / tối | 5, 6, 7 |
| 9 | [Đóng gói & phát hành](stage-09-release.md) | APK release đã ký trên GitHub Releases (repo public), đã rà lỗ hổng, tinh gọn, đo dung lượng và pin | 8 |
| 10 | [(Tuỳ chọn) Plugin Tasker/Locale](stage-10-tasker-plugin.md) | Guzz hiện trong danh sách plugin của MacroDroid / Tasker | 6 |

Stage 3 không cần Stage 1 và 2, nên làm song song được với hai stage đó.

## 6. Rủi ro lớn

1. **sherpa-onnx tách giọng kém chính xác hơn pyannote community-1.** Desktop phân cụm bằng VBx/PLDA, còn sherpa-onnx
   phân cụm nhanh theo ngưỡng. → Stage 1 đo trên bản ghi lớp học thật, so với kết quả pyannote của desktop; bước làm mượt
   của `speaker.py` bù được một phần. Stage 1: biết số người thì CAM++ zh_en đạt 3/5 bài, DER trung bình 8,2%; chưa tìm
   được ngưỡng khi không biết số người → Stage 5 (D39).
2. **Thời gian và RAM trên CPU điện thoại với bài 1–2 giờ.** Riêng mảng PCM float của bài 2 giờ ở 16 kHz đã khoảng 460 MB.
   → Stage 1 đo; Stage 2 không giữ cả bài trong RAM (đọc theo khối từ tệp PCM tạm). Kotlin API của sherpa-onnx nhận cả
   bài trong một `FloatArray`, trong khi heap Java của máy thử tối đa 512 MB → Stage 1 thử chia phần rồi nối người nói
   (D34). Stage 1: chạy nguyên bài 2 giờ bị giết vì hết RAM; chia phần 20 phút thì 23 phút, 556 MB, đạt D30.
3. **Android hạn chế việc chạy nền.** Từ Android 12 app ở nền không tự khởi động foreground service được (trừ vài
   ngoại lệ); Android 15 giới hạn 6 giờ / 24 giờ cho FGS loại `dataSync` / `mediaProcessing`. Intent từ MacroDroid lúc
   màn hình tắt có thể bị chặn. → Thử các cách: broadcast khi Guzz đã được miễn tối ưu pin, activity trong suốt,
   WorkManager. Stage 1 mới thử bằng adb lúc màn hình bật (cả ba đều lên FGS); thử lúc màn hình tắt chuyển sang Stage 4
   và 6 (D42).
4. **Hãng điện thoại (Xiaomi, Samsung, Oppo…) tự giết app chạy nền.** → Hướng dẫn tắt tối ưu pin; cache từng đoạn để
   làm tiếp (desktop đã có).
5. **Không đếm chung hạn mức với desktop**, trong khi Google đếm theo tài khoản. → App tự học hạn mức từ lỗi 429 (desktop
   đã có logic này) và nói rõ điều này trong giao diện.
6. **Giấy phép model.** → Trước khi đóng gói, kiểm lại giấy phép từng tệp model (segmentation-3.0: MIT, 3D-Speaker:
   Apache-2.0). Tránh model chỉ cho dùng phi thương mại (vd reverb-diarization).
7. **APK nặng** vì ONNX Runtime + model + Compose. → Chỉ đóng gói `arm64-v8a`, bật R8, đo ở Stage 9.
