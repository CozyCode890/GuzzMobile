# Stage 6 — Intent cho MacroDroid

**Trạng thái:** 🔨 đang làm (2026-11-17, nhánh `stage-6`, worktree `D:\GuzzMobile-stage6`) · **Cần xong trước:** 4 (phép thử chạy nền của Stage 1 chuyển vào đây,
D42). Làm song song với Stage 8, mỗi stage một worktree; Stage 6 gộp vào `main` trước (xem "Làm song song với Stage 8").

## Mục tiêu

App tự động hoá (MacroDroid, Tasker, Automate) điều khiển được Guzz bằng intent và nhận lại kết quả. Ví dụ: ghi âm xong →
tự chép lời → thông báo hoặc mở bản chép lời.

## Phạm vi

### Làm

- **API intent** (chốt ở D91–D97, D102). Gốc tên `P` = `io.github.cozycode890.guzz` cho **mọi bản**, cả debug (D91);
  macro chọn bản debug hay bản thật bằng ô Package (`io.github.cozycode890.guzz.debug` / `io.github.cozycode890.guzz`).
  Lệnh gửi tới receiver `io.github.cozycode890.guzz.feature.automation.CommandReceiver` hoặc activity trong suốt
  `io.github.cozycode890.guzz.feature.automation.CommandActivity` (D94); cả hai nhận cùng action, cùng extra.

  | Chiều | Action | Extra |
  | --- | --- | --- |
  | gửi tới Guzz | `P.action.TRANSCRIBE` | `path` / `paths` / `uri` (hoặc data / ClipData có cờ cấp quyền); `request_id`; `secret`; `start` (mặc định `true`); ghi đè cho riêng tệp (D95): `speakers` (`off` / `device` / `gemini` / `hybrid`), `num_speakers`, `preset` (`near_lecturer` / `near_speaker` / `moving_lecturer` / `speakers_around`), `denoise`, `output_dir` (`source` hoặc đường dẫn tuyệt đối) |
  | gửi tới Guzz | `P.action.START`, `P.action.STOP` | `secret`, `request_id` |
  | gửi tới Guzz | `P.action.STATUS` | `secret`, `request_id` |
  | Guzz phát ra | `P.event.FILE_STARTED` | `request_id`, `name`, `source`* |
  | Guzz phát ra | `P.event.FILE_DONE` | `request_id`, `name`, `source`*, `output_path`*, `output_uri`*, `duration_sec`, `chars`, `models` |
  | Guzz phát ra | `P.event.FILE_FAILED` | `request_id`, `name`, `source`*, `error`, `error_kind` |
  | Guzz phát ra | `P.event.QUEUE_FINISHED` | `done`, `failed`, `skipped`, `stopped` |
  | Guzz phát ra | `P.event.STATUS` | `request_id`, `running`, `waiting`, `done`, `failed`, `current`, `progress` (0–100), `chunk`, `chunk_total` |
  | Guzz phát ra | `P.event.ERROR` | `request_id`, `command`, `error`, `error_kind` (`disabled`, `bad_secret`, `bad_extra`, `duplicate`, `not_ready`, `background_blocked`) |

  Sự kiện nào cũng có extra `package` (bản nào phát). `*`: bỏ đi khi tắt "Kèm đường dẫn trong sự kiện" (D97). Không có
  `FILE_PROGRESS` (D92): cần tiến độ thì gửi `STATUS`. Extra nào cũng nhận được dạng chuỗi (MacroDroid hay gửi chuỗi):
  `"3"` cũng như `3`, `"true"` cũng như `true`; `paths` là mảng chuỗi hoặc một chuỗi nhiều dòng.

- **Cách nhận lệnh** (D94): BroadcastReceiver exported là cách chính, activity trong suốt là lối dự phòng khi broadcast bị
  chặn khởi động service. Nhận lệnh thì thêm vào hàng đợi và khởi động foreground service; bị chặn thì giữ tệp, phát
  `ERROR` `background_blocked` và hiện thông báo "Bấm để bắt đầu" (D102).
- **Hai chế độ đọc tệp** (D6):
  - có quyền "Truy cập mọi tệp" (`MANAGE_EXTERNAL_STORAGE`, chỉ flavor `sideload`) → nhận đường dẫn tuyệt đối;
  - không có → nhận content:// URI có kèm cờ cấp quyền, hoặc tệp nằm trong một "Thư mục nguồn" đã cấp qua SAF
    (persisted URI permission; đường dẫn được đổi sang URI tài liệu, D104). Đường dẫn không đọc được thì phát
    `FILE_FAILED` kèm lý do rõ ràng.
- **Nơi lưu** (D96): như cài đặt app (thư mục SAF, D59); trang Tự động hoá có lựa chọn "Lưu cạnh tệp nguồn" (chỉ khi có
  quyền "Truy cập mọi tệp" và nguồn là đường dẫn), mặc định tắt; extra `output_dir` theo cùng quy tắc.
- **Bảo mật:** công tắc "Cho phép app khác điều khiển Guzz" (mặc định tắt); mã bí mật tuỳ chọn gửi kèm trong extra (D93);
  công tắc "Kèm đường dẫn trong sự kiện" (mặc định bật, D97); ghi log mọi lệnh nhận được.
- **Chia sẻ tới Guzz:** `ACTION_SEND` / `ACTION_SEND_MULTIPLE` cho `audio/*` và `video/*` (từ app ghi âm, trình quản lý
  tệp): thêm vào hàng đợi và bắt đầu ngay (D98), không cần công tắc cho phép. Không giữ được quyền đọc bền thì chép tệp
  vào thư mục riêng của Guzz, xong / bỏ thì xoá (D103).
- **Tài liệu:** `docs/intents.md` (bảng action / extra / sự kiện) + 3 macro MacroDroid mẫu: ghi âm xong → chép lời →
  thông báo; theo dõi thư mục Recordings; chép xong thì mở tệp. Kèm tệp `.macro` xuất từ máy thật (D101).
- **Màn hình trong app** (D99): trang Tự động hoá gọn, là một Composable ở `:feature:automation` để Stage 8 gắn vào điều
  hướng mới: công tắc, mã bí mật, các quyền (tệp, pin, thông báo), Thư mục nguồn, liệt kê action có nút sao chép, nút
  "Gửi thử sự kiện".

### Không làm

- Plugin Tasker/Locale (Stage 10).

## Nguồn tham khảo trong Guzz desktop

- `gui/main.py`: desktop đã có cách nhận tệp từ lần mở app thứ hai (thêm vào cửa sổ đang mở) — ý tưởng tương tự.
- README desktop §4: hàng đợi, các trạng thái tệp.

## Kết quả bàn giao

- MacroDroid gửi lệnh được và nhận được sự kiện, cả lúc màn hình tắt.
- `docs/intents.md` + macro mẫu xuất từ MacroDroid.

## Tiêu chí hoàn thành

- [ ] Unit test đọc extra (thiếu extra, sai kiểu, mã bí mật sai).
- [ ] Instrumented test: gửi intent → tệp vào hàng đợi → nhận sự kiện `FILE_DONE`.
- [ ] Thử thật với MacroDroid ở cả hai chế độ quyền tệp, lúc màn hình tắt (đêm thử đạt D35: ≥ 1 cách 10/10).

## Câu hỏi cần chốt khi plan chi tiết

- Tên action cuối cùng (phụ thuộc `applicationId` chốt ở Stage 0)? → D91
- Có phát sự kiện tiến độ (`FILE_PROGRESS`, thưa, vd mỗi đoạn) không? → D92
- Mã bí mật: bắt buộc hay tuỳ chọn? → D93
- Extra được phép ghi đè những cài đặt nào? → D95
- Nhận lệnh qua broadcast, activity trong suốt, hay cả hai? → D94

## Quyết định

Hỏi người dùng ngày 2026-11-17, người dùng chọn phương án Recommended ở cả 14 câu. Mã số đánh tiếp bảng ở
[README §2](README.md#2-quyết-định-đã-chốt) (D91–D109 dành cho Stage 6; Stage 8 dùng D110 trở lên).

| # | Câu hỏi | Chọn | Lý do |
| --- | --- | --- | --- |
| D91 | Tên action / sự kiện | **Gốc cố định `io.github.cozycode890.guzz.action.*` / `.event.*` cho mọi bản**; macro chọn bản bằng ô Package; sự kiện có extra `package` | Macro thử trên debug dùng lại nguyên cho bản thật. Android 8+ không giao broadcast ngầm cho receiver khai báo trong manifest, nên macro phải điền Package (tên lớp càng tốt) dù sao. |
| D92 | Sự kiện tiến độ | **Không có `FILE_PROGRESS`**; lệnh `STATUS` trả sự kiện `STATUS` có %, đoạn i/n | Ít broadcast, ít việc; macro cần tiến độ thì hỏi. |
| D93 | Mã bí mật | **Tuỳ chọn**: ô trống thì không kiểm; có mã thì lệnh thiếu / sai mã bị từ chối (`ERROR` `bad_secret`) và ghi log | Cài macro nhanh; công tắc cho phép mặc định tắt đã chặn app lạ. |
| D94 | Cách nhận lệnh | **Cả hai**: BroadcastReceiver chính, activity trong suốt dự phòng; chung code đọc extra | Broadcast cần miễn tối ưu pin (D69); activity được khởi động service vì app vào tiền cảnh nhưng cần MacroDroid có quyền "Hiển thị trên ứng dụng khác". Đêm thử chọn cách ghi trước trong tài liệu. |
| D95 | Extra ghi đè cài đặt | **Người nói** (`speakers`, `num_speakers`), **preset âm thanh** (`preset`, `denoise`), **thư mục lưu** (`output_dir`). Không ghi đè model / chế độ | Đủ cho macro theo lớp / theo phòng; ghi đè lưu theo từng tệp, không đổi cài đặt app. |
| D96 | Nơi lưu khi không có `output_dir` | **Như cài đặt app** (thư mục SAF, D59) + lựa chọn **"Lưu cạnh tệp nguồn"** ở trang Tự động hoá, mặc định tắt; chỉ khi có "Truy cập mọi tệp" và nguồn là đường dẫn, không thì lùi về SAF và ghi log | Một chỗ lưu cho mọi cách thêm tệp; ai quen desktop (`output_dir` rỗng) thì bật. |
| D97 | Đường dẫn trong sự kiện | **Có, tắt được**: mặc định có `source`, `output_path`, `output_uri`; tắt thì chỉ còn `name` và `request_id` | Macro "chép xong thì mở tệp" chạy ngay; app nào cũng nghe được sự kiện nên cho tắt. |
| D98 | Chia sẻ tới Guzz | **Thêm và bắt đầu ngay** (thiếu key / thư mục lưu thì báo như nút Bắt đầu); không cần công tắc cho phép | Người dùng tự bấm Chia sẻ nên không phải lệnh lạ. |
| D99 | Chia việc với Stage 8 | **Stage 6 làm trang Tự động hoá gọn** là Composable ở `:feature:automation` (Activity riêng như trang Âm thanh); Stage 8 gắn vào điều hướng (H1) | Thử được bằng tay ngay khi làm; ít đụng tệp chung với Stage 8. |
| D100 | Gemini thật trong đêm thử | **Một bài 1–3 phút, ≤ 3 yêu cầu**, gửi lúc máy đã ở Doze; còn lại Gemini giả. Hỏi lại ngay trước khi gửi | Trả lời câu Phát sinh F1 của Stage 4 (HyperOS có chặn mạng của FGS trong Doze không). |
| D101 | Macro mẫu | **Bảng hướng dẫn từng bước + tệp `.macro` xuất từ MacroDroid** sau đêm thử, commit vào `docs/macrodroid/` | Nhập được ngay; bảng để đọc và sửa. |
| D102 | Broadcast bị chặn khởi động service | **Giữ tệp trong hàng đợi**, phát `ERROR` `background_blocked`, hiện thông báo "Bấm để bắt đầu" | Không mất lệnh; macro gửi lại bằng activity, mở app cũng tự chạy tiếp. |
| D103 | Tệp được chia sẻ mà không giữ được quyền đọc bền | **Chép vào thư mục riêng của Guzz** (kiểm chỗ trống trước), xoá khi tệp xong hoặc bị bỏ khỏi danh sách; giữ được quyền bền thì đọc thẳng | Tự làm tiếp sau khi bị giết (D64) vẫn đọc được tệp. |
| D104 | Bản play nhận đường dẫn | **"Thư mục nguồn" cấp qua SAF** ở trang Tự động hoá; đường dẫn nằm trong một thư mục đó thì đổi sang URI tài liệu, ngoài thì `FILE_FAILED` kèm hướng dẫn | Trigger "Tệp thay đổi" của MacroDroid chỉ cho đường dẫn. Thử bằng chính bản sideload khi chưa cấp "Truy cập mọi tệp". |

**Mặc định kỹ thuật** (không hỏi, chỉ là chi tiết thực hiện; đổi thì ghi vào Nhật ký):

- Module mới `:feature:automation` (Android library, như kiến trúc ở README §3), phụ thuộc `:feature:queue`,
  `:core:pipeline`, `:core:common`. Logic không đụng Android (đọc extra từ `Map<String, Any?>`, kiểm quyền / mã bí mật,
  đổi đường dẫn → nguồn, so trạng thái hàng đợi → sự kiện) để ở lớp Kotlin thuần trong module này, test bằng JUnit trên
  JVM (`src/test`), không cần Robolectric.
- Hằng số API (action, event, extra, `error_kind`) ở một object `GuzzIntents`; tài liệu và test dùng chung bảng này.
- Cài đặt mới ở mục `[AUTOMATION]` (chỉ có trên mobile, `sameAsDesktop = false`), thêm vào **cuối** `SettingKeys` / `all`
  (Stage 8 đặt `[GUI]` sau `[SYSTEM]`): `enabled` (false), `secret` (""), `include_paths` (true), `save_beside_source`
  (false), `source_dirs` (danh sách URI cây SAF, mỗi dòng một URI). Mã bí mật không xuất ra tệp cài đặt (Stage 8 H3).
- `QueueItem` thêm `requestId: String? = null`, `overrides: ItemOverrides? = null`, `ownedCopy: Boolean = false` (tệp do
  Guzz chép, D103), `charCount: Int? = null` (cho `chars`); tệp JSON cũ vẫn đọc được. `QueueRunner` áp
  `cfg.withOverrides(item.overrides)` sau `env.loadConfig()` cho từng tệp; `output_dir`/"cạnh tệp nguồn" đi qua
  `QueueEnvironment.output(cfg, item)`.
- `speakers`: `device` = giá trị cài đặt `pyannote` (D71), nhận cả `pyannote`; `off` = tắt người nói.
- Tệp đã có trong hàng đợi (cùng nguồn) mà còn chạy được thì không thêm lần nữa: phát `ERROR` `duplicate` kèm
  `request_id`; tệp đó đã xong / bị bỏ qua thì thêm lại như desktop thêm lại tệp đã xong.
- Sự kiện phát bằng broadcast ngầm (`sendBroadcast`, không đặt package) để receiver đăng ký lúc chạy của MacroDroid /
  Tasker nhận được; chỉ phát khi công tắc cho phép bật, cho mọi tệp (cả tệp thêm bằng tay / Chia sẻ). Bộ phát nghe
  `QueueController.state` và so trạng thái cũ / mới, không gắn vào vòng chạy.
- Lệnh tới khi công tắc tắt: không làm gì, ghi log, phát `ERROR` `disabled` (giúp dò macro sai; không lộ dữ liệu).
- Activity trong suốt: theme trong suốt, `excludeFromRecents`, `noHistory`, `taskAffinity` rỗng, xử lý xong thì `finish()`
  ngay, không vẽ gì.
- Mở trang Tự động hoá: một nút trên màn hình chính như nút Âm thanh / Người nói (một dòng ở `MainActivity`); Chia sẻ tới
  Guzz là Activity riêng (`ShareActivity`) mở màn hình chính sau khi thêm, nên không sửa `MainActivity` cho phần này.
- Chuỗi giao diện của Stage 6 ở `feature/automation/src/main/res/values{,-en}/strings_automation.xml` (theo gợi ý Stage 8).
- Bản sideload, Android 11+: xin "Truy cập mọi tệp" bằng `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`; Android 8–10:
  `READ_EXTERNAL_STORAGE` (`maxSdkVersion="29"`), chỉ kiểm bằng đọc code vì không có máy thử. Miễn tối ưu pin: bản sideload
  có nút xin `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, bản play mở trang cài đặt pin (chính sách Play).
- Bản debug nhận thêm extra `fake_gemini` (`on` / `off`) cho riêng tệp, bản release bỏ qua: đêm thử dùng Gemini giả cho
  mọi lần trừ một bài gửi thật (D100) mà không cần adb giữa đêm.
- Mọi lệnh nhận được ghi log mức INFO: nguồn (receiver / activity / chia sẻ), action, `request_id`, kết quả; không ghi mã
  bí mật.

## Làm song song với Stage 8

Stage 8 làm ở worktree khác cùng lúc (xem mục cùng tên ở [stage-08-ui.md](stage-08-ui.md)). Stage 6 **gộp vào `main`
trước**; Stage 8 rebase rồi mới làm nhóm G (pipeline / hàng đợi) và H (dùng API intent).

Stage 8 dùng những phần sau của Stage 6 (giữ tên, đổi thì báo trong Nhật ký):

| Phần của Stage 6 | Stage 8 dùng ở | Ghi chú |
| --- | --- | --- |
| `AutomationSettingsScreen(...)` (Composable, `:feature:automation`) + `AutomationActivity` (`:app`) | H1: thành route trong tab Cài đặt, bỏ Activity | Composable không tự đọc `AppContainer`: nhận một `AutomationSettingsModel` (state + callback) để Stage 8 dựng lại bằng widget chung. |
| `FileAccess` (`:feature:automation`): `hasAllFilesAccess()`, `allFilesAccessIntent()`, `sourceDirs()`, `addSourceDir(uri)` | H2: mục quyền đọc tệp trong Thiết lập ban đầu | Bản play không có "Truy cập mọi tệp": `hasAllFilesAccess()` luôn `false`, thay bằng "đã có ≥ 1 Thư mục nguồn". |
| `BatteryStatus` (`isIgnoringBatteryOptimizations`, intent xin / mở trang pin theo flavor) | F1 có thể dùng lại khi gộp (F1 làm trước, tự viết) | Gộp hai bản ở H2 nếu trùng. |
| Mục `[AUTOMATION]` trong `SettingKeys` | H3: xuất / nhập, **trừ `secret`** | `source_dirs` là URI SAF: xuất ra được nhưng sang máy khác vô dụng, Stage 8 cân nhắc bỏ. |
| `ShareActivity` (Chia sẻ tới Guzz) | H4: sau khi đổi sang một Activity, mở tab Chuyển đổi | `ShareActivity` mở màn hình chính bằng intent launcher của gói, nên H4 chỉ cần `onNewIntent` không mở chồng. |
| `QueueItem.error` / `errorKind` cho `FILE_FAILED` | G1: thêm mã lỗi đa ngôn ngữ | Sự kiện giữ `error` tiếng Việt + `error_kind`; mã mới của G1 thêm vào extra `error_code` nếu cần. |
| `strings_automation.xml` (vi + en) | H1: hoàn thiện văn phong | |

Tệp Stage 6 sửa ngoài module mới (để Stage 8 biết chỗ xung đột): `settings.gradle.kts` (một dòng include),
`app/build.gradle.kts` (một dòng phụ thuộc), `app/src/main/AndroidManifest.xml` (Activity trang Tự động hoá),
`app/src/sideload/AndroidManifest.xml` (`READ_EXTERNAL_STORAGE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), `AppContainer.kt`
(vài dòng `lazy` cuối class), `MainActivity.kt` (một nút mở trang), `SettingKeys.kt` (khối `[AUTOMATION]` cuối tệp),
`Queue.kt` / `QueueItem`, `PipelineConfig.kt` (`withOverrides`), `QueueController.kt` (thêm tệp kèm `requestId` /
`overrides`, chép tệp, dọn bản chép), `AppContainer` / `app/src/debug/...` (Gemini giả theo tệp), `CLAUDE.md` (khối
lệnh "Intent (Stage 6)" ở cuối "Lệnh thường dùng"). Thông báo "Bấm để bắt đầu" (D102) nằm ở `:feature:automation`, không
sửa `QueueService`.

## Rủi ro

- Hạn chế chạy nền của Android (README rủi ro 3): Stage 1 chỉ thử lúc màn hình bật, phần thử lúc màn hình tắt làm ở
  stage này (D42). Mỗi bản Android và mỗi hãng một khác → ghi rõ trong tài liệu những máy đã thử.
- Broadcast ngầm (implicit) gửi ra ngoài: chỉ receiver đăng ký lúc chạy mới nhận được (MacroDroid đăng ký kiểu này) →
  kiểm tra thật.
- MacroDroid có thể gửi mọi extra dạng chuỗi, hoặc không gửi được mảng chuỗi → bộ đọc extra nhận cả hai dạng (B1); đêm
  thử ghi lại MacroDroid gửi kiểu gì.
- Quyền đọc content:// của tệp chia sẻ có thể hết ngay khi `ShareActivity` đóng → chép / giữ quyền **trước** `finish()`
  (D103), có test thiết bị với provider thật.
- Hai worktree cùng sửa `Queue.kt`, `QueueController.kt`, `AppContainer.kt`, `SettingKeys.kt` với Stage 8 → giữ phần sửa
  nhỏ, logic mới ở lớp riêng; gộp vào `main` sớm (Stage 8 chờ G, H).

## Kế hoạch chi tiết

Mỗi bước có dòng **Kiểm:** là cách chứng minh bước đó xong. Thử trên máy ảo `guzz_15t` trước (D70): lệnh bằng
`adb shell am broadcast` / `am start` qua script `tools/device/intent_debug.ps1` (C4), đọc giao diện bằng `android layout`.
Điện thoại thật và MacroDroid chỉ ở nhóm F. Làm trong worktree riêng, nhánh `stage-06`; xong thì gộp vào `main` trước
Stage 8.

Thứ tự nhóm để "vừa làm vừa thử": A (nền, test JVM) → B (nhận lệnh, thử bằng adb) → C (sự kiện) → D (Chia sẻ) → E (trang
Tự động hoá) → F (máy thật, MacroDroid, đêm thử) → G (tài liệu, đóng). Sau B là đã thử được `TRANSCRIBE` trên máy ảo.

### A. Nền: module, cài đặt, hàng đợi mang `request_id` và ghi đè

- [x] **A1. Module `:feature:automation`** (convention plugin Android library), `:app` phụ thuộc; object `GuzzIntents`
  (gốc tên D91, action, event, tên extra, `error_kind`).
  **Kiểm:** `.\gradlew assembleSideloadDebug assemblePlayDebug` xanh; test JVM: mọi tên trong `GuzzIntents` bắt đầu bằng
  `io.github.cozycode890.guzz.` và không trùng nhau.
- [x] **A2. Cài đặt `[AUTOMATION]`**: `enabled`, `secret`, `include_paths`, `save_beside_source`, `source_dirs` (mục "Mặc
  định kỹ thuật"), cuối `SettingKeys`.
  **Kiểm:** `DesktopDefaultsTest` vẫn xanh (mục mobile-only được bỏ qua đúng); test đọc / ghi từng khoá với giá trị mặc định.
- [x] **A3. `QueueItem`** thêm `requestId`, `overrides`, `ownedCopy`, `charCount`; `QueueRunner` ghi `charCount` từ kết quả
  pipeline khi xong.
  **Kiểm:** test `:core:pipeline`: tệp `hang_doi.json` cũ (không có trường mới) đọc được; ghi rồi đọc lại giữ nguyên trường
  mới; tệp xong có `charCount` = độ dài bản chép lời của Gemini giả.
- [x] **A4. `ItemOverrides` + `PipelineConfig.withOverrides`** (D95): `speakers` (`off` → tắt; `device` / `pyannote` /
  `gemini` / `hybrid`), `numSpeakers`, `preset` (áp `AudioPresets` lên phần làm sạch của cấu hình), `denoise`, `outputDir`;
  `QueueRunner` áp cho từng tệp. Không đụng cài đặt đã lưu.
  **Kiểm:** test `:core:pipeline`: mỗi ghi đè đổi đúng trường, trường khác giữ nguyên; hai tệp liền nhau, một có ghi đè một
  không, tệp sau dùng cài đặt app; đổi preset bằng ghi đè thì mã cache âm thanh đổi (như A3 của Stage 7).
- [x] **A5. Nơi lưu** (D96): `QueueEnvironment.output(cfg, item)` chọn theo thứ tự `outputDir` ghi đè → "cạnh tệp nguồn"
  (cài đặt `save_beside_source`) → thư mục SAF. "Cạnh tệp nguồn" / đường dẫn tuyệt đối chỉ dùng khi có "Truy cập mọi tệp"
  và nguồn là đường dẫn, không thì lùi về SAF và ghi log. Quy tắc chọn là hàm thuần (`OutputChoice`), `QueueController`
  chỉ dựng `DirectoryOutputFolder` / `SafOutputFolder`.
  **Kiểm:** test JVM cho bảng tình huống (có / không quyền × nguồn đường dẫn / URI × có / không `output_dir` × bật / tắt
  "cạnh tệp nguồn"); máy ảo ở B5.

### B. Nhận lệnh

- [x] **B1. `ExtrasReader`** (Kotlin thuần, đọc `Map<String, Any?>`): chuỗi / số / boolean nhận cả dạng chuỗi; `paths` là
  mảng, `ArrayList` hoặc chuỗi nhiều dòng; giá trị lạ (`speakers=abc`, `num_speakers=-1`, `preset` lạ, `start=maybe`) báo
  lỗi có tên extra.
  **Kiểm:** test JVM: thiếu extra, sai kiểu, chuỗi số, mảng rỗng, khoảng trắng thừa, `paths` nhiều dòng có dòng trống.
- [x] **B2. `CommandHandler`** (thuần, nhận interface hàng đợi / quyền / cài đặt): kiểm công tắc (`disabled`), mã bí mật
  (D93: so sánh thời gian hằng, ô trống thì bỏ qua), đọc lệnh, trả danh sách việc + sự kiện trả lời (`ERROR`, `STATUS`,
  `FILE_FAILED` cho đường dẫn không đọc được, `duplicate`).
  **Kiểm:** test JVM: công tắc tắt; mã sai / thiếu / đúng / ô trống; TRANSCRIBE `start=false` chỉ thêm; STATUS trả số đúng
  với trạng thái giả; tệp trùng; log không chứa mã bí mật.
- [x] **B3. Đổi nguồn** (`SourceResolver`, thuần trừ lớp gọi Android): đường dẫn + "Truy cập mọi tệp" → đường dẫn; đường
  dẫn nằm trong một Thư mục nguồn SAF (D104) → URI tài liệu (`DocumentsContract.buildDocumentUriUsingTree`, so tiền tố
  `primary:` của URI cây với `/storage/emulated/0/`); `uri` / ClipData có cờ cấp quyền → giữ quyền bền nếu được, không thì
  chép (D103, dùng chung D2); không được thì `FILE_FAILED` `error_kind` `no_permission` / `not_found` kèm hướng dẫn.
  **Kiểm:** test JVM cho phép đổi đường dẫn ↔ URI cây (thư mục con, tên có dấu, khoảng trắng, đường dẫn ngoài thư mục, thẻ
  nhớ ngoài không phải `primary`).
- [x] **B4. `CommandReceiver` + `CommandActivity`** (D94), manifest exported, cùng gọi `CommandHandler` qua `goAsync()` /
  coroutine; thêm tệp qua `QueueController` (thêm `addPath` / `addUris` có `requestId`, `overrides`), rồi `start()` nếu
  `start=true`. Receiver bắt `ForegroundServiceStartNotAllowedException` → D102: tệp giữ trong hàng đợi, phát `ERROR`
  `background_blocked`, thông báo "Bấm để bắt đầu" (mở màn hình chính, `resumeIfNeeded`). Lệnh tới mà hàng đợi bị giết dở
  (Phát sinh F2 của Stage 4) cũng gọi `resumeIfNeeded()`. Thiếu quyền thông báo thì ghi log cảnh báo.
  **Kiểm:** máy ảo, app đóng, `adb shell am broadcast -n .../CommandReceiver -a io.github.cozycode890.guzz.action.TRANSCRIBE
  --es path ... --es request_id t1` với Gemini giả → tệp vào hàng đợi và chạy; `am start -n .../CommandActivity` cùng extra
  → chạy, không thấy cửa sổ nào (`android layout` ngay sau đó chỉ thấy app đang mở trước). Gỡ miễn tối ưu pin
  (`dumpsys deviceidle whitelist -io.github.cozycode890.guzz.debug`) rồi gửi broadcast lúc app ở nền → log
  `background_blocked`, có thông báo, bấm thông báo thì chạy; activity vẫn chạy được.
- [x] **B5. Thử ghi đè và nơi lưu trên máy ảo**: `speakers=off`, `preset=speakers_around`, `output_dir=source` (có / không
  quyền "Truy cập mọi tệp": `appops set ... MANAGE_EXTERNAL_STORAGE allow|ignore`).
  **Kiểm:** log từng tệp ghi cấu hình đã áp; bản chép lời nằm cạnh tệp nguồn khi có quyền, nằm ở thư mục SAF khi không; cài
  đặt app không đổi (`queue_debug.ps1 -Status`).
- [x] **B6. Gemini giả theo tệp (chỉ debug)**: extra `fake_gemini=on|off` lưu vào ghi đè của tệp; `clientFactory` của bản
  debug ưu tiên giá trị này hơn cờ `gemini_gia`; bản release bỏ qua extra.
  **Kiểm:** máy ảo: cờ chung bật, một tệp `fake_gemini=off` → log thấy client thật được dựng cho tệp đó (không có key thì
  lỗi `NoApiKey` cho đúng tệp đó; **không gửi gì**); test JVM: release bỏ qua.

### C. Sự kiện

- [x] **C1. `EventTracker`** (thuần): so `QueueState` cũ / mới → `FILE_STARTED` (vào `dang_chay`), `FILE_DONE`, `FILE_FAILED`
  (`loi`), `QUEUE_FINISHED` (cờ chạy tắt sau một vòng, kèm số xong / lỗi / bỏ qua, `stopped`); bỏ đường dẫn khi tắt
  `include_paths` (D97). Không phát lại sự kiện cũ khi process mới khởi động (trạng thái đầu là mốc).
  **Kiểm:** test JVM: chuỗi trạng thái của một vòng 3 tệp (xong, lỗi, bỏ qua); Dừng giữa chừng; process mới đọc hàng đợi đã
  xong thì không phát gì; tắt `include_paths` thì không còn `source` / `output_path` / `output_uri`.
- [x] **C2. `EventBroadcaster`**: dựng `Intent` từ sự kiện (extra `package`), `sendBroadcast` ngầm; chạy trong
  `AppContainer` nghe `QueueController.state` khi công tắc bật; trả lời `STATUS` / `ERROR` của B2 cũng qua đây. Log mỗi
  sự kiện phát (action, `request_id`).
  **Kiểm:** máy ảo: `logcat -s Guzz` thấy `FILE_STARTED` → `FILE_DONE` → `QUEUE_FINISHED` đúng `request_id`.
- [x] **C3. Bộ ghi sự kiện của bản debug**: receiver đăng ký lúc chạy trong `GuzzApp` (debug) cho mọi `P.event.*`, ghi
  `files/debug-events/events.jsonl`.
  **Kiểm:** sau B4, kéo tệp về thấy đủ sự kiện, `request_id` khớp lệnh.
- [x] **C4. `tools/device/intent_debug.ps1`**: `-Transcribe <tệp> [-RequestId] [-Secret] [-Activity] [-Speakers] [-Preset]
  [-OutputDir] [-FakeGemini on|off]`, `-Start`, `-Stop`, `-Status`, `-Events` (in `events.jsonl`), `-Enable on|off` (bật
  công tắc qua lệnh `automation` mới của `QueueDebugReceiver`). Tệp tương đối thì lấy trong `files/input/` như `queue_debug.ps1`.
  **Kiểm:** chạy lại B4 bằng script; `-Status` in sự kiện STATUS có % và đoạn i/n lúc đang chạy.
- [x] **C5. Test thiết bị** (`:app`, `androidTest`): bật công tắc + Gemini giả, gửi broadcast TRANSCRIBE tới
  `CommandReceiver` với một tệp ngắn trong `testdata/media`, receiver đăng ký trong test chờ `FILE_DONE` cùng
  `request_id` (≤ 60 s); ca mã bí mật sai → `ERROR` `bad_secret`, hàng đợi không đổi.
  **Kiểm:** chạy trên máy ảo theo cách cài tay ở `CLAUDE.md` (tiêu chí hoàn thành "Instrumented test").

### D. Chia sẻ tới Guzz

- [x] **D1. `ShareActivity`** (trong suốt, intent-filter `SEND` / `SEND_MULTIPLE` cho `audio/*`, `video/*`): đọc
  `EXTRA_STREAM`, thêm vào hàng đợi, `checkStart()` → bắt đầu (D98) hoặc báo lý do (thiếu key / thư mục lưu) bằng toast +
  mở màn hình chính; luôn mở màn hình chính sau cùng. Không cần công tắc cho phép.
  **Kiểm:** máy ảo: app Files → Chia sẻ một tệp `.m4a` tới "Guzz (debug)" (bấm bằng `android layout` / `android input
  tap`) → tệp chạy ngay; chia sẻ 2 tệp cùng lúc → cả hai vào hàng đợi; `adb shell am start -a android.intent.action.SEND
  -t audio/mp4 --eu android.intent.extra.STREAM content://media/...` cũng chạy.
- [x] **D2. Chép khi không giữ được quyền** (D103): thử `takePersistableUriPermission`; không được thì kiểm chỗ trống (D44)
  rồi chép vào `files/inbox/<id>/<tên>` trong luồng nền **trước** khi Activity đóng (giữ Activity tới khi chép xong, có
  vòng chờ); `ownedCopy = true`; xoá khi tệp xong (theo `delete_temp_when_done`) hoặc bị bỏ khỏi danh sách.
  **Kiểm:** máy ảo: chia sẻ từ app Files rồi `am kill` app giữa chừng, mở app → tệp làm tiếp, đọc được; xong thì
  `files/inbox` trống; bỏ tệp lỗi khỏi danh sách thì bản chép bị xoá. Test JVM cho quy tắc xoá.
- [x] **D3. Test thiết bị đọc qua ContentProvider thật** (Phát sinh Stage 2): `MediaInput.Content` / `AndroidChunkSource`
  đọc tệp qua một `FileProvider` của test trả độ dài không rõ (−1), và qua MediaStore Downloads.
  **Kiểm:** `:core:media:connectedDebugAndroidTest` (hoặc `androidTest` của `:app`) xanh trên máy ảo.

### E. Trang Tự động hoá (D99)

- [x] **E1. `FileAccess`, `BatteryStatus`**: kiểm / xin "Truy cập mọi tệp" (sideload; Android 8–10 `READ_EXTERNAL_STORAGE`),
  Thư mục nguồn (thêm qua `OpenDocumentTree` + `takePersistableUriPermission`, bỏ thì trả quyền), miễn tối ưu pin (sideload
  có nút xin; play mở trang cài đặt pin), quyền thông báo.
  **Kiểm:** máy ảo: `appops set ... MANAGE_EXTERNAL_STORAGE ignore|allow` và `dumpsys deviceidle whitelist ±` → trạng thái
  trên trang đổi theo khi quay lại trang (`onResume`).
- [x] **E2. `AutomationSettingsScreen`** (Composable nhận `AutomationSettingsModel`) + `AutomationActivity` ở `:app`, nút mở
  trên màn hình chính: công tắc cho phép; mã bí mật (ô + nút "Tạo mã ngẫu nhiên", ẩn / hiện); "Kèm đường dẫn trong sự
  kiện"; "Lưu cạnh tệp nguồn" (ẩn ở bản play); quyền tệp / Thư mục nguồn; pin; thông báo; danh sách action / event / tên
  lớp receiver, activity, mỗi dòng nút sao chép; nút "Gửi thử sự kiện" (phát `FILE_DONE` giả có `request_id` = `test`,
  `name` = `thu.m4a`). Chuỗi ở `strings_automation.xml` vi + en.
  **Kiểm:** máy ảo, đọc bằng `android layout`: đủ mục ở cả bản sideload và play (`assemblePlayDebug` cài thử); bấm sao chép
  thì clipboard có tên đúng; "Gửi thử sự kiện" → `events.jsonl` (C3) có dòng mới; lint `HardcodedText` sạch ở module mới.

### F. Máy thật, MacroDroid (Xiaomi 15T)

Trước nhóm này: người dùng cài bản debug mới, MacroDroid đã có pin "No restrictions", Autostart, quyền "Hiển thị trên ứng
dụng khác" (Phát sinh Stage 1); Guzz đã miễn tối ưu pin (D69) và có quyền thông báo.

- [ ] **F1. Macro theo `docs/intents.md` (bản nháp từ G1)**: tạo 3 macro mẫu + 3 macro thử: "Thử broadcast" / "Thử activity"
  (Regular Interval 30 phút, **dùng báo thức**, lệch nhau 15 phút; TRANSCRIBE một tệp 1 phút có sẵn trong Recordings,
  `request_id` = giờ hệ thống, `fake_gemini=on`), "Nhận DONE" (Intent Received `P.event.FILE_DONE` → ghi một dòng giờ +
  `request_id` vào tệp). Ghi lại MacroDroid gửi extra kiểu gì.
  **Kiểm:** màn hình bật, bấm chạy tay từng macro: tệp vào hàng đợi, "Nhận DONE" ghi đúng `request_id`; macro mẫu "chép xong
  thì mở tệp" mở được bản chép lời.
- [ ] **F2. Hai chế độ quyền tệp**: (a) có "Truy cập mọi tệp": macro mẫu "theo dõi Recordings" gửi đường dẫn; (b) thu hồi quyền
  đó, thêm Recordings làm Thư mục nguồn (D104): cùng macro vẫn chạy; tệp ngoài Thư mục nguồn → MacroDroid nhận
  `FILE_FAILED` `no_permission`.
  **Kiểm:** cả hai chế độ ra bản chép lời (Gemini giả); log ghi nguồn là đường dẫn ở (a), URI tài liệu ở (b).
- [ ] **F3. Đêm thử** (D35, D42): máy đã cài đặt, rút cáp, `adb disconnect`, vuốt Guzz khỏi đa nhiệm, màn hình tắt cả đêm.
  "Thử broadcast" và "Thử activity" mỗi cách ≥ 10 lần. Một lần trong đêm (macro một lần lúc ~03:00) gửi **một bài 1–3
  phút thật** (`fake_gemini=off`, ≤ 3 yêu cầu, D100) — **hỏi người dùng trước khi bật macro này**.
  **Kiểm:** sáng hôm sau đếm: lần gửi (nhật ký MacroDroid) / lần nhận (log Guzz) / lần FGS chạy xong / dòng "Nhận DONE";
  log nhịp ghi "Doze có" ở các lần chạy. **Đạt D35** khi ≥ 1 cách 10/10; sự kiện: "Nhận DONE" ≥ 3 dòng lúc màn hình tắt,
  `request_id` khớp; bài thật có bản chép lời và log thấy gửi trong Doze (trả lời Phát sinh F1 của Stage 4). Bảng số ghi
  vào Nhật ký. Không đạt thì ghi lý do và hỏi người dùng trước khi đổi hướng.
- [ ] **F4. Chia sẻ tới Guzz trên máy thật**: chia sẻ một bản ghi từ app Ghi âm của HyperOS và từ trình quản lý tệp.
  **Kiểm:** chạy ngay; app Ghi âm nào không cho giữ quyền bền thì log thấy chép vào `files/inbox` và tệp được xoá khi xong.
- [ ] **F5. Xuất macro** (D101): người dùng xuất 3 macro mẫu (bỏ macro thử) ra `.macro`, Claude `adb pull` về
  `docs/macrodroid/`, kiểm không chứa mã bí mật thật (thay bằng `DOI_MA_NAY` nếu có).
  **Kiểm:** nhập lại một tệp `.macro` vào MacroDroid chạy được; `git grep` không thấy mã bí mật đã dùng.

### G. Tài liệu và đóng stage

- [ ] **G1. `docs/intents.md`**: cài đặt một lần (bật công tắc, pin, thông báo, quyền tệp / Thư mục nguồn, quyền của
  MacroDroid), bảng action / extra / event / `error_kind` (sinh từ `GuzzIntents` hoặc có test so khớp), ví dụ `adb`, 3 macro
  mẫu dạng bảng, máy đã thử (Android / hãng / kết quả đêm thử), giới hạn đã biết. Viết nháp trước F1, sửa theo F1–F5.
  **Kiểm:** test JVM: mọi action / event / extra trong `GuzzIntents` có mặt trong `docs/intents.md` và ngược lại.
- [ ] **G2. `CLAUDE.md`**: khối lệnh "Intent (Stage 6)" cuối "Lệnh thường dùng" (`intent_debug.ps1`, test thiết bị C5, test
  `:feature:automation`).
  **Kiểm:** chạy lại từng lệnh trong khối trên máy ảo.
- [ ] **G3. Gộp và đóng**: `.\gradlew test lint` xanh cả hai flavor; đánh dấu "Tiêu chí hoàn thành"; Stage 6 ✅ kèm ngày;
  gộp nhánh `stage-06` vào `main` (hỏi người dùng trước khi gộp / push), báo session Stage 8 để rebase.
  **Kiểm:** `git log main` có commit gộp; `.\gradlew test` trên `main` xanh.

## Nhật ký

- (2026-11-17, A2) `secret` và `source_dirs` không nằm trong `SettingKeys.all` mà trong danh sách mới `SettingKeys.deviceLocal` (mục của máy này, không xuất / nhập). Stage 8 H3 chỉ cần xuất `all`.
- (2026-11-17, A3) `QueueItem` thêm cả `modelsUsed` (cho extra `models` của FILE_DONE). `QueueEnvironment.cacheSalt` nhận cấu hình của tệp (`cacheSalt(cfg)`), vì Gemini giả có thể bật cho riêng một tệp (B6); `PipelineConfig` thêm `debugFakeGemini` và `saveBesideSource`. Ghi đè không hợp lệ chỉ làm tệp đó lỗi `cau_hinh`, hàng đợi chạy tiếp.
- (2026-11-17, A5) `OutputChoice` nằm ở `:core:pipeline` (JVM thuần, test JUnit 5). Thư mục cha lấy bằng cắt chuỗi, không dùng `File.parent` (test trên Windows ra dấu gạch ngược).
- (2026-11-17, B2–B3) Không tách lớp `SourceResolver`: phần thuần là `PathMapping` (đường dẫn ↔ mã tài liệu), phần Android nằm trong `Automation.resolvePath/resolveUri`. Thêm: tệp trong thư mục riêng của app (`files/input` của lệnh adb) đọc thẳng không cần quyền; URI của lệnh TRANSCRIBE không giữ được quyền bền thì cũng chép như tệp chia sẻ (D103), trừ URI tài liệu dưới một cây SAF. Tệp bỏ qua (on_conflict = skip) phát FILE_DONE với `skipped=true`.
- (2026-11-17, E2) Trang là `AutomationSettingsScreen(state: AutomationUiState, actions: AutomationActions)`; dữ liệu ở `AutomationSettingsModel` (cài đặt + trạng thái quyền, `refresh()` ở `onResume`). Stage 8 H1 dựng lại bằng widget chung thì dùng `AutomationSettingsModel`.
- (2026-11-17) Máy ảo `guzz_15t` (emulator-5554) đang được session Stage 8 dùng, nên Stage 6 thử trên AVD riêng `guzz_15t_s6` (cùng thông số, cổng 5558) để không cài đè app của nhau.

- (2026-11-18, B4) Trên máy ảo API 36, app không đọc được tệp qua `/sdcard/…` dù cùng chỗ với `/storage/emulated/0/…`: tệp trong thư mục riêng của app và tệp khi có "Truy cập mọi tệp" được đổi sang đường dẫn thật. Bị chặn chạy nền (D102) thì ngoài thông báo còn bật cờ đang chạy của hàng đợi (`QueueController.markPendingStart`), nên mở app / bấm thông báo là tự bắt đầu; đã thử: gỡ miễn tối ưu pin → ERROR background_blocked + thông báo, mở app → FILE_STARTED → FILE_DONE.
- (2026-11-18, C3) Bộ ghi `events.jsonl` của bản debug ghi ngay lúc phát, không qua receiver đăng ký trong chính process: Android 14+ hoãn broadcast tới process cached (vừa dừng service) nên receiver đó bỏ sót FILE_DONE / QUEUE_FINISHED. Việc broadcast tới được app khác do test thiết bị C5 kiểm.
- (2026-11-18, B5–B6) Máy ảo `guzz_15t_s6`: `output_dir=source` không có quyền → `files/output` + log cảnh báo, có quyền (`appops … MANAGE_EXTERNAL_STORAGE allow`) → `B5test.txt` cạnh tệp nguồn; `speakers=off`, `preset=speakers_around` ghi trong log tệp; `fake_gemini=off` khi cờ chung bật → client thật, lỗi "Chưa có API key" (máy ảo không có key, không gửi gì). Tệp tạo bằng `adb shell cp` app không đọc được (chủ là shell): đẩy từ PC bằng `adb push`.

- (2026-11-18, D1) `querySource` (:feature:queue) hỏi cột `last_modified` nên MediaStore ném IllegalArgumentException ("Invalid column"): đổi sang projection `null`, chỉ đọc cột có mặt. `ShareActivity` nhận URI ở cả ClipData lẫn `data` (`am start --grant-read-uri-permission` chỉ cấp quyền cho data / ClipData, không cho extra STREAM). Đã thử: `am start -a SEND -d content://media/…`, chia sẻ hai tệp từ app Files của máy ảo (SEND_MULTIPLE), giết app giữa chừng rồi mở lại → làm tiếp từ bản chép, xong thì `files/inbox` trống.
- (2026-11-18, D3) Test ở `:app` (`ContentSourceTest`), không ở `:core:media`, vì cần cả `querySource` của :feature:queue. Provider trả độ dài không rõ là `UnknownLengthProvider` chỉ có ở bản debug (không exported).

- (2026-11-18, E) Máy ảo: trang hiện đủ mục ở bản sideload; bản play ẩn "Truy cập mọi tệp" và "Lưu cạnh tệp nguồn". `appops` / `deviceidle` đổi thì quay lại trang thấy ✓ / ✗ đổi theo; bấm dòng thì có toast "Đã sao chép …" (shell không đọc được clipboard nên kiểm bằng ảnh chụp); "Gửi thử sự kiện" ghi FILE_DONE `request_id=test`. Thư mục nguồn (D104) thử luôn trên bản play: chọn Music qua SAF, `/sdcard/Music/chia_se_2.m4a` đọc qua URI tài liệu (sự kiện mang URI đó ở `source`, không phải đường dẫn gốc), `/sdcard/Download/…` → FILE_FAILED `no_permission`.
- (2026-11-18, E2) Lint: `URLDecoder.decode(String, Charset)` cần API 33, đổi sang bản nhận tên bảng mã. Còn 6 cảnh báo (KTX `toUri`, plurals cho `share_added`, `/sdcard` trong PathMapping) để Stage 8 xét cùng phần văn phong.

## Phát sinh

- (Từ Stage 4, 2026-11-12, D69) Android 12+ không cho receiver ở nền khởi động foreground service khi app chưa được
  miễn tối ưu pin; trên Xiaomi 15T, "No restrictions" của HyperOS không đưa app vào `deviceidle whitelist`
  (`ForegroundServiceStartNotAllowedException`). Hướng dẫn cài đặt cho MacroDroid phải có bước "Tối ưu pin → Không tối
  ưu" (hoặc nút xin `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ở bản sideload), và receiver nên báo lỗi rõ khi bị chặn.

- (Từ Stage 4, 2026-11-14, F1) F1 chạy lúc máy cắm sạc nên không vào Doze: chưa biết HyperOS có chặn mạng của FGS
  trong Doze không. Đêm thử MacroDroid (rút cáp, Doze) chạy kèm một bài gửi thật ngắn, xem nhịp log ghi "Doze có" mà
  vẫn gửi được.
- (Từ Stage 4, 2026-11-14, F2) Trên Xiaomi 15T: (1) `kill -9` service lúc màn hình tắt thì HyperOS **không** khởi động
  lại (`START_STICKY` không có tác dụng), hàng đợi chỉ làm tiếp khi mở app (D64); lệnh TRANSCRIBE tới sau đó cũng nên gọi
  `resumeIfNeeded()`. (2) App chưa có quyền thông báo (cài qua adb, hoặc người dùng chưa bấm Bắt đầu lần nào) thì service
  vẫn chạy nhưng không có thông báo, không có nút Dừng: hướng dẫn cài đặt cho MacroDroid cần bước cấp quyền thông báo, và
  receiver nên ghi log khi thiếu quyền.

- (Từ Stage 1, 2026-11-10) Phép thử MacroDroid của Stage 1 chuyển sang đây, rút gọn (D42). Thử trên receiver thật của
  Guzz, hoặc trên app spike (`spikes/android`) nếu muốn biết trước khi viết code:
  - tạo macro theo `spikes/README.md`, mục "Macro MacroDroid" (đổi tên package / class sang của Guzz);
  - **chỉ một đêm**, máy đã cài đặt (pin "No restrictions", Autostart, miễn tối ưu pin), rút cáp, app bị vuốt khỏi đa
    nhiệm: mỗi cách (broadcast, activity trong suốt, WorkManager) ≥ 10 lần với màn hình tắt và Doze. Đạt D35 khi ít nhất
    một cách được 10/10. Bỏ đêm "chưa cài gì";
  - sự kiện gửi ngược về MacroDroid: macro bắt được DONE (ở đây là `FILE_DONE`) cho ≥ 3 lần chạy lúc màn hình tắt,
    `request_id` khớp.
- (Từ Stage 1, 2026-11-10) Điều đã biết:
  - bằng adb lúc màn hình bật, cả ba cách đều lên FGS (app spike, G1);
  - cài qua adb xong thì app đã được miễn tối ưu pin dù chưa bấm xin → muốn thử trạng thái chưa miễn thì gỡ bằng
    `adb shell dumpsys deviceidle whitelist -<package>`;
  - MacroDroid cũng phải được đặt pin "No restrictions", Autostart và quyền "Display over other apps", thiếu thì lỗi
    nằm ở phía gửi;
  - adb qua Tailscale: app VPN chạy trên máy có thể ảnh hưởng Doze và pin; đêm thử thì `adb disconnect`.
- (Từ Stage 2, 2026-11-11) `MediaInput.Content` từng hỏng với URI của trình chọn tệp vì provider trả độ dài −1 (đã sửa,
  xem Nhật ký Stage 2, D4). Test thiết bị của `:core:media` chỉ thử đường dẫn: khi nhận content:// từ app khác (Chia sẻ
  tới Guzz, intent có URI), thêm test thiết bị đọc qua một ContentProvider thật (vd MediaStore Downloads hoặc FileProvider
  trả độ dài không rõ).
- (Từ plan Stage 4, 2026-11-11) Stage 4 lưu bản chép lời vào thư mục chọn qua SAF (D59). **Lưu cạnh tệp nguồn** (như
  `output_dir` rỗng của desktop) làm ở đây: chỉ khi có quyền "Truy cập mọi tệp" và nguồn là đường dẫn tuyệt đối; nguồn
  content:// thì lùi về thư mục SAF. Extra `output_dir` của lệnh TRANSCRIBE cũng theo quy tắc này.
- (Từ plan Stage 4, 2026-11-11) Cửa vào hàng đợi là `QueueController` của `:feature:queue` (thêm, Bắt đầu, Dừng, trạng
  thái). Bản debug của Stage 4 có receiver adb + `tools/device/queue_debug.ps1` để thử hàng đợi lúc tắt màn hình: receiver
  thật của stage này thay thế hoặc dùng lại phần đọc extra của nó.
