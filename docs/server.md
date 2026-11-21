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

Giá Modal (2026-11-21): CPU $0,0000131 / nhân vật lý / giây, RAM $0,00000222 / GiB / giây. Hàm xử lý chạy 4 nhân + 4 GiB,
khoảng $0,22 cho mỗi giờ chạy; chỉ tính lúc có job. Số đo bài thật: xem mục "Số đo" (Stage 11 G3).

## Giao thức

Mọi yêu cầu kèm proxy auth của Modal: header `Authorization: Bearer <Modal-Key>.<Modal-Secret>` (hoặc cặp header
`Modal-Key`, `Modal-Secret`); thiếu hay sai thì 401. Phiên bản giao thức hiện tại: 1.

| Yêu cầu | Việc |
| --- | --- |
| `GET /health` | `{"protocol": 1, "server": "<phiên bản>"}` |
| `POST /jobs?request=<JobRequest JSON>` | thân là tệp âm thanh; trả `JobStatus` có `id`; khác phiên bản giao thức thì 409 |
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

`state`: `queued`, `running`, `done`, `failed`. Khi `failed` có `error`: `no_audio`, `no_speech` (làm trên máy cũng ra
lỗi đó, nên điện thoại không làm lại), `bad_request`, `cancelled`, `internal`.
