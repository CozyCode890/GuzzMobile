# Đo một lượt hàng đợi có tách giọng trên máy (Stage 5 G1): thêm tệp, Bắt đầu, tắt màn hình, rồi mỗi -Every giây ghi
# pin, nhiệt, PSS của process :diarize và của app, bước đang chạy, tới khi tệp xong / lỗi / dừng. Bản debug, đã đặt
# cài đặt bằng queue_debug.ps1 (-Fake on, -Speakers ...).
#
#   $env:ANDROID_SERIAL = '<ip-lan>:5555'
#   .\tools\device\measure_diarize.ps1 -Add L1_120m.m4a -Out D:\GuzzMobileData\stage-05\g1_L1_120m.csv
#
# In tóm tắt: thời gian tách giọng (từ log), PSS đỉnh của :diarize, nhiệt đỉnh, % pin tụt.
param(
    [Parameter(Mandatory)][string]$Add,
    [Parameter(Mandatory)][string]$Out,
    [int]$Every = 20
)

$ErrorActionPreference = 'Stop'
$pkg = 'io.github.cozycode890.guzz.debug'
$here = $PSScriptRoot
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null

function Battery {
    $t = adb shell dumpsys battery | Out-String
    [pscustomobject]@{
        level = [int]([regex]::Match($t, 'level: (\d+)').Groups[1].Value)
        temp = [int]([regex]::Match($t, 'temperature: (\d+)').Groups[1].Value) / 10.0
        plugged = $t -match '(AC|USB) powered: true'
    }
}

function PssMb([string]$proc) {
    $procId = (adb shell pidof $proc | Out-String).Trim()
    if (-not $procId) { return 0 }
    $m = adb shell dumpsys meminfo $procId | Out-String
    $kb = [regex]::Match($m, 'TOTAL PSS:\s+(\d+)').Groups[1].Value
    if (-not $kb) { $kb = [regex]::Match($m, 'TOTAL\s+(\d+)').Groups[1].Value }
    if (-not $kb) { return 0 }
    return [math]::Round([int]$kb / 1024)
}

& "$here\queue_debug.ps1" -Clear | Out-Null
$b0 = Battery
& "$here\queue_debug.ps1" -Add $Add -Start -ScreenOff | Out-Null
$t0 = Get-Date
"giay,pin,nhiet,cam_sac,pss_diarize_mb,pss_app_mb,trang_thai,buoc,tien_do" | Set-Content -Encoding utf8 $Out
$peakPss = 0; $peakTemp = $b0.temp; $last = $null
while ($true) {
    Start-Sleep -Seconds $Every
    $b = Battery
    $pd = PssMb "${pkg}:diarize"
    $pa = PssMb $pkg
    $s = & "$here\queue_debug.ps1" -Status 2>$null | Out-String
    $text = adb shell cat "/sdcard/Android/data/$pkg/files/debug-queue/trang_thai.json" | Out-String
    $st = $text | ConvertFrom-Json
    $item = $st.hang_doi.items | Select-Object -First 1
    $sec = [int]((Get-Date) - $t0).TotalSeconds
    "$sec,$($b.level),$($b.temp),$($b.plugged),$pd,$pa,$($item.status),$($item.stage),$($item.progress)" | Add-Content $Out
    $peakPss = [math]::Max($peakPss, $pd); $peakTemp = [math]::Max($peakTemp, $b.temp); $last = $b
    if ($item.status -in 'xong', 'loi', 'dung', 'bo_qua') { break }
}
$log = adb shell cat "/sdcard/Android/data/$pkg/files/logs/guzz.log" | Select-String 'Tách giọng xong sau' | Select-Object -Last 1
Write-Host "Tệp: $Add | trạng thái: $($item.status) $($item.error)"
Write-Host "Tổng: $([int]((Get-Date) - $t0).TotalMinutes) phút | $log"
Write-Host "PSS đỉnh :diarize $peakPss MB | nhiệt đỉnh $peakTemp °C | pin $($b0.level)% → $($last.level)% (cắm sạc lúc đầu: $($b0.plugged))"
