# Spike Stage 1

Code đo của [Stage 1](../plans/stage-01-spike.md). Số đo ghi ở [stage-01-results.md](../plans/stage-01-results.md).
Không module nào của app phụ thuộc vào thư mục này; `settings.gradle.kts` gốc không `include` gì ở đây (D32).

## Dữ liệu

Mọi thứ nặng hoặc lấy từ bản ghi thật (bản ghi, model, venv, JSON lượt nói, bản chép lời) nằm **ngoài repo**, trong
thư mục của biến môi trường `GUZZ_SPIKE_DATA` (mặc định `D:\GuzzMobileData\stage-01`). Trong tài liệu, bài chỉ được gọi
bằng mã (B1–B5, L1, L2, A1, V1); tên tệp thật chỉ nằm trong `$env:GUZZ_SPIKE_DATA\recordings.json`.

```powershell
[Environment]::SetEnvironmentVariable('GUZZ_SPIKE_DATA', 'D:\GuzzMobileData\stage-01', 'User')
$D = $env:GUZZ_SPIKE_DATA
```

## Mục lục

| Thư mục | Nội dung | Bước |
| --- | --- | --- |
| `diarization/` | Python trên PC: chuẩn bị dữ liệu, tải model, chạy sherpa-onnx, chấm DER so với desktop | A3–A5, B |
| `media/` | so PCM giải mã trên điện thoại với scipy / ffmpeg | E4 |
| `formats/` | gửi thử Gemini các định dạng đoạn | F3 |
| `android/` | app spike `io.github.cozycode890.guzz.spike`, build Gradle riêng | C–G |

## Đo trên PC (bước A, B)

```powershell
$D = $env:GUZZ_SPIKE_DATA
# A3: chép bài và tạo dữ liệu đo (chạy lại được, tệp đã có thì bỏ qua)
D:\Guzz\runtime\python\python.exe -B -s spikes\diarization\prepare_data.py
# A4: venv sherpa-onnx 2.0.1
uv venv --python 3.12 $D\venv
uv pip install --python $D\venv -r spikes\diarization\requirements.txt
# A5: model, tệp thử, AAR (so SHA-256)
.\spikes\diarization\fetch_models.ps1
# B2: kiểm cách chấm
D:\Guzz\runtime\nguoi_noi\python.exe -B -s spikes\diarization\evaluate.py --self-test
# B3–B8: chạy rồi chấm, kết quả ở $D\out\pc\<bước>\ (xem docstring của sweep.py)
& $D\venv\Scripts\python.exe spikes\diarization\sweep.py B3
# Một lần chạy lẻ và chấm lẻ
& $D\venv\Scripts\python.exe spikes\diarization\run_sherpa.py $D\B1\sach.flac --seg $D\models\seg\model.onnx `
    --emb <tệp .onnx> --threshold 0.5 --out $D\out\pc\thu\B1.json
D:\Guzz\runtime\nguoi_noi\python.exe -B -s spikes\diarization\evaluate.py --no-csv $D\out\pc\thu\B1.json
```

## App spike trên điện thoại (bước C–G)

```powershell
$pkg = 'io.github.cozycode890.guzz.spike.debug'
$files = "/sdcard/Android/data/$pkg/files"
$bench = "$pkg/io.github.cozycode890.guzz.spike.bench.BenchActivity"
.\gradlew -p spikes\android :app:installDebug
adb shell am start -n $pkg/io.github.cozycode890.guzz.spike.MainActivity
# Đưa dữ liệu lên (D1, E1). Bench nạp model theo đường dẫn tệp trong files/models.
adb push $D\models\seg\model.onnx "$files/models/seg/model.onnx"
adb push $D\L1\L1_10m.f32 "$files/input/"
# Chạy một phép đo; extra là tham số (xem KDoc của từng bench)
adb shell am start -n $bench --es bench noop --ei seconds 60
adb shell am start -n $bench --es bench diarize --es input L1_10m.f32 --es emb emb/<tệp>.onnx --ei threads 4 `
    --es shift 0.25 --es threshold 0.5
adb shell am start -n $bench --es bench decode --es input L1.m4a --es resampler sinc
adb shell am start -n $bench --es bench codecs
adb shell am start -n $bench --es bench encode --es variants flac,opus32,aac64,wav
adb shell am start -n $bench --es bench stop
# Lấy kết quả: results/<phép đo>.jsonl, results/diarize/*.json, results/decode/*.f32, results/formats/*
adb pull "$files/results/." $D\out\phone
```

Dòng `ket_thuc` trong `<phép đo>.jsonl` có thời gian, RAM đỉnh (`ram.pss_dinh_mb`, `vmhwm_mb`, `heap_java_dinh_mb`), pin
và nhiệt (`pin`) và kết quả (`ket_qua`).

## Macro MacroDroid (G2)

Trên điện thoại, **MacroDroid** phải được: pin "Không hạn chế" (No restrictions), bật "Tự khởi chạy" (Autostart) trong
cài đặt app của HyperOS, và có quyền "Hiển thị trên ứng dụng khác" (Display over other apps). Thiếu một trong ba thì
lỗi nằm ở phía gửi, không phải ở Guzz.

Tạo 4 macro. Ở trigger "Khoảng thời gian định kỳ" (Regular Interval) chọn **dùng báo thức** (use alarm) để trigger vẫn
chạy lúc máy ở Doze.

| Macro | Trigger | Action "Gửi Intent" (Send Intent) |
| --- | --- | --- |
| Spike broadcast | Regular Interval 30 phút | Target **Broadcast**; Action `io.github.cozycode890.guzz.spike.action.RUN`; Package `io.github.cozycode890.guzz.spike.debug`; Class `io.github.cozycode890.guzz.spike.automation.RunReceiver`; extra `method` = `broadcast`, extra `request_id` = Magic Text giờ hệ thống |
| Spike activity | Regular Interval 30 phút, bật sau macro đầu 10 phút | Target **Activity**; Package như trên; Class `io.github.cozycode890.guzz.spike.automation.TrampolineActivity`; extra `method` = `trampoline`, `request_id` = giờ hệ thống |
| Spike work | Regular Interval 30 phút, bật sau macro đầu 20 phút | Target **Broadcast**, như "Spike broadcast" nhưng `method` = `work` |
| Spike DONE | "Nhận Intent" (Intent Received), action `io.github.cozycode890.guzz.spike.event.DONE`, lưu extra `request_id` vào biến | "Ghi vào tệp" (Write to File), nối thêm một dòng: giờ + `request_id` + `method` |

Bench dài do MacroDroid khởi động (D6 / G4): như "Spike broadcast" nhưng thêm extra `bench` = `diarize` và các tham số
của bench (`input`, `emb`, `threads`, `shift`, `threshold`, `piece_minutes`). Xong bench, app phát DONE như trên.

Đếm lần gửi: nhật ký của MacroDroid (Nhật ký hệ thống / System log). Đếm lần nhận: `results/automation.jsonl` (`su_kien`
= `nhan`, `fgs_ok`, `start_fgs_loi`, `fgs_loi`, `nhip`, `done_gui`; mỗi dòng có `interactive`, `device_idle`,
`ignoring_battery_opt`, `standby_bucket`, lý do khởi động process).

## Lệnh đổi trạng thái máy (người dùng tự chạy)

```powershell
# Ép Doze ngay (G3, khi một đêm quá ít lần rơi vào Doze); hoàn tác:
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
# Gỡ miễn tối ưu pin của app spike (S0); kiểm danh sách miễn:
adb shell dumpsys deviceidle whitelist -io.github.cozycode890.guzz.spike.debug
adb shell dumpsys deviceidle whitelist
# G5: rút hạn mức FGS mediaProcessing còn 2 phút; hoàn tác:
adb shell device_config put activity_manager media_processing_fgs_timeout_duration 120000
adb shell device_config delete activity_manager media_processing_fgs_timeout_duration
```
