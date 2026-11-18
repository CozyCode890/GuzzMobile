# Điều khiển Guzz từ MacroDroid, Tasker, Automate

App tự động hoá gửi **lệnh** tới Guzz bằng intent (thêm tệp, bắt đầu, dừng, hỏi trạng thái) và nhận lại **sự kiện** khi
chép lời xong hoặc lỗi. Ví dụ: ghi âm xong → tự chép lời → hiện thông báo, hoặc mở bản chép lời.

Chia sẻ tệp âm thanh / video tới Guzz (nút Chia sẻ của app Ghi âm, trình quản lý tệp) không cần cài đặt gì ở trang này:
tệp được thêm vào hàng đợi và chạy ngay.

> Bản nháp Stage 6 (2026-11-18). Mục "Máy đã thử" và tên mục MacroDroid được đối chiếu lại sau đêm thử trên máy thật.

## 1. Cài đặt một lần

1. Mở Guzz → tab **Cài đặt** → **Tự động hoá**:
   - bật **Cho phép app khác điều khiển Guzz** (mặc định tắt: lệnh bị từ chối, không phát sự kiện);
   - **Mã bí mật** (tuỳ chọn): để trống thì không kiểm; có mã thì mọi lệnh phải gửi kèm extra `secret` đúng mã;
   - **Kèm đường dẫn tệp trong sự kiện** (mặc định bật): app nào trên máy cũng nghe được sự kiện; tắt thì sự kiện chỉ còn
     tên tệp và `request_id`.
2. **Quyền đọc tệp** (một trong hai):
   - bản tự cài (GitHub Releases): **Truy cập mọi tệp** → Guzz đọc được mọi đường dẫn tuyệt đối;
   - hoặc **Thư mục nguồn**: thêm thư mục chứa bản ghi (vd `Recordings`) qua nút "Thêm thư mục". Đường dẫn nằm trong
     thư mục đó đọc được mà không cần "Truy cập mọi tệp" (bản Play chỉ có cách này).
3. **Miễn tối ưu pin** cho Guzz: thiếu thì Android 12+ không cho lệnh qua broadcast khởi động hàng đợi khi Guzz ở nền.
   Trên HyperOS (Xiaomi): Cài đặt → Ứng dụng → Guzz → Tiết kiệm pin → **Không hạn chế**, và bấm nút "Bật" ở trang Tự động
   hoá (hộp thoại "Bỏ qua tối ưu hoá pin"). "Không hạn chế" của HyperOS một mình chưa đủ.
4. **Thông báo** của Guzz: thiếu thì hàng đợi vẫn chạy nhưng không có thông báo tiến độ, không có nút Dừng.
5. Chọn **thư mục lưu bản chép lời** một lần (màn hình chính → Bắt đầu, hoặc "Chọn thư mục").
6. Phía **MacroDroid**: pin "Không hạn chế", bật "Tự khởi chạy" (Autostart), quyền "Hiển thị trên ứng dụng khác"
   (cần cho cách gửi bằng Activity). Thiếu một trong ba thì lỗi nằm ở phía gửi.

## 2. Gửi lệnh

Tên action giống nhau ở mọi bản. Chọn bản bằng **Package**:

| Bản | Package |
| --- | --- |
| Bản thật | `io.github.cozycode890.guzz` |
| Bản debug | `io.github.cozycode890.guzz.debug` |

Android 8+ không giao broadcast "ngầm" cho Guzz: lệnh **phải** điền Package và Class.

| Cách gửi | Class | Khi nào dùng |
| --- | --- | --- |
| Broadcast (chính) | `io.github.cozycode890.guzz.feature.automation.CommandReceiver` | Mặc định. Cần Guzz đã được miễn tối ưu pin |
| Activity (dự phòng) | `io.github.cozycode890.guzz.feature.automation.CommandActivity` | Broadcast bị chặn (`error_kind = background_blocked`). Activity trong suốt, đóng ngay |

### Lệnh

| Action | Làm gì |
| --- | --- |
| `io.github.cozycode890.guzz.action.TRANSCRIBE` | Thêm tệp vào hàng đợi rồi bắt đầu (nếu chưa chạy) |
| `io.github.cozycode890.guzz.action.START` | Bắt đầu hàng đợi (như nút Bắt đầu) |
| `io.github.cozycode890.guzz.action.STOP` | Dừng (tệp đang chạy về "đã dừng", lần sau làm tiếp từ đoạn dở) |
| `io.github.cozycode890.guzz.action.STATUS` | Hỏi trạng thái: Guzz trả sự kiện `STATUS` |

### Extra của lệnh

Mọi extra gửi dạng **chuỗi** được (MacroDroid hay gửi chuỗi): `"3"` như `3`, `"true"` / `"on"` / `"1"` như `true`.

| Extra | Lệnh | Giá trị |
| --- | --- | --- |
| `path` | TRANSCRIBE | Đường dẫn tuyệt đối, vd `/storage/emulated/0/Recordings/bai giang.m4a` (cả `file://…`) |
| `paths` | TRANSCRIBE | Nhiều đường dẫn: mảng chuỗi, hoặc một chuỗi nhiều dòng (mỗi dòng một tệp) |
| `uri` | TRANSCRIBE | `content://…` (app gửi phải kèm cờ cấp quyền đọc; cũng nhận ở Data / ClipData của intent) |
| `request_id` | mọi lệnh | Mã tuỳ ý, Guzz trả lại trong mọi sự kiện của tệp / lệnh đó |
| `secret` | mọi lệnh | Mã bí mật, khi trang Tự động hoá có đặt |
| `start` | TRANSCRIBE | `false` = chỉ thêm, không bắt đầu (mặc định `true`) |
| `speakers` | TRANSCRIBE | Nhận diện người nói cho riêng tệp này: `off`, `device` (tách giọng trên máy + Prompt), `gemini`, `hybrid` |
| `num_speakers` | TRANSCRIBE | Số người nói, `0` = tự đoán |
| `preset` | TRANSCRIBE | Preset âm thanh: `near_lecturer`, `near_speaker`, `moving_lecturer`, `speakers_around` |
| `denoise` | TRANSCRIBE | Bật / tắt lọc dải tần + khử ồn |
| `output_dir` | TRANSCRIBE | `source` = lưu cạnh tệp nguồn, hoặc đường dẫn thư mục tuyệt đối. Chỉ khi có "Truy cập mọi tệp"; không thì lưu vào thư mục đã chọn |

Ghi đè (`speakers`, `num_speakers`, `preset`, `denoise`, `output_dir`) chỉ áp cho tệp đó, không đổi cài đặt của app.

## 3. Sự kiện Guzz phát ra

Guzz phát broadcast; app nhận đăng ký lúc chạy (MacroDroid: trigger "Nhận Intent" / Intent Received). Mọi giá trị là
chuỗi. Sự kiện nào cũng có `package` (bản phát ra). Sự kiện về tệp được phát cho mọi tệp, kể cả tệp thêm bằng tay hay
Chia sẻ (khi đó `request_id` rỗng). Không có sự kiện tiến độ: cần thì gửi `STATUS`.

| Event | Khi nào | Extra |
| --- | --- | --- |
| `io.github.cozycode890.guzz.event.FILE_STARTED` | Bắt đầu chạy một tệp | `request_id`, `name`, `source`* |
| `io.github.cozycode890.guzz.event.FILE_DONE` | Xong một tệp | `request_id`, `name`, `source`*, `output_path`* (bản chép lời là đường dẫn) hoặc `output_uri`* (thư mục SAF), `duration_sec`, `chars`, `models` (cách nhau dấu phẩy), `skipped` (`true` khi bỏ qua vì trùng tên) |
| `io.github.cozycode890.guzz.event.FILE_FAILED` | Tệp lỗi, hoặc tệp của lệnh không đọc được | `request_id`, `name`, `source`*, `error`, `error_kind` |
| `io.github.cozycode890.guzz.event.QUEUE_FINISHED` | Hàng đợi chạy xong / dừng | `done`, `failed`, `skipped`, `stopped` |
| `io.github.cozycode890.guzz.event.STATUS` | Trả lời lệnh STATUS | `request_id`, `running`, `waiting`, `done`, `failed`, `current`, `progress` (0–100), `chunk`, `chunk_total` |
| `io.github.cozycode890.guzz.event.ERROR` | Lệnh bị từ chối / không chạy được | `request_id`, `command`, `error`, `error_kind` |

`*`: không có khi tắt "Kèm đường dẫn tệp trong sự kiện".

### `error_kind`

| Mã | Nghĩa |
| --- | --- |
| `disabled` | Chưa bật "Cho phép app khác điều khiển Guzz" |
| `bad_secret` | Thiếu / sai mã bí mật |
| `bad_extra` | Extra sai (thiếu tệp, đường dẫn tương đối, giá trị lạ); `error` ghi tên extra |
| `duplicate` | Tệp đã có trong hàng đợi (chưa xong) |
| `not_ready` | Chưa có API key, chưa chọn thư mục lưu, mọi model đang khoá |
| `background_blocked` | Android chặn chạy nền: tệp vẫn trong hàng đợi, bấm thông báo "Guzz chưa chạy được…" hoặc gửi lại bằng Activity |
| `no_permission` | Guzz không được đọc tệp: cấp "Truy cập mọi tệp" hoặc thêm thư mục vào Thư mục nguồn |
| `not_found` | Không thấy tệp |

Lỗi khi chép lời (FILE_FAILED) dùng mã của hàng đợi: `cau_hinh` (key sai, cài đặt), `rang_buoc` (model không hợp cách
nhận diện người nói), `khac`, và mã lỗi của Google AI (vd hết hạn mức).

## 4. Thử bằng adb

```powershell
# Bản debug. Tệp đã adb push vào /sdcard/Android/data/io.github.cozycode890.guzz.debug/files/input/
adb shell am broadcast -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.feature.automation.CommandReceiver `
    -a io.github.cozycode890.guzz.action.TRANSCRIBE --es path /sdcard/Recordings/a.m4a --es request_id thu1
# Script của repo (bật công tắc, gửi lệnh, in sự kiện đã phát)
.\tools\device\intent_debug.ps1 -Enable on
.\tools\device\intent_debug.ps1 -Transcribe a.m4a -RequestId thu1 -Speakers off
.\tools\device\intent_debug.ps1 -Status
```

## 5. Macro MacroDroid mẫu

Tên mục theo MacroDroid bản tiếng Anh (trong ngoặc là tên tiếng Việt gần đúng). Bản debug thì Package là
`io.github.cozycode890.guzz.debug`. Không có tệp `.macro` nhập sẵn (D108): tạo theo bảng.

### 5.1 Ghi âm xong → chép lời → thông báo

| Phần | Cài đặt |
| --- | --- |
| Trigger | **File Changed** (Thay đổi tệp): thư mục `Recordings`, loại "Tệp được tạo" |
| Action 1 | **Send Intent** (nhóm Device Actions): Target **Broadcast**; Action `io.github.cozycode890.guzz.action.TRANSCRIBE`; Package `io.github.cozycode890.guzz`; Class `io.github.cozycode890.guzz.feature.automation.CommandReceiver`; Extra `path` = đường dẫn tệp (magic text của trigger), `request_id` = magic text `{system_time}` |

Macro nhận kết quả (riêng):

| Phần | Cài đặt |
| --- | --- |
| Trigger | **Intent Received**: Action `io.github.cozycode890.guzz.event.FILE_DONE`; ADD EXTRA `name`, giá trị `*`, Save to variable (biến cục bộ, nút +) |
| Action | **Display Notification**: "Đã chép lời {lv=name}" |

### 5.2 Theo dõi thư mục Recordings

Như 5.1, thêm extra ghi đè theo lớp, vd `speakers` = `device`, `num_speakers` = `3`, `preset` = `moving_lecturer`.
Thêm macro nhận `FILE_FAILED` → thông báo `error` để biết tệp nào hỏng.

### 5.3 Chép xong thì mở tệp

| Phần | Cài đặt |
| --- | --- |
| Trigger | **Intent Received**: `io.github.cozycode890.guzz.event.FILE_DONE`, extra `output_path` (và `output_uri`) giá trị `*` lưu vào biến |
| Action | **Open File** với biến `output_path`; lưu vào thư mục SAF thì dùng `output_uri` |

Cần "Kèm đường dẫn tệp trong sự kiện" đang bật.

### 5.4 Macro thử đã chạy trên Xiaomi 15T

Dùng để kiểm Guzz nhận lệnh lúc màn hình tắt. Thử xong thì tắt, vì mỗi lần chạy sinh một bản chép lời.

| Macro | Trigger | Action |
| --- | --- | --- |
| Thử broadcast | **Regular Interval** 30 phút, mốc 00:00, bật **Use alarm** | Send Intent, Target Broadcast, Class `…CommandReceiver`; extra `path`, `request_id` = `b-{system_time}`, `fake_gemini` = `on` |
| Thử activity | Regular Interval 30 phút, mốc 00:15, Use alarm | Send Intent, Target Activity, Class `…CommandActivity`; extra như trên, `request_id` = `a-{system_time}` |
| Nhận DONE | Intent Received `…event.FILE_DONE`, extra `request_id` = `*` → biến `rid` | **Write to File** (Storage Access Framework, thư mục `Documents`, Append): `{datetime} DONE {lv=rid}` + xuống dòng |
| Nhận lỗi | Hai trigger Intent Received: `…event.ERROR` và `…event.FILE_FAILED`, extra `request_id` → `rid`, `error_kind` → `kind` | Write to File `guzz_loi.txt`: `{datetime} LOI {lv=rid} {lv=kind}` |

Write to File qua SAF không chọn được gốc thư mục `Download` (Android chặn), nên dùng `Documents` hoặc một thư mục con.

## 6. Máy đã thử

| Máy | Android | Kết quả |
| --- | --- | --- |
| Máy ảo `guzz_15t_s6` (giống Xiaomi 15T) | 16 (API 36) | Lệnh qua broadcast và activity, sự kiện, Chia sẻ tới Guzz, Thư mục nguồn (bản play), bị chặn chạy nền → thông báo |
| Xiaomi 15T, HyperOS 3.0, MacroDroid | 16 | 2026-11-18, rút cáp 09:48–16:34: broadcast 14/14, activity 14/14 lệnh ra `FILE_DONE`, `request_id` khớp; 11 lần lúc màn hình tắt (có Doze) đều xong. Hai chế độ quyền tệp (Truy cập mọi tệp / Thư mục nguồn). Chia sẻ từ app Ghi âm HyperOS và Zalo |

## 7. Giới hạn đã biết

- Broadcast từ nền chỉ khởi động được hàng đợi khi Guzz đã được miễn tối ưu pin (Android 12+). Không miễn thì dùng
  Activity (MacroDroid cần quyền "Hiển thị trên ứng dụng khác").
- Tệp được chia sẻ mà Guzz không giữ được quyền đọc lâu dài thì được chép vào thư mục riêng của Guzz trước (tốn chỗ trống
  tạm thời bằng cỡ tệp), xoá khi xong. Lệnh `uri` qua broadcast cũng vậy, và Android chỉ cho receiver khoảng 10 giây:
  tệp rất lớn nên gửi bằng `path` hoặc Activity.
- Tệp trong Thư mục nguồn được đọc qua URI tài liệu: sự kiện mang URI đó ở `source`, không phải đường dẫn đã gửi.
- Gửi cùng một tệp khi tệp đó còn trong hàng đợi thì lệnh bị từ chối: sự kiện `ERROR` với `error_kind` `duplicate`.
- Zalo chia sẻ tệp với kiểu `application/<đuôi>` (vd `application/m4a`), nên "Chép lời bằng Guzz" hiện cả khi chia sẻ
  tệp `application/…` khác (pdf, docx…); Guzz xem đuôi tệp và bỏ tệp không phải âm thanh / video (D106).
- Sự kiện là broadcast mọi app đều nghe được; tắt "Kèm đường dẫn tệp trong sự kiện" nếu không muốn lộ tên thư mục.
