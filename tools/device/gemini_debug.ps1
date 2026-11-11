# Gửi thử Gemini từ màn hình thử của bản debug (Stage 3), chờ xong rồi in kết quả. GỬI THẬT LÊN GOOGLE (trừ khi chưa
# lưu key): hỏi người dùng trước khi chạy.
#
#   .\tools\device\gemini_debug.ps1 -ListModels
#   .\tools\device\gemini_debug.ps1 -Folder L1_1m -Mode smart                          # model chính
#   .\tools\device\gemini_debug.ps1 -Folder L1_1m -Model gemini-3.5-transcribe -Mode words
#
# -Folder: thư mục bài trong files/debug-audio (đã cắt bằng tools/device/audio_debug.ps1), gửi doan_001.flac của nó.
# -Mode: smart | verbatim | words (mốc từng từ) | diarize (Google tách người nói).
# Kết quả: files/debug-gemini/<bài>_<model>_<chế độ>.json (hoặc models.json); -Pull kéo về thư mục chỉ định.
# Key nhập trên điện thoại ở trang Google AI; script không bao giờ nhận hay in key.
param(
    [string]$Folder,
    [string]$Model = '',
    [ValidateSet('smart', 'verbatim', 'words', 'diarize')][string]$Mode = 'smart',
    [string]$Chunk = 'doan_001.flac',
    [switch]$ListModels,
    [string]$Pull,
    [int]$TimeoutMin = 10
)

$ErrorActionPreference = 'Stop'
$pkg = 'io.github.cozycode890.guzz.debug'
$out = "/sdcard/Android/data/$pkg/files/debug-gemini"
$activity = "$pkg/io.github.cozycode890.guzz.debug.AudioDebugActivity"

if ($ListModels) {
    $result = "$out/models.json"
    adb shell rm -f $result | Out-Null
    adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    # -S như audio_debug.ps1: màn hình thử còn nằm trên cùng thì intent mới chỉ tới khi activity hiện lại.
    adb shell am start -S -n $activity --ez gemini_list_models true | Out-Null
} else {
    if (-not $Folder) { throw 'Thiếu -Folder (hoặc dùng -ListModels).' }
    if (-not $Model) {
        # Model chính lấy trong cài đặt của app; tên tệp kết quả cần biết model nên đọc lại sau khi xong.
        $pattern = "${Folder}_*_$Mode.json"
    } else {
        $pattern = ("${Folder}_${Model}_$Mode" -replace '[^\p{L}\p{N}._-]', '_') + '.json'
    }
    adb shell "rm -f $out/$pattern" | Out-Null
    adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $extra = @('--ez', 'gemini_send', 'true', '--es', 'gemini_folder', $Folder, '--es', 'gemini_mode', $Mode,
        '--es', 'gemini_chunk', $Chunk)
    if ($Model) { $extra += @('--es', 'gemini_model', $Model) }
    adb shell am start -S -n $activity @extra | Out-Null
    $result = "$out/$pattern"
}

$t0 = Get-Date
while (-not ($found = adb shell "ls $result 2>/dev/null")) {
    if (((Get-Date) - $t0).TotalMinutes -gt $TimeoutMin) { throw "Quá $TimeoutMin phút chưa có $result" }
    Start-Sleep -Seconds 3
}
$found = ($found | Select-Object -First 1).Trim()
$r = adb shell cat $found | Out-String | ConvertFrom-Json
if ($Pull) {
    New-Item -ItemType Directory -Force $Pull | Out-Null
    adb pull $found $Pull | Out-Null
}
if ($ListModels) {
    if ($r.ket_qua -eq 'ok') { Write-Host "list_models: $($r.so_model) model"; $r.model | ForEach-Object { "  $_" } }
    else { Write-Host "list_models: LỖI $($r.loai_loi): $($r.loi)" }
} elseif ($r.ket_qua -eq 'ok') {
    Write-Host ("{0} ({1}): {2} ký tự, {3} từ có mốc, {4} token, {5:N1} s" -f $r.model, $r.che_do, $r.so_ky_tu,
        $r.so_tu_co_moc, $r.token, $r.giay_xu_ly)
    Write-Host "  chữ: $($r.chu.Substring(0, [Math]::Min(300, $r.chu.Length)))"
    if ($r.so_tu_co_moc -gt 0) { Write-Host "  từ đầu: $($r.tu_dau -join ' | ')" }
} else {
    Write-Host "$($r.model_yeu_cau) ($($r.che_do)): LỖI $($r.loai_loi): $($r.loi)"
}
Write-Host "  HTTP: $($r.http -join ' | ')"
if ($r.su_kien_tracker) { Write-Host "  sự kiện hạn mức: $($r.su_kien_tracker -join ' | ')" }
Write-Host "  tệp: $found"
