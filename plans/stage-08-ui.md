# Stage 8 — Hoàn thiện giao diện

**Trạng thái:** 🔨 đang làm (plan 2026-11-17) · **Cần xong trước:** 5, 6, 7 (làm song song với Stage 6, xem "Làm song
song với Stage 6")

## Mục tiêu

Mỗi stage trước chỉ làm màn hình tối thiểu. Stage này làm đủ các trang như desktop, rút gọn cho vừa màn hình điện thoại,
có tiếng Việt / tiếng Anh, giao diện sáng / tối.

## Phạm vi

### Làm

- Các trang:
  - **Chuyển đổi**: hàng đợi, cài đặt nhanh;
  - **Âm thanh, video & cắt đoạn**: phần còn thiếu sau Stage 7;
  - **Google AI Studio**: model, chế độ, ngôn ngữ, từ vựng, prompt, Tự đổi model;
  - **Người nói**;
  - **Hạn mức & sử dụng**: Bây giờ (đếm ngược), Các model, Hạn mức từng model, Lịch sử;
  - **Cài đặt**: Bản chép lời, Hàng đợi, Hệ thống, Thông tin;
  - **Nhật ký**;
  - **Tự động hoá**: phần làm ở Stage 6, hoàn thiện thêm.
- i18n: chuyển nội dung `gui/i18n.py` sang `strings.xml` vi / en, theo văn phong đã chuẩn hoá trong REFACTOR_PLAN của
  desktop.
- Chủ đề sáng / tối / theo hệ thống.
- Hướng dẫn lần đầu mở app: API key, quyền đọc tệp, tắt tối ưu pin, quyền thông báo (D116: thẻ việc cần làm).
- Xuất / nhập cài đặt (D112: INI như `config.txt` desktop).
- `[VIDEO] save_extracted` (D68, D120) và prompt sửa được (Phát sinh Stage 3), vì cần công tắc / ô trên giao diện.

### Không làm

- Tính năng mới: "tiết kiệm dữ liệu" bằng Opus và "chỉ gửi khi có Wi-Fi" (D118).
- Bố cục riêng cho máy tính bảng (D113).
- Đổi định dạng bản chép lời: mục `?` trong "Thời gian nói" (Phát sinh Stage 5) giữ như desktop, golden vẫn khớp.
- Mục desktop không có nghĩa trên điện thoại: `[GUI] accent_color`, mica, tỉ lệ, cỡ chữ nhật ký, nhớ cửa sổ, âm báo,
  giữ máy thức, `open_when_done`.

## Nguồn tham khảo trong Guzz desktop

- `gui/pages/*`, `gui/i18n.py`, `gui/widgets.py`, `gui/model_ui.py`, `gui/config_io.py`
- `REFACTOR_PLAN.md`: quy tắc văn phong giao diện

## Kết quả bàn giao

- Đủ các trang; mọi chuỗi trên giao diện nằm trong `strings.xml`.

## Tiêu chí hoàn thành

- [ ] Không còn chuỗi giao diện viết cứng trong code: lint `HardcodedText` / `MissingTranslation` sạch, và
      `tools/check_strings.ps1` (bắt chuỗi viết cứng trong Compose, lint không thấy) không báo gì.
- [ ] Đổi ngôn ngữ và chủ đề không cần khởi động lại app.
- [ ] Màn hình nhỏ (360 dp) và cỡ chữ lớn không bị vỡ bố cục.

## Câu hỏi cần chốt khi plan chi tiết

Đã chốt ngày 2026-11-17, xem "Quyết định".

- Điều hướng: thanh dưới (bottom navigation) hay menu ngăn kéo? → D110
- Màu: Material You (lấy màu theo hình nền) hay màu nhấn riêng của Guzz? → D111
- Có nhập `config.txt` của desktop không? → D112
- Có làm bố cục riêng cho máy tính bảng không? → D113

## Rủi ro

- Desktop có rất nhiều tuỳ chỉnh → cần chọn mục nào đưa ra màn hình chính, mục nào giấu vào "Nâng cao" (D117; bảng
  "Mục nào ở đâu" bên dưới).
- Làm song song với Stage 6 trên cùng `:app`: dễ xung đột khi gộp. Cách tránh ở mục "Làm song song với Stage 6".
- Đổi sang một Activity (D114) đụng gần hết code giao diện cũ: làm thành từng bước nhỏ, mỗi bước build + thử được.

## Quyết định

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D110 | Điều hướng | **Thanh dưới 3 tab**: Chuyển đổi · Hạn mức · Cài đặt. Tab Cài đặt là danh sách mở các trang con | Một tay bấm được, vừa 360 dp; desktop có 7 trang nên gom phần cài đặt vào một tab. |
| D111 | Màu | **Material You** (Android 12+) **+ bộ màu Guzz dự phòng** cho máy cũ, không có công tắc | Ít việc nhất; máy cũ vẫn có màu riêng thay cho màu mặc định của Material. |
| D112 | Xuất / nhập cài đặt | **INI như `config.txt` desktop**; nhập được `config.txt` của desktop (mục chỉ có trên desktop bỏ qua, sai kiểu thì báo rõ); không xuất API key; golden so với `config_io.py` / `configparser` | Một định dạng cho cả hai app, key đã đặt tên `SECTION.key` theo desktop (D16). |
| D113 | Máy tính bảng | **Không**, một bố cục dọc, màn rộng thì giới hạn bề ngang nội dung; thử xoay ngang một lần | Không có máy tính bảng để thử; giữ không vỡ là đủ. |
| D114 | Tổ chức màn hình | **Một Activity + Navigation Compose**; Activity cũ (Google AI, Âm thanh, Người nói, Xem bản chép lời) thành route; trang của Stage 6 thành route ở nhóm H | Back, giữ trạng thái tab, đổi ngôn ngữ / chủ đề gọn hơn. Màn hình thử của bản debug giữ Activity riêng. |
| D115 | Đổi ngôn ngữ | **Chọn trong app** (Theo hệ thống / Tiếng Việt / English) bằng per-app locale của AndroidX AppCompat | Chạy từ Android 8, không cần khởi động lại; Android 13+ hiện cả trong Cài đặt hệ thống. |
| D116 | Hướng dẫn lần đầu | **Thẻ "Thiết lập ban đầu" trên màn hình chính**: mục chưa xong kèm nút làm ngay, tự ẩn khi đủ; cùng danh sách ở Cài đặt → Hệ thống | Không chặn dùng app; quay lại kiểm được bất cứ lúc nào. |
| D117 | Mục ít dùng | **Phần "Nâng cao" gập lại cuối từng trang** | Trang ngắn, mục vẫn nằm cạnh mục liên quan. |
| D118 | Opus "tiết kiệm dữ liệu", "chỉ gửi khi có Wi-Fi" (Phát sinh Stage 1, 4) | **Không làm** ở Stage 8 | Tính năng mới, ngoài phạm vi; xét lại sau khi phát hành. |
| D119 | `languages` cho model `*-transcribe` (Phát sinh Stage 3) | **Gợi ý bấm để điền**: chọn model `*-transcribe` mà ô Ngôn ngữ trống thì hiện dòng nhắc + nút "Điền vi-VN, en-US"; mặc định giữ trống như desktop, không gửi thử | Giải quyết chữ quá thưa mà không đổi mặc định, không gửi gì lên Google. |
| D120 | `[VIDEO] save_extracted` (D68) | **Làm, thử bằng Gemini giả** (test JVM + hàng đợi trên máy ảo), không gửi thật | Lưu FLAC không phụ thuộc Gemini. |
| D121 | Kiểm giao diện | **Máy ảo + script + vài Compose UI test**: script đặt 360 dp / `font_scale` 2.0 / tối / en trên `guzz_15t`, đọc bằng `android layout`, chụp màn hình; Compose UI test cho điều hướng và đổi ngôn ngữ; không thêm thư viện screenshot | Đủ kiểm tiêu chí, không thêm phụ thuộc và ảnh mẫu vào repo. |

Mặc định không hỏi (ghi để session làm không phải đoán):

- Cài đặt mới: `[GUI] language` (`auto` / `vi` / `en`, mặc định `auto`, khác desktop `vi` vì điện thoại theo hệ thống:
  `sameAsDesktop = false`) và `[GUI] theme` (`auto` / `light` / `dark`, như desktop). AppCompat tự lưu ngôn ngữ; `GUI.language`
  là bản để xuất / nhập, đồng bộ hai chiều lúc mở app.
- Prompt sửa được (Phát sinh Stage 3) lưu ở tệp `files/prompts/prompt.txt` và `files/prompts/prompt_diarization.txt`;
  không có tệp hoặc tệp trống thì dùng `DefaultPrompt`. Desktop cũng giữ prompt ở tệp riêng (`prompt_file`), nên xuất /
  nhập INI không mang theo prompt.
- Nhập INI không ghi đè `OUTPUT.output_dir` (desktop là đường dẫn Windows, mobile là URI SAF) và các mục `[APP]`.
- Chuỗi trong log giữ tiếng Việt như desktop; chỉ chuỗi người dùng nhìn thấy mới qua `strings.xml`.

## Kế hoạch chi tiết

### Làm song song với Stage 6

Stage 8 làm ở worktree riêng, cùng lúc Stage 6 làm ở worktree khác. Thứ tự nhóm:

- **A–F: không đụng intent và không đụng code pipeline / hàng đợi.** Làm ngay, không chờ Stage 6.
- **G: đụng `:core:pipeline` / `:feature:queue`** (mã lỗi đa ngôn ngữ, `save_extracted`, prompt sửa được). Không cần API
  của Stage 6 nhưng sửa đúng các tệp Stage 6 sửa (`Queue.kt` / `QueueItem`, `FilePipeline`, `QueueController`). **Chờ Stage 6
  gộp vào `main`**, rebase nhánh Stage 8 rồi mới làm.
- **H: dùng API intent của Stage 6.** **Chờ Stage 6 gộp vào `main`.**
- **I: kiểm cuối, đóng stage.**

Chỗ dễ đụng tệp với Stage 6 và cách tránh:

| Tệp / chỗ | Stage 6 có thể sửa | Stage 8 làm thế này |
| --- | --- | --- |
| `strings.xml` | Không sửa: chuỗi của Stage 6 ở `feature/automation/src/main/res/values{,-en}/strings_automation.xml` | Chuỗi mới để ở **tệp riêng theo trang** (`strings_nav.xml`, `strings_settings.xml`, `strings_quota.xml`, `strings_logs.xml`, `strings_setup.xml`…); `strings.xml` cũ chỉ xoá chuỗi hết dùng, làm **một commit riêng ở nhóm I** (sau khi Stage 6 gộp). |
| `SettingKeys.kt` | Thêm khối `[AUTOMATION]` ở **cuối** tệp / cuối `all` (`enabled`, `secret`, `include_paths`, `save_beside_source`, `source_dirs`) | Mục `[GUI]` đặt thành **một khối riêng ngay sau `[SYSTEM]`**, không chèn vào cuối tệp / cuối `all`. |
| `AppContainer.kt` | Vài dòng cuối class | Thêm **tối đa vài dòng** (`lazy` ngay sau `queueController`, không ở cuối class); logic mới để ở lớp riêng (`ConfigTransfer`, `SetupChecklist`, `LogReader`). |
| `MainActivity.kt` | Chỉ thêm một nút mở `AutomationActivity`; Chia sẻ tới Guzz là `ShareActivity` riêng, không đụng `onNewIntent` | B1 chuyển `QueueScreen` ra `ui/convert/`, `MainActivity` chỉ còn khung (NavHost). Gộp: bỏ nút của Stage 6, thay bằng mục trong tab Cài đặt (H1). |
| `AndroidManifest.xml` (main + `sideload`) | Receiver, `CommandActivity`, `ShareActivity`, `AutomationActivity`, quyền `MANAGE_EXTERNAL_STORAGE` (sideload) | Chỉ xoá các `<activity>` cũ (một commit ở B6) và thêm service lưu locale của AppCompat (A3); `AutomationActivity` bỏ ở H1. |
| `Queue.kt` / `QueueItem`, `PipelineConfig`, `QueueController`, `QueueDebugReceiver` | `requestId`, ghi đè cài đặt cho riêng tệp, `ownedCopy`, `charCount` | Không sửa trước khi Stage 6 gộp (nhóm G). |
| `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` | Thêm `:feature:automation`, thư viện | Thêm dòng ở vị trí theo thứ tự chữ cái, không sắp lại cả khối. |
| `CLAUDE.md` "Lệnh thường dùng" | Khối "Intent (Stage 6)" cuối mục | Thêm khối "Giao diện (Stage 8)" riêng **sau** khối của Stage 6. |

Gộp: Stage 6 gộp vào `main` trước; Stage 8 rebase lên `main` trước nhóm G, giải xung đột, chạy lại `.\gradlew test lint`
và B7 (Compose UI test).

### Mục nào ở đâu (D117)

| Trang | Hiện sẵn | Nâng cao (gập) |
| --- | --- | --- |
| Chuyển đổi | Hàng đợi; cài đặt nhanh: model, cách nhận diện người nói, preset âm thanh, thư mục lưu | — |
| Google AI | API key (đầu trang); Kiểm tra kết nối; model (cách xa ô key, Phát sinh Stage 3); `transcribe_mode`; `languages` (+ gợi ý D119); `vocabulary`; prompt (G3) | `store_on_server`, `token_rate_limit`, `timeout_seconds`, `chunk_retries`, `delete_remote_file`; **Tự đổi model**: `[FALLBACK]` đủ mục |
| Âm thanh | Preset; bật / tắt từng bước; lưu âm thanh sạch (đã có, D89); video: `audio_track`, `skip_silent`, `save_extracted` | Ô số `[AUDIO]` (D89); `[CHUNKING]` `chunk_minutes`, `search_seconds`, `min_last_chunk_seconds` |
| Người nói | Như D76 (bật, cách, số người, min–max, nhãn) + `timestamps`, `map_delivery` | Ngưỡng làm mượt (`smooth`, `sandwich_*`, `density_*`, `merge_gap_sec`), `run_on`, mẫu đoạn văn, `max_paragraph_sec`, `paragraph_gap_sec`, `mark_review`, prompt người nói (G3) |
| Hạn mức | Bây giờ; Các model (mỗi model một thẻ) + Mở khoá / Quên hạn mức đã học / Đặt lại bộ đếm | Hạn mức từng model (`[QUOTAS]`); Lịch sử + Xoá lịch sử |
| Cài đặt → Bản chép lời | `filename_template` (+ xem trước tên), `extension`, `on_conflict`, `add_header` | `encoding`, `newline`, `mark_chunks`, `save_speaker_map` |
| Cài đặt → Hàng đợi | `notify_when_done`, `on_file_error` | — |
| Cài đặt → Hệ thống | Ngôn ngữ, Chủ đề; Thiết lập ban đầu; Xuất / Nhập cài đặt | `log_level`, `log_size_mb`, `old_log_count`, `delete_temp_when_done` |
| Cài đặt → Nhật ký | Xem `guzz.log`, lọc mức, tìm, chia sẻ tệp log | — |
| Cài đặt → Thông tin | Phiên bản, flavor, giấy phép model / thư viện, nơi để log | — |
| Cài đặt → Tự động hoá | Trang của Stage 6 (H1) | Theo Stage 6 |

### A. Nền: thư viện, cài đặt giao diện, chủ đề, ngôn ngữ

- [x] A1. Thêm `androidx.navigation:navigation-compose` (route kiểu an toàn, kotlinx.serialization đã có) và
      `androidx.appcompat:appcompat` vào version catalog + `app/build.gradle.kts`. Kiểm: `.\gradlew assembleSideloadDebug
      assemblePlayDebug` xanh.
- [x] A2. `[GUI] language`, `[GUI] theme` trong `SettingKeys` (khối riêng sau `[SYSTEM]`, vào `all` để xuất / nhập). Kiểm:
      test `:core:common` đọc / ghi, giá trị lạ lùi về mặc định.
- [x] A3. Ngôn ngữ (D115): `MainActivity` thành `AppCompatActivity`, `themes.xml` đổi sang `Theme.AppCompat.DayNight.NoActionBar`
      (cả `values-night`), manifest thêm `AppLocalesMetadataHolderService` (`autoStoreLocales`), bật
      `androidResources.generateLocaleConfig` + `res/resources.properties` (`unqualifiedResLocale=vi`). Đổi mục Ngôn ngữ thì
      gọi `AppCompatDelegate.setApplicationLocales` và ghi `GUI.language`. Kiểm: trên máy ảo đổi vi ↔ en, giao diện đổi
      ngay, mở lại app vẫn giữ; Android 13+ thấy Guzz trong Cài đặt → Ngôn ngữ ứng dụng.
- [x] A4. Chủ đề (D111): `GuzzTheme` đọc `GUI.theme` (Flow từ Settings), Android 12+ màu động, máy cũ bộ màu Guzz sáng /
      tối (tệp `ui/theme/Color.kt`); thanh trạng thái / điều hướng đổi màu theo. Kiểm: đổi Sáng / Tối / Theo hệ thống
      không cần khởi động lại; xem bộ màu dự phòng bằng cờ chỉ có ở bản debug (tắt màu động) trên máy ảo.
- [x] A5. `tools/device/ui_check.ps1` (D121): tham số `-Small` (`wm size` + `wm density` cho bề ngang 360 dp), `-FontScale 2.0`,
      `-Dark`, `-Lang en` (`cmd locale set-app-locales`), `-Landscape`, `-Out <thư mục>`; với mỗi trang trong danh sách:
      mở bằng deep link debug (B2), ghi `android layout` ra `.json` và chụp `.png`; `-Reset` trả máy ảo về như cũ. Kiểm: chạy
      trên `guzz_15t` ra đủ tệp cho trang Chuyển đổi.

### B. Khung một Activity (D110, D114)

- [x] B1. Tách code: `QueueScreen`, `QuickSettings`, `QueueRow`, `Banner` từ `MainActivity.kt` sang `ui/convert/`; màn hình
      của `GoogleAiActivity`, `AudioActivity`, `SpeakersActivity`, `TranscriptActivity` sang `ui/googleai/`, `ui/audio/`,
      `ui/speakers/`, `ui/transcript/` dạng composable nhận tham số (Activity cũ tạm gọi composable đó). Chưa đổi hành vi.
      Kiểm: build + thử nhanh 4 trang trên máy ảo như trước.
- [x] B2. `ui/nav/`: route (`Convert`, `Quota`, `Settings`, `GoogleAi`, `Audio`, `Speakers`, `Output`, `Queue`, `System`, `Logs`,
      `About`, `Transcript(location, name)`), `NavHost` trong `MainActivity`, thanh dưới 3 tab (`NavigationBar`, giữ trạng thái
      từng tab, Back về tab Chuyển đổi rồi mới thoát). Bản debug nhận extra `route` để `ui_check.ps1` mở thẳng một trang.
      Kiểm: Compose UI test (B7) và thử Back trên máy ảo.
- [x] B3. Trang Cài đặt dạng danh sách (biểu tượng + tên + mô tả một dòng) mở các trang con; trang con có thanh tiêu đề với
      nút Back. Kiểm: `android layout` thấy đủ mục, bấm từng mục mở đúng trang.
- [x] B4. Bộ widget chung `ui/components/`: `SettingsPage` (cuộn, giới hạn bề ngang ~640 dp, D113), `SectionCard`,
      `SwitchRow`, `ChoiceRow` (từ `ChoiceSetting`), `NumberRow` (kiểm `min` / `max` của `SettingKey`, báo lỗi dưới ô, không
      lưu giá trị sai), `TextRow`, `AdvancedSection` (gập, D117), nút "Khôi phục mặc định" từng trang. Ghi ngay khi đổi
      (như các trang Stage 3–7), không có nút Lưu. Kiểm: Compose UI test cho `NumberRow` (số ngoài khoảng không được lưu).
- [x] B5. Nối các trang cũ vào route: Chuyển đổi (tab 1), Google AI / Âm thanh / Người nói (trang con của Cài đặt; nút trên
      màn hình chính đổi sang điều hướng route), Xem bản chép lời (route `Transcript`, nút Mở / Chia sẻ giữ nguyên).
      Kiểm: thử trên máy ảo mọi lối vào cũ (nút ở hàng đợi, banner "chưa có key", cài đặt nhanh).
- [x] B6. Xoá 4 Activity cũ khỏi code và manifest (một commit riêng). `AudioDebugActivity` của bản debug giữ nguyên, mở
      từ mục debug trong Cài đặt → Hệ thống. Kiểm: `.\gradlew lint` không còn cảnh báo class thừa; `adb shell am start`
      của `CLAUDE.md` vẫn mở app; `audio_debug.ps1` vẫn chạy.
- [x] B7. Compose UI test trong `app/src/androidTest` (chạy như test Keystore ở `CLAUDE.md`): bấm 3 tab, mở từng trang con
      của Cài đặt rồi Back; đổi ngôn ngữ sang en thì nhãn tab đổi. Kiểm: `am instrument` xanh trên máy ảo.

### C. Các trang cài đặt (không đụng intent)

- [x] C1. Google AI đầy đủ (bảng "Mục nào ở đâu"): ô key ở đầu, ô model cách xa và ghi rõ nhãn; `transcribe_mode`,
      `languages` + gợi ý D119 (`model.endsWith("-transcribe")` và ô trống → dòng nhắc + nút "Điền vi-VN, en-US"),
      `vocabulary`; Nâng cao: mục `[GOOGLE_AI]` còn lại và cả `[FALLBACK]` (danh sách model dự phòng sửa được, thứ tự).
      Ràng buộc model ↔ cách nhận diện như desktop (`model_ui.constraint_hint`). Kiểm: đổi từng mục rồi
      `queue_debug.ps1 -Status` / đọc lại DataStore thấy giá trị; gợi ý hiện / ẩn đúng khi đổi model.
- [x] C2. Âm thanh: video (`audio_track`, `skip_silent`, công tắc `save_extracted` — chỉ lưu cài đặt, phần pipeline ở G2);
      Nâng cao: ô số `[AUDIO]` (D89) và `[CHUNKING]`. Kiểm: đổi preset thì ô số cập nhật; sửa một ô số thì preset thành
      "Tuỳ chỉnh" như desktop (`presets.py`).
- [x] C3. Người nói: thêm `timestamps`, `map_delivery`; Nâng cao: ngưỡng làm mượt, `run_on`, mẫu đoạn văn (kiểm chỗ giữ chỗ
      như desktop), độ dài đoạn. Kiểm: đổi mẫu đoạn văn sai thì báo lỗi, không lưu; chạy hàng đợi Gemini giả với
      `-Speakers hybrid` vẫn ra bản chép lời.
- [x] C4. Bản chép lời: các mục `[OUTPUT]` + dòng xem trước tên tệp (dùng `OutputNaming`, như `filename_preview` của
      desktop). Hàng đợi: `notify_when_done`, `on_file_error`. Kiểm: đổi `filename_template` thì xem trước đổi theo; chạy hàng
      đợi Gemini giả ra tên tệp đúng mẫu.
- [x] C5. Hệ thống: Ngôn ngữ, Chủ đề (A3, A4); Nâng cao: `log_level`, `log_size_mb`, `old_log_count`,
      `delete_temp_when_done`. Thông tin: phiên bản (`versionName` + `versionCode`), flavor, giấy phép (sherpa-onnx Apache-2.0,
      segmentation-3.0 MIT, 3D-Speaker Apache-2.0, thư viện AndroidX / OkHttp), đường dẫn log. Kiểm: đổi `log_level` sang
      DEBUG thì `guzz.log` có dòng DEBUG ngay (không cần mở lại app).

### D. Hạn mức và Nhật ký

- [x] D1. Phần tính toán của trang Hạn mức để ở `:core:gemini` (`QuotaView`, JVM thuần): từ `UsageSnapshot` + `now` ra
      trạng thái "Bây giờ" (đang chờ model nào, đếm ngược, giờ đặt lại hằng ngày), từng model (đã dùng / hạn mức, khoá tới
      lúc nào, lý do), lịch sử. Port của `gui/pages/quota.py` (`_paint_now`, `_paint_models_table`, `_paint_events`). Chữ
      hiển thị là mã + tham số, `:app` ghép bằng `strings.xml`. Kiểm: unit test với snapshot dựng tay (khoá, hết ngày, đủ
      model).
- [x] D2. Trang Hạn mức (tab 2): Bây giờ (đếm ngược mỗi giây khi trang hiện), Các model (thẻ), nút Mở khoá / Quên hạn mức
      đã học / Đặt lại bộ đếm (hỏi lại trước khi làm); Nâng cao: Hạn mức từng model (sửa `QUOTAS.lines`, thêm / xoá model),
      Lịch sử + Xoá lịch sử. Đọc lại khi `revision()` đổi (hàng đợi chạy nền ghi sổ). Ghi rõ "không đếm chung với desktop"
      (README rủi ro 5). Kiểm: `tools/device/quota_fixture.py` sinh một `su_dung.json` mẫu (có model bị khoá, hạn mức
      đã học, lịch sử; cấu trúc như ca trong `testdata/golden/gemini/tracker.json`), đẩy vào `files/quota/` bằng
      `adb shell run-as`, mở trang thấy đúng số / khoá; bấm Mở khoá thì tệp đổi.
- [x] D3. Nhật ký: đọc đuôi `guzz.log` (và `guzz.log.1` khi cần) ở luồng IO, lọc Tất cả / Cảnh báo / Lỗi như
      `logs.py` (`_line_level`), ô tìm, tự làm mới khi trang hiện, nút Chia sẻ tệp log (FileProvider), cuộn xuống cuối.
      Kiểm: unit test bộ lọc mức (dòng nhiều dòng của traceback đi theo dòng đầu); trên máy ảo chạy hàng đợi Gemini giả
      thì dòng mới hiện ra.

### E. Tiếng Việt / tiếng Anh

- [x] E1. Chuyển mọi chuỗi viết cứng còn lại trong `:app` (kể cả `contentDescription`, hộp thoại, Toast) sang
      `strings_*.xml` vi / en, câu chữ lấy từ `gui/i18n.py` (bản đã chuẩn hoá theo REFACTOR_PLAN). Kiểm: E3.
- [x] E2. `formatCountdown` / `formatLocalTime` và các chuỗi khác từ module JVM hiện trên giao diện: chuyển sang mã + tham
      số (hoặc định dạng ở `:app`). Kiểm: trang Hạn mức tiếng Anh không còn chữ Việt.
- [x] E3. `tools/check_strings.ps1`: tìm chuỗi có chữ cái trong `Text("…")`, `text = "…"`, `label = { Text("…`,
      `contentDescription = "…"`, `Toast.makeText(…, "…"` ở `app/src/main/kotlin` và `feature/*/src/main/kotlin`; in tệp:dòng,
      thoát mã 1 khi có. Bật lint `MissingTranslation` / `HardcodedText` ở mức lỗi. Kiểm: script báo 0, `.\gradlew lint`
      sạch.

### F. Thiết lập ban đầu, xuất / nhập cài đặt

- [x] F1. `SetupChecklist` (D116): API key đã có; thư mục lưu đã chọn (SAF); quyền thông báo (Android 13+); miễn tối ưu pin
      (`isIgnoringBatteryOptimizations`; bản sideload có nút xin `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, bản play mở trang
      cài đặt pin). Mỗi mục kèm nút làm ngay; thẻ trên màn hình Chuyển đổi tự ẩn khi đủ, có nút "Để sau" (ẩn tới lần mở
      app sau). Cài đặt → Hệ thống có cùng danh sách. Trước khi Stage 6 gộp, phần kiểm pin gọi thẳng `PowerManager` ở
      một hàm nhỏ; H2 đổi sang `BatteryStatus` của Stage 6 và bỏ hàm đó. Mục quyền đọc tệp thêm ở H2. Kiểm: trên máy ảo mới cài
      (`pm clear`), thẻ hiện 4 mục; làm từng mục thì mục đó biến mất (`dumpsys deviceidle whitelist -<package>` để thử lại).
- [x] F2. `ConfigIni` ở `:core:common` (JVM thuần, D112): ghi INI (`[SECTION]`, `key = value`, dòng đầu ghi phiên bản Guzz
      Mobile và ngày), đọc theo đúng luật `configparser` của desktop (ghi chú cuối dòng chỉ khi `#` / `;` đứng sau khoảng
      trắng — sửa lỗi `C#, F#` của `config_io.py`; giá trị nhiều dòng; key không phân biệt hoa thường). Áp vào Settings:
      mục biết thì chuyển kiểu bằng `SettingKey`, mục lạ / chỉ có trên desktop bỏ qua, sai kiểu / ngoài khoảng ghi vào báo
      cáo; không đụng `OUTPUT.output_dir` và `[APP]`. Port của `gui/config_io.py` (phần đọc). Kiểm: golden
      `tools/golden/gen_config.py` (đọc `config.default.txt` desktop và một tệp khó bằng `configparser`; cạnh
      `gen_config_keys.py` đã có, ra `testdata/golden/config/`) + `ConfigIniGoldenTest`; xuất rồi nhập lại ra đúng giá trị cũ.
- [x] F3. Nút Xuất (SAF `CreateDocument`, tên gợi ý `guzz-config.txt`) và Nhập (`OpenDocument`) ở Hệ thống; nhập xong hiện
      tóm tắt: đã áp N mục, bỏ qua M mục, lỗi K mục (danh sách gập). Kiểm: trên máy ảo nhập `config.default.txt` của desktop
      (adb push) thì không lỗi, mục khác desktop (vd `[GUI] language`) đổi đúng; xuất ra tệp không có API key.

### G. Phần đụng pipeline / hàng đợi — chờ Stage 6 gộp vào `main`

- [x] G0. Rebase nhánh Stage 8 lên `main` đã có Stage 6; giải xung đột theo bảng ở trên. Kiểm: `.\gradlew test lint` và B7
      xanh.
- [x] G1. Mã lỗi và tên bước (Phát sinh Stage 4): `PrepareStep` hiển thị qua `strings.xml` (`:app` / `:feature:queue` ghép
      tên từ mã); lỗi của pipeline / hàng đợi có thêm mã + tham số (vd `QueueItem.errorCode`, `errorArgs`) bên cạnh `error`
      tiếng Việt (giữ cho log và cho sự kiện `FILE_FAILED` của Stage 6). Giao diện có mã thì dùng `strings.xml`, không có
      mã thì hiện `error`. Thông báo của `QueueService` cũng vậy. Kiểm: test `:core:pipeline` cho mã lỗi; máy ảo tiếng Anh
      chạy hàng đợi Gemini giả với tệp hỏng → lỗi hiện tiếng Anh.
- [x] G2. `save_extracted` (D68, D120): nguồn là video và bật mục này thì trước khi xoá `goc.pcm` (D66) mã hoá FLAC vào thư
      mục tạm; xong tệp thì ghi `<tên>.am_thanh.flac` cạnh bản chép lời (cùng `OutputSink`); đoạn lấy từ cache mà không còn
      PCM thì giải mã lại. Port theo `transcribe.py` (`save_extracted`). Kiểm: test `:core:pipeline` (có / không cache);
      máy ảo: `queue_debug.ps1 -Fake on`, thêm `testdata/media/two_tracks.mp4` → có tệp `.am_thanh.flac` mở được, đúng luồng đã chọn.
- [ ] G3. Prompt sửa được: `FilePipeline` đọc prompt qua một `PromptSource` (tệp `files/prompts/*.txt`, trống thì
      `DefaultPrompt` / prompt người nói mặc định). Ô sửa prompt ở Google AI và Người nói (Nâng cao), nút Khôi phục mặc
      định; ô bị khoá khi model là `*-transcribe` (không nhận prompt), ghi rõ lý do. Kiểm: test `:core:pipeline` dùng prompt
      tệp; Gemini giả ghi prompt vào log DEBUG thấy nội dung mới.

### H. Phần dùng API intent của Stage 6 — chờ Stage 6 gộp vào `main`

- [ ] H1. `AutomationSettingsScreen(model: AutomationSettingsModel)` của `:feature:automation` thành route trong tab Cài đặt
      (D114); bỏ `AutomationActivity` và nút mở nó trên màn hình chính. Nếu cần, chỉnh trang dùng widget chung B4 và đưa
      mục ít dùng vào Nâng cao (sửa trong `:feature:automation`, chuỗi vẫn ở `strings_automation.xml`, đủ en). Kiểm: B7 thêm
      bước mở trang này; `ui_check.ps1` chạy trên trang này.
- [ ] H2. Thiết lập ban đầu (F1) thêm mục quyền đọc tệp theo D6: bản sideload "Truy cập mọi tệp" (`MANAGE_EXTERNAL_STORAGE`),
      bản play Thư mục nguồn SAF, dùng `FileAccess` của Stage 6; mục pin đổi sang `BatteryStatus`. Kiểm: máy ảo thu hồi
      quyền (`appops set … MANAGE_EXTERNAL_STORAGE ignore`) thì mục hiện lại.
- [ ] H3. Xuất / nhập (F2) gồm mục `[AUTOMATION]` của Stage 6, **trừ `secret`** (như API key); nhập không ghi đè
      `source_dirs` (URI SAF chỉ có nghĩa trên máy đã cấp, như `OUTPUT.output_dir`). Kiểm: xuất ra không có `secret`; nhập
      lại giữ công tắc cho phép, `source_dirs` không đổi.
- [ ] H4. Lối vào từ ngoài sau khi đổi sang một Activity: `ShareActivity` (và thông báo "Bấm để bắt đầu" của Stage 6) mở
      màn hình chính bằng intent launcher → vào đúng tab Chuyển đổi, không mở chồng `MainActivity` khi app đang mở
      (`launchMode="singleTop"` hoặc cờ intent, xử lý `onNewIntent` chọn tab). Kiểm: `adb shell am start -a
      android.intent.action.SEND` với một tệp âm thanh lúc app đang mở ở tab Cài đặt và lúc đã đóng; macro mẫu của Stage 6
      vẫn chạy.

### I. Kiểm cuối và đóng stage

- [ ] I1. Dọn `strings.xml` cũ (chuỗi hết dùng sau B6, G1) — một commit riêng. Kiểm: lint `UnusedResources` không báo
      chuỗi của `:app`.
- [ ] I2. `ui_check.ps1` cho mọi trang ở 4 cấu hình: mặc định; `-Small -FontScale 2.0`; `-Dark -Lang en`; `-Landscape`. Xem
      ảnh: không chữ bị cắt, không nút tràn, không cuộn ngang. Ghi thư mục ảnh vào Nhật ký (ngoài repo,
      `D:\GuzzMobileData\stage-08`). Kiểm: tiêu chí 360 dp / cỡ chữ lớn.
- [ ] I3. Đổi ngôn ngữ và chủ đề lúc hàng đợi đang chạy (Gemini giả): giao diện đổi ngay, hàng đợi không dừng. Kiểm: tiêu chí
      "không cần khởi động lại".
- [ ] I4. Điện thoại thật (Xiaomi 15T) một lượt: thẻ Thiết lập ban đầu với HyperOS (pin, thông báo), đổi ngôn ngữ trong
      Cài đặt hệ thống, sáng / tối theo hệ thống. Kiểm: người dùng xem cùng.
- [ ] I5. `.\gradlew test lint`, `tools\check_strings.ps1`, B7 xanh; `CLAUDE.md` thêm khối lệnh "Giao diện (Stage 8)"
      (`ui_check.ps1`, `check_strings.ps1`, `gen_config.py`, B7); đánh dấu tiêu chí hoàn thành, Stage 8 ✅ kèm ngày.

## Nhật ký

- 2026-11-18, A–F trên nhánh `stage-8` (worktree `D:\GuzzMobile-stage8`), thử trên máy ảo `guzz_15t` (emulator-5554):
  - **Khác kế hoạch:** B1 không qua bước trung gian "Activity cũ gọi composable": viết thẳng các trang thành route
    (B2–B6 cùng lúc), vì đằng nào B6 cũng xoá Activity cũ.
  - **Khác kế hoạch:** `MainActivity` thêm `launchMode="singleTop"` ngay ở B2 (kế hoạch để H4): không có thì
    `am start --es route …` khi app đang mở chỉ đưa task lên trước, không gọi `onNewIntent`, `ui_check.ps1` không đổi
    được trang.
  - **Khác kế hoạch:** Hạn mức từng model (`[QUOTAS]`) sửa bằng một ô nhiều dòng `model = token/phút, yêu cầu/phút,
    yêu cầu/ngày` (kiểm từng dòng đọc được), không làm lưới ô số như desktop: thêm / xoá model là thêm / xoá dòng.
  - **Khác kế hoạch:** mục pin của Thiết lập ban đầu mở trang thông tin app ở cả hai bản (chưa xin
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: quyền khai trong manifest sideload là việc của Stage 6); H2 đổi sang
    `BatteryStatus`.
  - Nhãn thanh dưới chỉ phóng chữ tới ×1,3: ở 360 dp + cỡ chữ ×2, "Chuyển đổi" xuống dòng và bị cắt.
  - Nhập cài đặt: ngôn ngữ nhập vào chỉ được áp khi đóng hộp tóm tắt (áp ngay thì AppCompat dựng lại Activity, hộp
    mất). Không đọc `config.txt` kiểu cũ (section tiếng Việt, `LEGACY_SECTIONS` của desktop).
  - E2: `formatCountdown` / `formatLocalTime` chỉ ra số và giờ (`3:05:09`, `14:00`, `18/09 14:00`), không có chữ, nên
    không cần đổi; lý do khoá, loại lỗi, loại sự kiện của trang Hạn mức là mã, ghép câu ở `strings_quota.xml`.
  - Kết quả thử: `.\gradlew lint test` xanh; Compose UI test (B7) 3/3 trên máy ảo; `tools\check_strings.ps1` sạch;
    nhập `config.default.txt` desktop qua trình chọn tệp: áp 76 mục, bỏ qua 29 (mục chỉ có trên desktop), 0 lỗi, giao
    diện sang tiếng Việt theo `[GUI] language = vi`; đổi chủ đề Tối ngay, cùng tiến trình; `quota_fixture.py` → trang
    Hạn mức hiện khoá theo ngày / khoá tay / hạn mức đã học, bấm Mở khoá thì sổ có sự kiện `mo_khoa`; thẻ Thiết lập ban
    đầu sau `pm clear` hiện 3 việc (máy ảo cài qua adb đã được miễn tối ưu pin sẵn); ảnh `ui_check.ps1` mặc định và
    360 dp / chữ ×2 / tối / vi ở `D:\GuzzMobileData\stage-08`.
  - Khởi động nguội bản debug trên máy ảo mất khoảng 2,5 s (`am start -W`): chưa so với trước Stage 8, xem lại ở I4
    trên máy thật.
  - Sự cố: `gradlew installSideloadDebug` chạy khi chưa đặt `ANDROID_SERIAL` đã cài đè bản stage-8 lên máy ảo
    `guzz_15t_s6` của session Stage 6 (đã báo session đó). Từ đó luôn đặt `ANDROID_SERIAL`.
  - Nhóm G, H chờ Stage 6 gộp vào `main`.
- 2026-11-18, G0–G1 (sau khi Stage 6 gộp vào `main`, 1272805):
  - G0: dùng `git merge main` (theo lời giao việc), không rebase. Xung đột chỉ ở `MainActivity.kt` (giữ bản Stage 8: mục
    "Tự động hoá" Stage 6 thêm vào menu ⋮ của QueueScreen cũ, H1 làm lại thành route) và manifest (giữ cả service ngôn
    ngữ lẫn `AutomationActivity`). `test lint` xanh, B7 3/3, cả 11 test thiết bị của `:app` qua. Một lần chạy cả bộ, test
    đổi ngôn ngữ treo ở `waitForIdle` của Compose (stack: luồng test chờ Espresso idle); chạy lại riêng 3 lần, ghép với
    `AutomationIntentTest`, rồi cả bộ đều qua: coi là lỗi chập chờn của máy ảo, theo dõi ở I5.
  - G1: `QueueItem.errorCode` / `errorArgs` (mã ở `ErrorCodes`, `errorInfoOf` trong `:core:pipeline`); `error` tiếng Việt
    giữ nguyên cho log và `FILE_FAILED`. Thêm mã `media_read` (tệp hỏng, không có trong plan): thiếu nó thì ca thử "tệp
    hỏng" vẫn hiện tiếng Việt; chi tiết là thông báo gốc của hệ thống. Lỗi Google AI: loại lỗi dịch + chi tiết nguyên
    văn (câu của client / Google, không dịch từng câu). `QueueItem.step` lưu mã (`decode`, `bandpass`…), hàng đợi cũ có
    chữ tiếng Việt thì hiện nguyên văn. Chuỗi loại lỗi Google chuyển từ `quota_err_*` (`:app`) sang `google_err_*`
    (`:feature:queue`), tab Hạn mức dùng chung. Thử trên máy ảo: tệp rác `hong.m4a` → "Could not decode the file…
    Details: Failed to instantiate extractor."; đang chạy L1_20m hiện "Processing audio: decoding 33%", đổi sang tiếng
    Việt giữa chừng thì thành "Xử lý âm thanh: giải mã 37%" và hàng đợi chạy tiếp.
  - G2: `AudioPreparation` mã hoá `goc.pcm` thành `am_thanh.flac` trong thư mục tạm khi nguồn là video và bật
    `save_extracted` (`ChunkSource.prepare` thêm tham số `saveExtracted`); cache thiếu tệp này thì xử lý lại; xong bài thì
    chép `<tên>.am_thanh.flac` cạnh bản chép lời. Khác desktop: luôn FLAC 16 kHz một kênh (desktop giữ `extract_format` và
    chất lượng gốc), vì máy chỉ có PCM đã giải mã về 16 kHz. `queue_debug.ps1` thêm `-SaveExtracted on|off` và
    `-Track auto|0..3`. Thử trên máy ảo: `two_tracks.mp4`, luồng 2, Gemini giả → `two_tracks.am_thanh.flac` mở được bằng
    ffmpeg (16 kHz mono, 3 s), đỉnh phổ 880 Hz (đúng luồng 2; luồng 1 là 440 Hz).

## Phát sinh

- (Từ Stage 4, 2026-11-13) Tên bước xử lý âm thanh ("giải mã", "cắt lặng"…, từ `PrepareStep.label` / `AndroidChunkSource`)
  và thông báo lỗi của pipeline / hàng đợi luôn tiếng Việt kể cả khi giao diện tiếng Anh: chuyển sang mã + `strings.xml`.
  → G1.

- (Từ Stage 1, 2026-11-10) Đoạn gửi Gemini mặc định là FLAC (D41), 9,6 MB / 10 phút. Nếu muốn có tuỳ chọn "tiết kiệm dữ
  liệu di động": OGG-Opus 32 kbps chỉ 3 MB / 10 phút nhưng mã hoá mất 46 s (FLAC 4–6 s), và phải thử xem bản chép lời có
  kém đi không trước khi đưa ra (`spikes/formats/gemini_compare.py`, bước F3 cũ của Stage 1; gửi Gemini thì hỏi người
  dùng trước). → Không làm ở Stage 8 (D118).

- (Từ Stage 3, 2026-11-11) Trang Google AI của Stage 3 chỉ có key, Kiểm tra kết nối, model chính. Còn lại: các mục
  `[GOOGLE_AI]` khác (`transcribe_mode`, `languages`, `vocabulary`, `store_on_server`, `token_rate_limit`,
  `timeout_seconds`, `chunk_retries`, `delete_remote_file`), cả `[FALLBACK]` và `[QUOTAS]` (lưu ở `QUOTAS.lines`), sửa
  prompt (mặc định `DefaultPrompt` = `prompt.txt` desktop). Trang Hạn mức đọc `QuotaTracker.snapshot()` /
  `revision()` (sổ của app ở `AppContainer.quotaTracker`, tệp `files/quota/su_dung.json`), có nút Mở khoá
  (`unlock`), Quên hạn mức đã học (`forget_quota`), Đặt lại bộ đếm, Xoá lịch sử. → C1, D1–D2, G3.
- (Từ Stage 3, 2026-11-11) Gửi thật (D4): `gemini-3.5-transcribe` không có `languages` cho chữ rất thưa trên đoạn lớp
  học ồn lẫn Việt / Anh (smart 170 ký tự chỉ câu tiếng Anh; mốc từng từ 86 ký tự), `gemini-3.5-flash-lite` cùng đoạn
  562 ký tự. Khi làm trang Google AI đầy đủ: cân nhắc gợi ý điền `languages = vi-VN, en-US` cho model `*-transcribe`, và
  thử lại trên vài đoạn trước khi đổi mặc định (đổi mặc định khác desktop thì hỏi người dùng). → Gợi ý bấm để điền, không
  đổi mặc định (D119, C1).
- (Từ Stage 3, 2026-11-11) Ô nhập tên model đã chặn chuỗi không phải tên model (`isValidModelName`) sau khi API key bị
  dán nhầm vào ô model lúc thử; giao diện đầy đủ nên đặt ô key và ô model cách xa nhau, ghi rõ nhãn. → C1.
- (Từ plan Stage 4, 2026-11-11) `[VIDEO] save_extracted` (D68, trước là D47): lưu tiếng tách từ video cạnh bản chép lời
  (`<tên>.am_thanh.flac` như desktop). Pipeline Stage 4 xoá `goc.pcm` ngay sau khi cắt đoạn (D66), nên khi bật mục này
  phải mã hoá `goc.pcm` ra FLAC trong thư mục tạm trước khi xoá, hoặc giải mã lại nếu các đoạn lấy từ cache. Thử trên máy
  cần một bài video gửi Gemini thật (hỏi người dùng trước). → Thử bằng Gemini giả, không gửi thật (D120, C2, G2).
- (Từ plan Stage 4, 2026-11-11) Cài đặt Stage 4 thêm mà chưa có giao diện: `[OUTPUT]` `filename_template`, `extension`,
  `on_conflict`, `encoding`, `newline`, `add_header`, `mark_chunks`; `[VIDEO] skip_silent`; `[QUEUE] on_file_error`,
  `notify_when_done`; `[SYSTEM] delete_temp_when_done`. Stage 4 chỉ có model, cắt lặng, thư mục lưu. → C2, C4, C5.
- (Từ plan Stage 4, 2026-11-11) Cân nhắc tuỳ chọn "chỉ gửi khi có Wi-Fi" (bài 2 giờ tải lên khoảng 200 MB FLAC, số đo
  Stage 2), cùng chỗ với tuỳ chọn tiết kiệm dữ liệu bằng Opus ở trên. Hỏi người dùng trước khi làm. → Không làm (D118).
- (Từ Stage 5, 2026-11-15) Cách Kết hợp: dòng "Thời gian nói" / phần đầu có thể có mục `?` (vd `? 0.2 phút`) khi một
  người trong lượt nói không được gán từ nào sau khi làm mượt; desktop cũng vậy (`minutes_per_speaker(turns, name_map)`),
  golden khớp. Cân nhắc ẩn mục `?` khi làm lại phần hiển thị. Trang Người nói (D76) mới có các mục rút gọn: thêm ngưỡng,
  mẫu đoạn văn, prompt, `run_on`, `map_delivery`, `timestamps`, `mark_review` ở đây. → Mục `?` giữ như desktop (Không
  làm); các mục còn lại C3, G3.
