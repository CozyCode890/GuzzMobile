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

PC Ryzen 7 7435HS, 8 luồng, chạy lần lượt từng bài; embedding CAM++ zh-cn common; `num_clusters` = số người của desktop;
trên `sach.flac`; 2026-11-01. Thời gian là tổng 5 bài (408 phút âm thanh).

| shift | DER TB | Từng bài B1 / B2 / B3 / B4 / B5 | Bài đạt D29 | Thời gian (s) | RTF | RAM đỉnh (MB) |
| --- | --- | --- | --- | --- | --- | --- |
| 0,1 | 12,2 (11,6) | 4,3 / 2,8 / 7,2 / 45,5 / 1,3 | 3/5 (B2 số người; B4 cả ba) | 2 592 | 0,106 | 1 155 |
| 0,25 | 18,6 (18,6) | 4,3 / 0,8 / 50,1 / 35,5 / 2,4 | 3/5 (B3, B4: DER, người chính) | 653 | 0,027 | 971 |
| 0,5 | 14,1 (14,5) | 4,5 / 2,8 / 11,8 / 48,3 / 3,1 | 3/5 (B2 số người; B4 cả ba) | 284 | 0,012 | 942 |

Chọn **0,1** theo luật của B3: 0,25 và 0,5 làm DER trung bình tăng quá 1 điểm. Phần lớn chênh lệch không do shift mà do
phân cụm complete-linkage khi ép số cụm: B4 (4 người, người chính 53,6%) ở cả ba shift đều ra một cụm lớn + vài cụm
lẻ, làm mượt xong chỉ còn 1 người; B3 ở 0,25 bị tách đôi người nói chính (confusion 46,6%). RAM đỉnh trên PC phần lớn là
mảng âm thanh: bản Python chép mảng numpy sang `std::vector` (85 phút = 330 MB, nhân hai).

### B4. Embedding (num_clusters = số người của desktop)

PC, shift 0,1, trên `sach.flac`, 2026-11-01. Chạy 2 process song song × 8 luồng, nên cột thời gian chỉ so được giữa các
embedding với nhau. DER sau làm mượt (trong ngoặc: trước làm mượt), %.

| Embedding | B1 | B2 | B3 | B4 | B5 | TB | Đạt D29 | Thời gian 5 bài (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CAM++ zh-cn common | 4,3 | 2,8 | 7,2 | 45,5 | 1,3 | 12,2 (11,6) | 3/5 | 1 942 |
| **CAM++ zh_en advanced** | 4,6 | 2,8 | 7,2 | 25,3 | 1,3 | **8,2** (8,8) | 3/5 | 2 477 |
| **ERes2Net base 200k** | 4,6 | 1,3 | 8,9 | 33,6 | 1,3 | **9,9** (10,1) | 4/5 | 5 529 |
| NeMo TitaNet small | 2,5 | 2,8 | 6,5 | 45,5 | 1,3 | 11,7 (10,9) | 3/5 | 3 096 |
| WeSpeaker ResNet34-LM | 4,3 | 2,4 | 51,9 | 38,7 | 1,3 | 19,7 (18,8) | 3/5 | 4 537 |

Chọn cho B5: **CAM++ zh_en advanced** và **ERes2Net base** (DER trung bình thấp nhất). ERes2Net chậm gấp khoảng 2,2 lần
CAM++. B4 (4 người, người chính 53,6%) trượt với mọi embedding. B2 trượt "số người" ở 3 embedding vì làm mượt nuốt hết
người phụ (người chính 97,2% ở desktop).

### B5. Ngưỡng phân cụm

| Embedding | Ngưỡng | DER TB | Số bài đạt D29 | Bài trượt (tiêu chí) |
| --- | --- | --- | --- | --- |

### B6–B8. int8, âm thanh gốc, chia phần

| Biến thể | B1 | B2 | B3 | B4 | B5 | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |

## 2. Tách giọng trên máy

Xiaomi 15T (MT6899, 8 nhân, RAM 12 GB), Android 16 (API 36), HyperOS 3.0, app spike debug, sherpa-onnx 2.0.1 AAR,
2026-11-01. Bench `diarize`, segmentation-3.0 fp32, `min_duration_on` 0,2 / `min_duration_off` 0,5.

D2 (kiểm bản điện thoại so với bản PC): `L1_10m`, CAM++ zh-cn, shift 0,1, ngưỡng 0,5, 4 luồng. JSON của điện thoại
**trùng hoàn toàn** với JSON của `run_sherpa.py` trên PC: 144/144 lượt cùng mốc (±2 ms) và cùng nhãn, DER 0,0%.
(Ngưỡng 0,5 ra 25 người cho bài 1 người: ngưỡng chọn ở B5.)

D3 (số luồng), `L1_10m`, CAM++ zh-cn, shift 0,1, ngưỡng 0,5, máy cắm sạc. Chọn **4 luồng**: nhanh nhất; nhiều luồng hơn
lại chậm đi, có lẽ vì luồng thừa rơi vào nhân nhỏ và các luồng ONNX phải chờ nhau.

| Luồng | 1 | 2 | 3 | 4 | 4 (đo lặp) | 6 | 8 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Thời gian (s) | 212,7 | 125,4 | 120,7 | **77,2** | 90,3 | 113,2 | 107,5 |
| Màn hình | tắt | tắt | bật | tắt | bật | tắt | tắt |
| PSS đỉnh (MB) | 358 | 311 | 340 | 302 | 303 | 341 | 331 |

D4 và D5 (bảng dưới), D6 (pin và nhiệt). VmHWM là đỉnh RSS suốt đời process, nên lượt sau trong cùng process thừa hưởng
đỉnh của lượt trước; lấy PSS đỉnh làm số chính (tiêu chí D30).

| Đầu vào | Cách chạy | Luồng | Thời gian | PSS đỉnh | VmHWM | Heap Java đỉnh | Pin | Nhiệt đỉnh |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `L1_10m` | nguyên bài, CAM++ zh-cn, shift 0,1 | 4 | 77 s (RTF 0,13) | 302 MB | 545 MB | 51 MB | cắm sạc | 33 °C |
| `L1_60m` | nguyên bài, như trên | 4 | 716 s (RTF 0,20) | **3 739 MB** (native 3 729 MB) | 3 911 MB | 239 MB | cắm sạc | |
| `L1_120m` | nguyên bài, như trên | 4 | **bị giết sau 21 phút** | — | RSS 6,2 GB lúc bị giết | 443 MB (mảng 460 MB cấp được) | cắm sạc | |
| `L1_120m` | chia phần P = 20 phút, O = 60 s, CAM++ zh_en, ngưỡng **0,5 (tạm, ra 71 cụm)** | 4 | **1 372 s (22,9 phút)** | **556 MB** | | | rút cáp: −8% | 41,1 °C |

Ngưỡng 0,5 sinh quá nhiều cụm, mà RAM phình theo số cụm (xem B5), nên các số trên là cận trên; đo lại với ngưỡng của B5.

D4: cấp `FloatArray` 460 MB cho bài 2 giờ **được** (heap Java lên 443 / 512 MB), nhưng phần native (phân cụm) phình theo
bình phương số embedding: bài 60 phút đã 3,7 GB, bài 120 phút bị hệ thống giết lúc chạy native
(`ApplicationExitInfo` reason `LOW_MEMORY`, importance 125 = FGS, RSS 6,2 GB). **Chạy nguyên bài ở shift 0,1 không dùng
được trên điện thoại** → chia phần (D5).

Dừng giữa chừng (D7):

- Cách 1, callback trả về khác 0: `L1_10m`, callback trả về 1 từ khối 79 / 792. sherpa-onnx **vẫn gọi tiếp 712 lần và
  chạy tới hết** (63 s sau lần trả về đầu tiên). Giá trị trả về bị bỏ qua đúng như đọc mã nguồn, dù docstring Python ghi
  "Return a non-zero value to abort". Không dừng được bằng callback.
- Cách 3, process riêng: bench `diarize` chạy trong process `:diarize`, sau 30 s thì `Process.killProcess`. Process
  `:diarize` biến mất sau 54 ms, process chính vẫn sống, RAM trống của máy trở lại (4 795 → 4 983 MB sau 2 s; PSS của
  `:diarize` lúc giết 205 MB).
- Cách 2 (chia phần + lệnh Dừng): chưa đo.

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

E4, `spikes/media/compare_pcm.py`, 2026-11-01. Tệp tổng hợp 48 kHz giải mã trên máy rồi resample về 16 kHz. Suy giảm
10 / 15 kHz đo trên đoạn quét sin (RMS đầu ra lúc quét qua tần số đó so với RMS của sin 0,5). Biên độ 1 / 6 kHz đo trên
`tones48k` bằng bình phương tối thiểu.

| Resampler | SNR sweep 0–7 kHz | Suy giảm 10 kHz / 15 kHz | SNR tệp thật |
| --- | --- | --- | --- |
| `SincResampler` (32 zero-crossing, Kaiser β 8,6, cắt 7,52 kHz) | 50,9 dB so với `resample_poly` | −96,0 / −97,1 dB | |
| `SonicAudioProcessor` (Media3 1.11.1) | 50,3 dB | **0,0 / 0,0 dB**: không lọc chống alias | |

Biên độ trên `tones48k`: sinc giữ 1 kHz và 6 kHz đúng tới 0,001 dB, phần dư −97 dB. Sonic ra **toàn số 0**: tỉ lệ đúng 3 : 1
nên Sonic chỉ lấy mỗi mẫu thứ ba, không lọc gì; 15 kHz gập về 1 kHz, 10 kHz gập về 6 kHz, ngược pha, triệt tiêu đúng hai sin
còn lại. Giọng nói có năng lượng trên 8 kHz (nhất là âm xát), nên Sonic sẽ gập nhiễu vào dải nói.

## 4. Định dạng đoạn

Bộ mã hoá có trên máy (F1), Xiaomi 15T, Android 16 (API 36), 2026-11-01. Toàn bộ là bộ mã hoá phần mềm (`c2.android.*`,
bản OMX cũ chỉ là bí danh), không có bộ mã hoá âm thanh phần cứng:

| Bộ mã hoá | MIME | Bitrate | Tần số lấy mẫu | Ghi chú |
| --- | --- | --- | --- | --- |
| `c2.android.flac.encoder` | audio/flac | 1–21 000 000 | 7 350–192 000 | mức nén (complexity) 0–8 |
| `c2.android.opus.encoder` | audio/opus | 6 000–510 000 | 8 / 12 / 16 / 24 / 48 kHz | complexity 0–10 |
| `c2.android.aac.encoder` | audio/mp4a-latm | 8 000–510 000 | 8–48 kHz (9 giá trị, có 16 kHz) | |

MediaMuxer trên API 36 (`addTrack` mỗi cặp): MPEG_4 nhận FLAC và AAC; 3GPP nhận FLAC và AAC; WEBM chỉ nhận Opus; OGG chỉ
nhận Opus; Vorbis không định dạng nào nhận. **MPEG_4 nhận FLAC**, khác điều ghi lúc plan, và F2 ghi thật được: FLAC trong
MP4 giải mã ra trùng từng mẫu. Nhưng `_MIME` của desktop không có `.mp4`, và Gemini nhận FLAC qua `audio/flac` (tệp
`.flac`), nên Stage 2 vẫn tự ghi tệp `.flac`.

Mã hoá đoạn F1 (10 phút, 16 kHz mono, lượng tử hoá 16-bit trên máy) bằng bench `encode`, 2026-11-01. MB = 10⁶ byte.
Kiểm bằng `ffmpeg -v error -i <tệp> -f null -` (không lỗi ở mọi tệp) và giải mã ra PCM 16-bit.

| Định dạng | Bitrate / mức | MB / 10 phút | Thời gian mã hoá | Kiểm ffmpeg | Độ giống với FLAC 1 (Gemini) |
| --- | --- | --- | --- | --- | --- |
| FLAC (tự ghi `.flac`) | mức mặc định (5) | 9,56 | 6,2 s | 600,000 s; trùng từng mẫu với WAV | |
| FLAC (tự ghi `.flac`) | mức 8 | 9,55 | 4,4 s | 600,000 s; trùng từng mẫu | |
| FLAC trong MP4 (MediaMuxer) | mức 5 | 9,57 | 4,5 s | 600,000 s; trùng từng mẫu | |
| OGG-Opus | 24 kbps | 2,47 | 48,9 s | ra 48 kHz, 599,998 s | |
| OGG-Opus | 32 kbps | 2,98 | 45,7 s | ra 48 kHz, 599,998 s | |
| M4A-AAC-LC | 48 kbps | 3,64 | 11,9 s | 600,128 s: thừa 2 048 mẫu priming ở đầu; SNR so với WAV 28,0 dB | |
| M4A-AAC-LC | 64 kbps | 4,84 | 18,9 s | 600,128 s, như trên; SNR 29,2 dB | |
| WAV 16-bit | — | 19,20 | 0,02 s | 600,000 s | |

PSS đỉnh của cả lượt mã hoá 8 biến thể: 139 MB (gồm mảng float 10 phút 38 MB và mảng 16-bit 19 MB).

- FLAC: bộ mã hoá trả về buffer codec-config 93 byte, bắt đầu bằng `fLaC` + STREAMINFO, rồi các khung. STREAMINFO để
  trống tổng số mẫu và MD5; bench điền lại sau khi ghi xong.
- AAC: MediaMuxer không ghi edit list bỏ 2 048 mẫu priming, nên mọi mốc thời gian trong đoạn trễ 128 ms.

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
