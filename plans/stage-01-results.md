# Stage 1 — Kết quả spike

> Điền dần trong lúc làm [Stage 1](stage-01-spike.md). Bài chỉ ghi bằng mã (B1–B5, L1, L2, A1, V1; bảng ở bước A2).
> Không chép lời nói hay tên tệp thật vào đây. Mỗi bảng ghi máy, bản Android và ngày đo.
>
> DER ở đây đo độ **lệch so với pyannote community-1 của desktop**. Tham chiếu là kết quả máy, không phải nhãn do người gán.

## Tóm tắt

| Mục | Kết luận | Mặc định chọn |
| --- | --- | --- |
| 1. Tách giọng: độ chính xác | chưa đo | |
| 2. Tách giọng: tốc độ, RAM, pin | chưa đo | |
| 3. Giải mã + resampler | chưa đo | |
| 4. Định dạng đoạn | chưa đo | |
| 5. Intent → chạy nền | chưa đo | |
| 6. Giấy phép | chưa kiểm | |

Đi tiếp / đổi hướng với sherpa-onnx: _chưa quyết_ (bước I2).

## 1. Độ chính xác trên PC

Cấu hình chung: sherpa-onnx 2.0.1, segmentation-3.0, DER đo với collar 0,5 s. Số liệu đo sau `process_turns`; số trong
ngoặc là trước khi làm mượt.

### B3. windowShiftRatio

| shift | DER TB | thời gian (s) | RAM đỉnh (MB) |
| --- | --- | --- | --- |

### B4. Embedding (num_clusters = số người của desktop)

| Embedding | B1 | B2 | B3 | B4 | B5 | TB |
| --- | --- | --- | --- | --- | --- | --- |

### B5. Ngưỡng phân cụm

| Embedding | Ngưỡng | DER TB | Số bài đạt D29 | Bài trượt (tiêu chí) |
| --- | --- | --- | --- | --- |

### B6–B8. int8, âm thanh gốc, chia phần

| Biến thể | B1 | B2 | B3 | B4 | B5 | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |

## 2. Tách giọng trên máy

| Đầu vào | Cách chạy | Luồng | Thời gian | PSS đỉnh | VmHWM | Heap Java đỉnh | Pin | Nhiệt đỉnh |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

Dừng giữa chừng (D7):

Dung lượng thư viện (C3), `apkanalyzer files list --raw-size --download-size` trên APK debug của app spike
(chỉ `arm64-v8a`), 2026-11-01:

| Tệp trong APK | Cỡ thô | Cỡ tải về (nén) | Ghi chú |
| --- | --- | --- | --- |
| `lib/arm64-v8a/libonnxruntime.so` | 22,2 MB | 7,9 MB | |
| `lib/arm64-v8a/libsherpa-onnx-jni.so` | 4,6 MB | 1,9 MB | DT_NEEDED chỉ có `libonnxruntime.so` + thư viện hệ thống |
| `lib/arm64-v8a/libsherpa-onnx-c-api.so` | 4,3 MB | 1,8 MB | JNI không dùng → Stage 9 bỏ được bằng `packaging.jniLibs.excludes` |
| `lib/arm64-v8a/libsherpa-onnx-cxx-api.so` | 0,3 MB | 0,1 MB | như trên |
| Cả APK debug (gồm Compose, media3, work, chưa R8) | 45,2 MB | 25,0 MB | |

AAR 2.0.1 **có** `libsherpa-onnx-c-api.so` cho cả 4 ABI, khác điều ghi lúc plan ("bản Android tắt C API"). Kotlin API vẫn
chỉ đi qua JNI.

## 3. Giải mã và resampler

| Tệp | Bộ giải mã | Resampler | × thời gian thực | PSS đỉnh | Lệch thời lượng |
| --- | --- | --- | --- | --- | --- |

| Resampler | SNR sweep 0–7 kHz | Suy giảm 10 kHz / 15 kHz | SNR tệp thật |
| --- | --- | --- | --- |

## 4. Định dạng đoạn

Bộ mã hoá có trên máy (F1):

| Định dạng | Bitrate / mức | MB / 10 phút | Thời gian mã hoá | Kiểm ffmpeg | Độ giống với FLAC 1 (Gemini) |
| --- | --- | --- | --- | --- | --- |

## 5. Intent → chạy nền

| Cách | Cài đặt | FGS lên / lần gửi | Lỗi gặp |
| --- | --- | --- | --- |

Chạy dài (G4), onTimeout (G5), sự kiện gửi ngược về MacroDroid (G6):

Hướng dẫn cài đặt cần ghi cho người dùng:

## 6. Giấy phép

| Thành phần | Giấy phép | Nguồn (URL) | Ngày kiểm | Ghi công |
| --- | --- | --- | --- | --- |
| pyannote segmentation-3.0 (`model.onnx`, `model.int8.onnx`) | MIT, © 2022 CNRS | thẻ HF `huggingface.co/api/models/pyannote/segmentation-3.0` (`license: mit`, gated); tệp `LICENSE` trong `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` cùng nội dung | 2026-11-01 | giữ câu bản quyền MIT trong màn hình giấy phép |
| 3D-Speaker CAM++ zh-cn common | Apache-2.0 | ModelScope API `modelscope.cn/api/v1/models/iic/speech_campplus_sv_zh-cn_16k-common` (`License: Apache License 2.0`) | 2026-11-01 | NOTICE / tên giấy phép |
| 3D-Speaker CAM++ zh_en advanced | Apache-2.0 | `…/iic/speech_campplus_sv_zh_en_16k-common_advanced` | 2026-11-01 | như trên |
| 3D-Speaker ERes2Net base 200k | Apache-2.0 | `…/iic/speech_eres2net_base_200k_sv_zh-cn_16k-common` | 2026-11-01 | như trên |
| NeMo TitaNet small | Theo giấy phép NeMo Toolkit = Apache-2.0 | `catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small`: "License to use this model is covered by the license of the NeMo Toolkit"; `NVIDIA/NeMo` Apache-2.0. Bản large trên HF là CC-BY-4.0 | 2026-11-01 | nếu chọn: ghi công NVIDIA như CC-BY cho chắc |
| WeSpeaker ResNet34-LM (VoxCeleb) | CC-BY-4.0 (theo dataset VoxCeleb) | `wenet-e2e/wespeaker/docs/pretrained.md`: model VoxCeleb "follows Creative Commons Attribution 4.0 International License"; thẻ HF `Wespeaker/wespeaker-voxceleb-resnet34-LM` `cc-by-4.0` | 2026-11-01 | nếu chọn: "Speaker embedding: WeSpeaker ResNet34-LM (VoxCeleb), CC BY 4.0, wenet-e2e/wespeaker" |
| sherpa-onnx 2.0.1 (AAR, JNI) | Apache-2.0 | `gh api repos/k2-fsa/sherpa-onnx/license` | 2026-11-01 | NOTICE / tên giấy phép |
| ONNX Runtime (`libonnxruntime.so` trong AAR) | MIT | `gh api repos/microsoft/onnxruntime/license`. AAR **không** kèm LICENSE / NOTICE / ThirdPartyNotices → Stage 9 tự thêm | 2026-11-01 | câu bản quyền MIT của Microsoft |
| androidx.media3 1.11.1 | Apache-2.0 | `gh api repos/androidx/media/license` | 2026-11-01 | |
| androidx.work 2.12.0 | Apache-2.0 | AndroidX (AOSP) | 2026-11-01 | |
| reverb-diarization v1 / v2 | phi thương mại | (lúc plan) | 2026-11-01 | **loại**, không dùng |
