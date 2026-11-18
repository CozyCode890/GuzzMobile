# Thử giao diện trên máy ảo / điện thoại (Stage 8 A5, D121): đặt cấu hình màn hình, mở thẳng từng trang của bản debug
# (extra `route` của MainActivity), ghi cây giao diện bằng `android layout` (.json) và ảnh chụp (.png) cho mỗi trang.
#
#   .\tools\device\ui_check.ps1 -Out D:\GuzzMobileData\stage-08\mac_dinh
#   .\tools\device\ui_check.ps1 -Small -FontScale 2.0 -Out D:\GuzzMobileData\stage-08\nho_chu_to
#   .\tools\device\ui_check.ps1 -Dark -Lang en -Out D:\GuzzMobileData\stage-08\toi_en
#   .\tools\device\ui_check.ps1 -Landscape -Pages convert,settings -Out D:\GuzzMobileData\stage-08\ngang
#   .\tools\device\ui_check.ps1 -Reset                       # trả máy về như cũ (mật độ, cỡ chữ, sáng, dọc, ngôn ngữ app)
#   .\tools\device\ui_check.ps1 -NoDynamicColor on           # bộ màu Guzz dự phòng thay cho màu động (D111), off để tắt
#
# -Small: đặt mật độ điểm ảnh để bề ngang màn hình đúng 360 dp. -Lang: ngôn ngữ riêng của app (Android 13+,
# `cmd locale set-app-locales`), không đổi ngôn ngữ máy. Thiết bị: $env:ANDROID_SERIAL hoặc -Serial. Chạy bằng
# PowerShell 7 (pwsh): Windows PowerShell 5 đọc sai tệp UTF-8 không BOM.
param(
    [string]$Out,
    [string[]]$Pages = @('convert', 'quota', 'settings', 'settings/google_ai', 'settings/audio', 'settings/speakers',
        'settings/output', 'settings/queue', 'settings/automation', 'settings/system', 'settings/logs', 'settings/about'),
    [switch]$Small,
    [double]$FontScale = 0,
    [switch]$Dark,
    [ValidateSet('vi', 'en')][string]$Lang,
    [switch]$Landscape,
    [switch]$Reset,
    [ValidateSet('on', 'off')][string]$NoDynamicColor,
    [string]$Serial = $env:ANDROID_SERIAL,
    [int]$WaitMs = 2500
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$pkg = 'io.github.cozycode890.guzz.debug'
$activity = "$pkg/io.github.cozycode890.guzz.MainActivity"
if ($Serial) { $env:ANDROID_SERIAL = $Serial }

function Shell([string]$cmd) { adb shell $cmd | Out-Null }

if ($Reset) {
    Shell 'wm density reset'
    Shell 'settings put system font_scale 1.0'
    Shell 'cmd uimode night no'
    Shell 'settings put system user_rotation 0'
    Shell 'settings put system accelerometer_rotation 1'
    # Không có --locales = danh sách rỗng: app lại theo ngôn ngữ máy.
    Shell "cmd locale set-app-locales $pkg"
    Write-Host 'Đã trả máy về cấu hình mặc định.'
    if (-not $Out) { return }
}

if ($NoDynamicColor) {
    # Cờ chỉ bản debug đọc (MainActivity.NO_DYNAMIC_COLOR_FLAG); run-as vì nằm trong files/ riêng của app.
    if ($NoDynamicColor -eq 'on') { Shell "run-as $pkg sh -c 'mkdir -p files/debug && touch files/debug/no_dynamic_color'" }
    else { Shell "run-as $pkg rm -f files/debug/no_dynamic_color" }
    Shell "am force-stop $pkg"
    if (-not $Out) { return }
}

if (-not $Out) { throw 'Cần -Out <thư mục> (hoặc chỉ -Reset / -NoDynamicColor).' }
New-Item -ItemType Directory -Force $Out | Out-Null

if ($Small) {
    # Bề ngang (px) chia mật độ = 360 dp.
    $size = (adb shell wm size) -join "`n"
    if ($size -notmatch '(\d+)x(\d+)') { throw "Không đọc được kích thước màn hình: $size" }
    $width = [Math]::Min([int]$Matches[1], [int]$Matches[2])
    $density = [int][Math]::Round($width / 360.0 * 160)
    Shell "wm density $density"
}
if ($FontScale -gt 0) { Shell "settings put system font_scale $FontScale" }
if ($Dark) { Shell 'cmd uimode night yes' }
if ($Lang) { Shell "cmd locale set-app-locales $pkg --locales $Lang" }
if ($Landscape) {
    Shell 'settings put system accelerometer_rotation 0'
    Shell 'settings put system user_rotation 1'
}

Shell "am force-stop $pkg"
Start-Sleep -Milliseconds 500
$first = $true
foreach ($page in $Pages) {
    $name = $page -replace '/', '_'
    adb shell am start -n $activity --es route $page 2>&1 | Out-Null
    # Trang đầu: app vừa khởi động (đọc cài đặt, có thể dựng lại vì đổi ngôn ngữ) nên chờ lâu hơn.
    Start-Sleep -Milliseconds ($(if ($first) { $WaitMs + 4000 } else { $WaitMs }))
    $first = $false
    if ($Serial) { android layout "--device=$Serial" "--output=$Out\$name.json" | Out-Null }
    else { android layout "--output=$Out\$name.json" | Out-Null }
    $png = "$Out\$name.png"
    $remote = '/sdcard/ui_check.png'
    Shell "screencap -p $remote"
    adb pull $remote $png 2>&1 | Out-Null
    Shell "rm $remote"
    Write-Host "$page -> $png"
}
Write-Host "Xong. Nhớ chạy -Reset khi thử xong."
