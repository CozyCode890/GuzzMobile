# Chạy màn hình thử lõi âm thanh (Stage 2, bản debug) cho một tệp trên điện thoại qua adb, chờ xong rồi in kết quả.
#
#   .\tools\device\audio_debug.ps1 -Path L1_120m.m4a -ScreenOff
#   .\tools\device\audio_debug.ps1 -Path two_tracks.mp4 -Track 1
#   .\tools\device\audio_debug.ps1 -Path V1.mp4 -PullPcm "$env:GUZZ_SPIKE_DATA\out\stage-02"
#
# Tệp phải nằm sẵn trong files/input của app (adb push vào /sdcard/Android/data/<package>/files/input/). Kết quả ở
# files/debug-audio/<tên bài>/ket_qua.json; -Pull kéo ket_qua.json và danh_sach_doan.json về, -PullPcm kéo thêm goc.pcm.
# -ScreenOff tắt màn hình ngay sau khi bắt đầu (phím SLEEP), để đo đúng điều kiện D45.
param(
    [Parameter(Mandatory)][string]$Path,
    [string]$Track = 'auto',
    [switch]$ScreenOff,
    [string]$Pull,
    [string]$PullPcm,
    [int]$TimeoutMin = 60
)

$ErrorActionPreference = 'Stop'
$pkg = 'io.github.cozycode890.guzz.debug'
$files = "/sdcard/Android/data/$pkg/files"
$activity = "$pkg/io.github.cozycode890.guzz.debug.AudioDebugActivity"
$name = [IO.Path]::GetFileNameWithoutExtension($Path) -replace '[^\p{L}\p{N}._-]', '_'
$folder = "$files/debug-audio/$name"

adb shell rm -f "$folder/ket_qua.json" | Out-Null
# -S: dừng hẳn app rồi mở lại. Không có -S mà màn hình thử còn nằm trên cùng từ lần trước thì intent mới chỉ được giao
# khi activity hiện lại (máy đang khoá thì không bao giờ), nên lần chạy không bắt đầu. Mở mới cũng cho số RAM sạch.
adb shell input keyevent KEYCODE_WAKEUP | Out-Null
adb shell am start -S -n $activity --es path $Path --es track $Track --ez auto_run true | Out-Null
if ($ScreenOff) {
    Start-Sleep -Seconds 2
    adb shell input keyevent KEYCODE_SLEEP | Out-Null
}
$t0 = Get-Date
while (-not (adb shell "ls $folder/ket_qua.json 2>/dev/null")) {
    if (((Get-Date) - $t0).TotalMinutes -gt $TimeoutMin) { throw "Quá $TimeoutMin phút: $Path" }
    Start-Sleep -Seconds 5
    # Qua Wi-Fi adb hay rớt khi máy ngủ: connect lại nếu ANDROID_SERIAL là địa chỉ mạng.
    if ((adb get-state 2>$null) -ne 'device' -and $env:ANDROID_SERIAL -and $env:ANDROID_SERIAL.Contains(':')) {
        adb connect $env:ANDROID_SERIAL 2>&1 | Out-Null
    }
}
$json = adb shell cat "$folder/ket_qua.json" | Out-String
$r = $json | ConvertFrom-Json
if ($Pull -or $PullPcm) {
    $dest = Join-Path ($Pull ? $Pull : $PullPcm) $name
    New-Item -ItemType Directory -Force $dest | Out-Null
    adb pull "$folder/ket_qua.json" $dest | Out-Null
    adb pull "$folder/danh_sach_doan.json" $dest 2>$null | Out-Null
    if ($PullPcm) { adb pull "$folder/goc.pcm" $dest | Out-Null }
}
if ($r.ket_qua -ne 'ok') {
    Write-Host "${Path}: LỖI $($r.loi)"
} else {
    Write-Host ("{0}: {1:N1} phút âm thanh ({2}, {3} Hz × {4}) → {5} đoạn trong {6:N1} s (×{7:N1}); giải mã {8:N1} s, " +
        "cắt đoạn {9:N1} s; PSS đỉnh {10} MB; màn hình bật lúc đầu / cuối: {11} / {12}") -f $Path, ($r.giay_am_thanh / 60),
        $r.bo_giai_ma, $r.tan_so_goc, $r.so_kenh_goc, $r.so_doan, $r.giay_xu_ly, $r.x_thoi_gian_thuc, ($r.ms_giai_ma / 1000),
        ($r.ms_cat_doan / 1000), $r.ram.pss_dinh_mb, $r.man_hinh_bat_luc_dau, $r.man_hinh_bat_luc_cuoi
    Write-Host "  từng bước (ms): $($r.ms_tung_buoc | ConvertTo-Json -Compress)"
}
