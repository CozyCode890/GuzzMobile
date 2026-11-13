# Máy ảo để thử Stage 4 trở đi (D70): AVD guzz_15t giống Xiaomi 15T (Android 16 / API 36, 1280×2772, 520 dpi), RAM 4 GB.
#
#   .\tools\device\emulator.ps1                 # tạo AVD nếu chưa có, khởi động, cài APK debug, miễn tối ưu pin
#   .\tools\device\emulator.ps1 -Push L1_20m.m4a,B1_1m.m4a   # thêm: đẩy tệp thử vào files/input/
#   .\tools\device\emulator.ps1 -Wipe           # khởi động với dữ liệu sạch (xoá app, cài lại)
#   .\tools\device\emulator.ps1 -Headless       # không mở cửa sổ
#
# Cần system image: sdkmanager "system-images;android-36;google_apis;x86_64". Tệp thử lấy từ $env:GUZZ_SPIKE_DATA\..\stage-04
# (mặc định D:\GuzzMobileData\stage-04) hoặc đường dẫn tuyệt đối. In ra serial (emulator-5556…): đặt
# $env:ANDROID_SERIAL bằng serial đó rồi dùng queue_debug.ps1, audio_debug.ps1 như trên điện thoại.
# AVD itc_test có sẵn trên PC là của dự án khác: script không đụng tới.
param(
    [string[]]$Push = @(),
    [switch]$Wipe,
    [switch]$Headless,
    [int]$Port = 5556
)

$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avdmanager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$image = 'system-images;android-36;google_apis;x86_64'
$avd = 'guzz_15t'
$avdDir = Join-Path $env:USERPROFILE ".android\avd\$avd.avd"
$pkg = 'io.github.cozycode890.guzz.debug'
$serial = "emulator-$Port"
$dataDir = if ($env:GUZZ_STAGE4_DATA) { $env:GUZZ_STAGE4_DATA } else { 'D:\GuzzMobileData\stage-04' }

if (-not (Test-Path (Join-Path $sdk 'system-images\android-36\google_apis\x86_64'))) {
    throw "Thiếu system image. Chạy: sdkmanager `"$image`""
}

# ---- tạo AVD (một lần)
if (-not (Test-Path $avdDir)) {
    Write-Host "Tạo AVD $avd"
    'no' | & $avdmanager create avd -n $avd -k $image -d pixel_7_pro | Out-Null
}
# Thông số như Xiaomi 15T (D22: màn hình 1280×2772, 520 dpi); RAM 4 GB vì PC có 16 GB; heap như máy thật (512 MB, D34).
$cfg = Join-Path $avdDir 'config.ini'
$want = [ordered]@{
    'hw.lcd.width' = '1280'; 'hw.lcd.height' = '2772'; 'hw.lcd.density' = '520'
    'hw.ramSize' = '4096'; 'vm.heapSize' = '512'; 'hw.cpu.ncore' = '4'
    'disk.dataPartition.size' = '8G'; 'hw.keyboard' = 'yes'; 'showDeviceFrame' = 'no'
}
$lines = Get-Content $cfg | Where-Object { $k = ($_ -split '=', 2)[0].Trim(); -not $want.Contains($k) }
$lines += $want.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
Set-Content -Path $cfg -Value $lines -Encoding ascii

# ---- khởi động
$env:ANDROID_SERIAL = $serial
if ((adb -s $serial get-state 2>$null) -ne 'device') {
    $emuArgs = @('-avd', $avd, '-port', $Port, '-no-boot-anim', '-no-snapshot-save')
    if ($Wipe) { $emuArgs += '-wipe-data' }
    if ($Headless) { $emuArgs += '-no-window' }
    Write-Host "Khởi động $avd ($serial)"
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
    adb -s $serial wait-for-device
    $t0 = Get-Date
    while ((adb -s $serial shell getprop sys.boot_completed 2>$null) -ne '1') {
        if (((Get-Date) - $t0).TotalMinutes -gt 5) { throw 'Máy ảo không khởi động xong sau 5 phút' }
        Start-Sleep -Seconds 3
    }
}

# ---- cài app, miễn tối ưu pin (D69: máy ảo nên script tự làm), cấp quyền thông báo
$apk = 'app\build\outputs\apk\sideload\debug\app-sideload-debug.apk'
if (-not (Test-Path $apk)) { throw "Chưa có $apk. Chạy .\gradlew assembleSideloadDebug" }
adb -s $serial install -r -t $apk | Out-Null
adb -s $serial shell dumpsys deviceidle whitelist "+$pkg" | Out-Null
adb -s $serial shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

# ---- tệp thử
$inputDir = "/sdcard/Android/data/$pkg/files/input/"
adb -s $serial shell mkdir -p $inputDir | Out-Null
foreach ($f in $Push) {
    $src = if ([IO.Path]::IsPathRooted($f)) { $f } else { Join-Path $dataDir $f }
    adb -s $serial push $src $inputDir | Out-Null
    Write-Host "Đã đẩy $src"
}
Write-Host "Sẵn sàng: `$env:ANDROID_SERIAL = '$serial'"
