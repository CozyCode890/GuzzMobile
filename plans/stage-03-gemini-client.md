# Stage 3 — Client Gemini, hạn mức, đổi model

**Trạng thái:** ⬜ chưa plan chi tiết · **Cần xong trước:** 0 (làm song song được với Stage 1–2)

## Mục tiêu

Port phần gọi Google AI Studio, xử lý hạn mức và tự đổi sang model dự phòng, với cùng hành vi như desktop — kể cả chuỗi
lỗi đã ghi trong nhật ký ngày 17/09.

## Phạm vi

### Làm

- API key: nhập, kiểm tra, lưu mã hoá bằng Android Keystore. Không bao giờ ghi key ra log.
- Files API: tải lên kiểu resumable (start → upload, finalize), `wait_file_ready`, `delete_file`; `list_models`.
- Interactions API (`build_request`):
  - model đa năng: prompt + âm thanh + tệp văn bản đi kèm;
  - model `*-transcribe`: `transcription_config` (smart / verbatim, ngôn ngữ, từ vựng, tách người nói, mốc thời gian từng
    từ).
- Đọc kết quả: `extract_text`, `extract_words`, `check_status`, số token đầu vào.
- Phân loại lỗi `ERR_*` thành sealed class; đọc quota violation (`quotaId` theo ngày / theo phút), retry-after.
- `RateLimiter`: giới hạn token / phút và yêu cầu / phút.
- `QuotaTracker`: giờ tính lại hạn mức theo giờ Thái Bình Dương; khoá / mở khoá model; tự học hạn mức Google báo; đếm yêu
  cầu và token trong ngày; lịch sử 300 sự kiện gần nhất. Lưu trong app, không dùng chung tệp với desktop.
- `ChunkDispatcher`: chuỗi model dự phòng, chờ / khoá / đổi model, `max_all_locked_wait_min`, quay lại model chính.
- Ràng buộc model ↔ cách nhận diện người nói (`config.constraint_error`).
- Port test: `test_google_ai.py`, `test_quota.py` với HTTP giả lập (MockWebServer), gồm cả chuỗi lỗi 17/09.
- Màn hình tối thiểu: nhập key, Kiểm tra kết nối, chọn model.

### Không làm

- Trang Hạn mức & sử dụng đầy đủ (Stage 8).
- Gửi kèm bản đồ người nói (khung có sẵn, dùng thật ở Stage 5).

## Nguồn tham khảo trong Guzz desktop

- `google_ai.py`, `quota.py`
- `transcribe.py`: `ChunkDispatcher`, `tracker_for`
- `config.py`: `model_chain`, `constraint_error`, các mục `[GOOGLE_AI]`, `[FALLBACK]`, `[QUOTAS]`
- `tests/test_google_ai.py`, `tests/test_quota.py`, `tests/test_config.py`
- README desktop §6 (bảng xử lý lỗi)

## Kết quả bàn giao

- Module `:core:gemini` có test, chạy trên JVM.
- Gọi thật được một đoạn âm thanh từ màn hình debug.

## Tiêu chí hoàn thành

- [ ] Test port từ desktop đều qua.
- [ ] Chạy lại chuỗi lỗi 17/09: hành vi giống hệt desktop.
- [ ] Không có key trong log (kể cả ở mức DEBUG).

## Câu hỏi cần chốt khi plan chi tiết

- HTTP: OkHttp hay Ktor client?
- Lưu dữ liệu hạn mức: Room hay một tệp JSON (giống `su_dung.json`)?
- Ghi log yêu cầu gửi Google ở mức DEBUG như desktop không?
- Có cần nhập key / cài đặt từ bản desktop (đọc `config.txt`) không?

## Rủi ro

- Interactions API và các model `*-transcribe` có thể đã đổi so với lúc viết bản desktop → đầu stage kiểm lại tài liệu
  Google.

## Kế hoạch chi tiết

_(viết ở session của stage này)_

## Nhật ký

## Phát sinh

- (Từ Stage 0, 2026-10-31) Manifest của `:app` chưa khai báo `android:allowBackup` / `dataExtractionRules`, tức Android
  mặc định sao lưu dữ liệu app (gồm DataStore `files/datastore/settings.preferences_pb`) lên Google Drive và chuyển sang
  máy mới. Khi bắt đầu lưu API key, chốt chính sách sao lưu: ít nhất loại key ra khỏi bản sao lưu và chuyển máy.
