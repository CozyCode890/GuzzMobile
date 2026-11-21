# Server xử lý âm thanh (Modal)

Từ bản 1.1.0 (Stage 11), Guzz có thể gửi phần nặng nhất (làm sạch âm thanh, cắt đoạn, tách giọng) lên một server riêng
của bạn trên [Modal](https://modal.com). Điện thoại chỉ tách luồng âm thanh, tải lên, nhận về các đoạn FLAC và lượt nói,
rồi tự gửi Google AI Studio như cũ. Server lỗi thì điện thoại tự làm trên máy.

## Giao thức

Mọi yêu cầu kèm hai header proxy auth của Modal (`Modal-Key`, `Modal-Secret`). Phiên bản giao thức hiện tại: 1.

| Yêu cầu | Việc |
| --- | --- |
| `GET /health` | `{"protocol": 1, "server": "<phiên bản>"}` |
| `POST /jobs?request=<JobRequest JSON>` | thân là tệp âm thanh; trả `JobStatus` có `id` |
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
