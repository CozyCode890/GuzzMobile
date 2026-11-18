# Stage 9 D3: keep rules cho bản release (R8). AAR sherpa-onnx 2.0.1 có proguard.txt rỗng.

# Mã C++ của sherpa-onnx tìm class, field, method của API Kotlin theo tên qua JNI (config, kết quả, callback): đổi tên
# hay bỏ đi là app chết lúc tách giọng, chỉ ở bản release.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# JNI gọi `invoke(IIJ)Ljava/lang/Integer;` trên callback tiến độ (SherpaPieceDiarizer.ProgressCallback): R8 không thấy
# ai gọi nên sẽ bỏ hoặc đổi tên method này.
-keep class io.github.cozycode890.guzz.core.diarization.SherpaPieceDiarizer$ProgressCallback { *; }
