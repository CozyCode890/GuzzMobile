# Server xử lý âm thanh (Modal)

Từ bản 1.1.0 (Stage 11), Guzz có thể gửi phần nặng nhất (làm sạch âm thanh, cắt đoạn, tách giọng) lên một server riêng
của bạn trên [Modal](https://modal.com). Điện thoại chỉ tách luồng âm thanh, tải lên, nhận về các đoạn FLAC và lượt nói,
rồi tự gửi Google AI Studio như cũ. Server lỗi thì điện thoại tự làm trên máy.

## Cài đặt (một lần)

Cần: tài khoản Modal (gói Starter: $30 tín dụng / tháng, không cần thẻ), Python 3.12, JDK 21, mã nguồn GuzzMobile.

1. Cài CLI và đăng nhập (mở trình duyệt):

   ```powershell
   uv venv D:\GuzzMobileData\tools\modal-venv --python 3.12
   uv pip install --python D:\GuzzMobileData\tools\modal-venv\Scripts\python.exe modal
   D:\GuzzMobileData\tools\modal-venv\Scripts\modal.exe token new
   ```

2. Build jar rồi deploy. Model embedding lấy từ cache guzz-deps của máy (D149), nên phải build Gradle ít nhất một lần
   trước đó:

   ```powershell
   .\gradlew :server:serverJar
   D:\GuzzMobileData\tools\modal-venv\Scripts\modal.exe deploy server\modal_app.py
   ```

   Lệnh in ra URL dạng `https://<workspace>--guzz-server-web.<vùng>.modal.run`.

3. Tạo proxy token (chỉ hiện secret một lần, lưu ngoài repo):

   ```powershell
   D:\GuzzMobileData\tools\modal-venv\Scripts\modal.exe workspace proxy-tokens create --name guzz-mobile --json
   ```

4. Trong app: Cài đặt → Server xử lý: bật công tắc, dán URL, dán token dạng `<Modal-Key>.<Modal-Secret>`, bấm Kiểm tra
   kết nối.

Thử server bằng tệp thử tổng hợp (so với jar chạy trên PC):

```powershell
python -X utf8 tools\server\smoke.py --url https://<workspace>--guzz-server-web.<vùng>.modal.run
```

Tắt server: `modal app stop guzz-server` (app tự làm trên máy khi server không trả lời). Xoá hẳn tệp còn lại:
`modal volume delete guzz-jobs`.

## Lưu tệp và riêng tư

- Tệp tải lên xoá ngay khi job xong hoặc lỗi; kết quả xoá khi điện thoại đã nhận (`DELETE`); hàm `cleanup` chạy mỗi
  ngày xoá mọi job cũ hơn 24 giờ.
- Log của server chỉ ghi mã job, cỡ tệp, thời gian; không ghi tên tệp hay nội dung.
- Server không gửi gì lên Google: điện thoại tự gửi các đoạn tới Google AI Studio như khi không dùng server.

## Chi phí

Giá Modal (2026-11-21): CPU $0,0000131 / nhân vật lý / giây, RAM $0,00000222 / GiB / giây; chỉ tính lúc có job. Hàm xử
lý chạy 16 nhân + 12 GiB (khoảng $0,85 cho mỗi giờ chạy) và tách giọng các phần 20 phút song song, vì server chỉ một
người dùng nên dồn tài nguyên cho nhanh.

Số đo (Stage 11 G3, Xiaomi 15T, bài 135 phút, 88 phút sau cắt lặng): tải lên 175 MB mất 104 s, server giải mã 12 s,
làm sạch + cắt đoạn 42 s, tách giọng 177 s; cả lượt tới lúc điện thoại có đủ đoạn và lượt nói **7 phút** (làm trên máy:
41 phút). Chi phí khoảng **$0,06–0,07 mỗi bài 2 giờ**, tức hơn 400 bài với $30 tín dụng / tháng của gói Starter.

Mạng chậm vẫn dùng được: tệp gửi theo phần 8 MB (D150), Modal cắt một yêu cầu kéo quá khoảng 13 phút.

## Giao thức

Mọi yêu cầu kèm proxy auth của Modal: header `Authorization: Bearer <Modal-Key>.<Modal-Secret>` (hoặc cặp header
`Modal-Key`, `Modal-Secret`); thiếu hay sai thì 401. Phiên bản giao thức hiện tại: 1.

| Yêu cầu | Việc |
| --- | --- |
| `GET /health` | `{"protocol": 1, "server": "<phiên bản>"}` |
| `POST /jobs?request=<JobRequest JSON>&parts=1` | tạo job ở trạng thái `uploading` (thân rỗng); khác phiên bản giao thức thì 409 |
| `PUT /jobs/{id}/parts/{k}` | phần thứ `k` (từ 0) của tệp, app dùng 8 MB / phần; chỉ nhận phần kế tiếp hoặc gửi lại phần cũ (không thì 409); trả `JobStatus` có `received` |
| `POST /jobs/{id}/start` | nhận đủ thì bắt đầu xử lý, trả `JobStatus` (`queued`) |
| `POST /jobs?request=<JobRequest JSON>` | cách cũ, cả tệp trong thân yêu cầu (script thử trên PC) |
| `GET /jobs/{id}` | `JobStatus` |
| `GET /jobs/{id}/result` | tar các tệp kết quả |
| `DELETE /jobs/{id}` | huỷ nếu đang chạy, xoá tệp |

Mẫu `JobRequest`:

```json
{
  "protocol": 1,
  "fileName": "bai_giang.m4a",
  "sourceName": "content://media/external/file/123",
  "audioKey": "3f2a…",
  "speakerKey": "9c1d…",
  "sampleRate": 16000,
  "chunk": {"targetDbfs": -16.0, "trimSilence": true, "chunkMinutes": 10.0},
  "clean": {"denoise": true, "levelWindowSec": 3.0},
  "saveClean": false,
  "saveExtracted": false,
  "bounds": {"min": 0, "max": 0},
  "runOnOriginal": false
}
```

Mẫu `JobStatus` khi xong:

```json
{
  "protocol": 1,
  "id": "a1b2c3",
  "state": "done",
  "step": "pack",
  "fraction": 1.0,
  "result": {"files": [{"name": "doan_001.flac", "size": 1234567, "sha256": "ab12…"}]}
}
```

`state`: `uploading`, `queued`, `running`, `done`, `failed`. Tải lên theo phần (D150) vì Modal cắt một yêu cầu kéo quá
khoảng 13 phút: mạng chậm (vd 85 KB/s) không gửi nổi bài dài trong một yêu cầu. Phần đứt thì app gửi lại phần đó (3 lần),
app bị giết thì lần sau hỏi `received` rồi gửi tiếp. Khi `failed` có `error`: `no_audio`, `no_speech` (làm trên máy cũng ra
lỗi đó, nên điện thoại không làm lại), `bad_request`, `cancelled`, `internal`.
