# Điều khiển hàng đợi thật (Stage 4, bản debug) qua adb: thêm tệp, Bắt đầu, Dừng, giết app, xem trạng thái.
#
#   .\tools\device\queue_debug.ps1 -Fake on                                   # Gemini giả, không gọi mạng
#   .\tools\device\queue_debug.ps1 -Add L1_20m.m4a -ChunkDelayMin 0.5 -Start -ScreenOff -Wait
#   .\tools\device\queue_debug.ps1 -Stop ; .\tools\device\queue_debug.ps1 -Start
#   .\tools\device\queue_debug.ps1 -Kill                                       # giết tiến trình (kill -9)
#   .\tools\device\queue_debug.ps1 -Status
#
# Tệp của -Add nằm sẵn trong files/input (adb push vào /sdcard/Android/data/<package>/files/input/) hoặc là đường dẫn
# tuyệt đối. Chưa chọn thư mục lưu SAF thì bản chép lời ra files/output/. -ChunkDelayMin: chờ giữa các đoạn (D67).
# Trạng thái: files/debug-queue/trang_thai.json. Log: files/logs/guzz.log.
param(
    [string]$Add,
    [double]$ChunkDelayMin = 0,
    [switch]$Start,
    [switch]$Stop,
    [switch]$Kill,
    [switch]$Clear,
    [switch]$Status,
    [switch]$ScreenOff,
    [ValidateSet('on', 'off')][string]$Fake,
    [switch]$Wait,
    [int]$TimeoutMin = 180
)

$ErrorActionPreference = 'Stop'
# adb in UTF-8: đọc đúng chữ có dấu trong trạng thái và log.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$pkg = 'io.github.cozycode890.guzz.debug'
$files = "/sdcard/Android/data/$pkg/files"
$receiver = "$pkg/io.github.cozycode890.guzz.debug.QueueDebugReceiver"

function Send([string[]]$extra) {
    adb shell am broadcast -n $receiver @extra | Out-Null
    Start-Sleep -Milliseconds 800
}

function Reconnect {
    # Qua Wi-Fi adb hay rớt khi máy ngủ: connect lại nếu ANDROID_SERIAL là địa chỉ mạng.
    if ((adb get-state 2>$null) -ne 'device' -and $env:ANDROID_SERIAL -and $env:ANDROID_SERIAL.Contains(':')) {
        adb connect $env:ANDROID_SERIAL 2>&1 | Out-Null
    }
}

function Read-Status {
    Send @('--es', 'cmd', 'status')
    $text = adb shell cat "$files/debug-queue/trang_thai.json" 2>$null | Out-String
    if (-not $text.Trim()) { return $null }
    return $text | ConvertFrom-Json
}

function Show-Status($s) {
    if (-not $s) { Write-Host '(chưa có trạng thái)'; return }
    $q = $s.hang_doi
    Write-Host ("vòng chạy: {0} | cờ đang chạy: {1} | Gemini giả: {2} | PID {3}" -f $s.dang_chay_vong, $q.running, $s.gemini_gia, $s.pid)
    foreach ($i in $q.items) {
        $detail = if ($i.chunk) { "đoạn $($i.chunk)/$($i.chunkTotal) $($i.model)" } elseif ($i.step) { $i.step } else { '' }
        $line = "  {0,-10} {1,5:P0} {2} {3}" -f $i.status, $i.progress, $i.displayName, $detail
        if ($i.error) { $line += " | LỖI: $($i.error)" }
        if ($i.outFile) { $line += " → $($i.outFile)" }
        Write-Host $line
    }
}

if ($Fake) { Send @('--es', 'cmd', 'fake', '--es', 'value', $Fake) }
if ($Clear) { Send @('--es', 'cmd', 'clear') }
if ($Add) {
    # Lệnh chạy qua shell của máy: bọc tên tệp trong nháy đơn để dấu cách / ký tự đặc biệt không tách tham số.
    $quoted = "'" + ($Add -replace "'", "'\''") + "'"
    $delay = $ChunkDelayMin.ToString([Globalization.CultureInfo]::InvariantCulture)
    Send @('--es', 'cmd', 'add', '--es', 'path', $quoted, '--ef', 'delay_min', $delay)
}
if ($Start) {
    # Android 12+ chỉ cho receiver ở nền khởi động foreground service khi app đã được miễn tối ưu pin (D69):
    #   adb shell dumpsys deviceidle whitelist +io.github.cozycode890.guzz.debug
    Send @('--es', 'cmd', 'start')
}
if ($Stop) { Send @('--es', 'cmd', 'stop') }
if ($ScreenOff) {
    Start-Sleep -Seconds 2
    adb shell input keyevent KEYCODE_SLEEP | Out-Null
}
if ($Kill) {
    $procId = (adb shell pidof $pkg | Out-String).Trim()
    if ($procId) {
        # am kill không giết tiến trình đang có foreground service; app debug nên run-as được.
        adb shell run-as $pkg kill -9 $procId | Out-Null
        Write-Host "Đã giết PID $procId"
    } else {
        Write-Host 'App không chạy.'
    }
}
if ($Wait) {
    $t0 = Get-Date
    while ($true) {
        Start-Sleep -Seconds 30
        Reconnect
        $s = Read-Status
        if ($s) {
            $run = $s.hang_doi.items | Where-Object { $_.status -eq 'dang_chay' } | Select-Object -First 1
            if ($run) { Write-Host ("[{0:HH:mm:ss}] {1} {2:P0} {3}" -f (Get-Date), $run.displayName, $run.progress, $run.stage) }
            if (-not $s.dang_chay_vong) {
                # Cờ đang chạy còn bật mà không có vòng chạy: bị hệ thống dừng (onTimeout), chờ mở app để làm tiếp.
                if ($s.hang_doi.running) { Write-Host 'Hàng đợi tạm ngưng (hệ thống dừng), mở app để làm tiếp.' }
                break
            }
        }
        if (((Get-Date) - $t0).TotalMinutes -gt $TimeoutMin) { throw "Quá $TimeoutMin phút" }
    }
}
if ($Status -or $Wait -or (-not ($Add -or $Start -or $Stop -or $Kill -or $Clear -or $Fake))) {
    Show-Status (Read-Status)
}
