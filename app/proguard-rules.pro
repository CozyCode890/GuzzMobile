# Keep rules của app cho bản release (R8). Keep rules của sherpa-onnx (JNI) ở core/diarization/consumer-rules.pro.

# Giữ tên các class lỗi của Guzz. Lỗi không lường trước được ghi thành "<tên class>: <thông báo>" (như
# `type(e).__name__` ở desktop): vào trạng thái tệp trong hàng đợi, thông báo xong việc, sự kiện gửi MacroDroid và
# guzz.log. Không giữ tên thì bản release ghi kiểu "ke1: Không đọc được …". Vẫn cho R8 bỏ class lỗi không dùng.
-keepnames class io.github.cozycode890.guzz.** extends java.lang.Throwable
