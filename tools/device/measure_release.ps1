# Đo một lượt hàng đợi trên bản release (Stage 9 F4): cứ $Every giây ghi pin, nhiệt độ, sạc hay không, PSS của app và
# process :diarize, dòng log mới nhất của guzz.log, tới khi log báo "Hàng đợi xong" (hoặc quá $TimeoutMin phút).
# Không cần receiver của bản debug: thêm tệp bằng Chia sẻ tới Guzz (-ShareUri, URI MediaStore) hoặc tự thêm trước rồi
# chạy với -NoShare. Đặt $env:ANDROID_SERIAL trước. Chạy bằng pwsh.
#
#   pwsh .\tools\device\measure_release.ps1 -ShareUri content://media/external/file/8228 -Out D:\GuzzMobileData\stage-09\f4.csv
param(
    [string]$ShareUri,
    [switch]$NoShare,
    [Parameter(Mandatory)][string]$Out,
    [int]$Every = 20,
    [int]$TimeoutMin = 120,
    [string]$Package = 'io.github.cozycode890.guzz'
)

$ErrorActionPreference = 'Stop'
$logPath = "/sdcard/Android/data/$Package/files/logs/guzz.log"

# Pin: rút cáp thật để đo. `dumpsys battery unplug` (giả lập rút sạc) làm chính `dumpsys battery` đóng băng số liệu tới
# khi `dumpsys battery reset` (Stage 9 F4: 76 % / 33,4 °C suốt 10 phút); khi đó xem % pin ở dòng "Nhịp" của guzz.log.
# Nhiệt: `dumpsys thermalservice` (số sống của HAL; /sys/class/power_supply bị chặn quyền với adb shell).
function Battery {
    $t = adb shell dumpsys battery | Out-String
    $th = adb shell dumpsys thermalservice | Out-String
    $hal = $th.Substring([Math]::Max(0, $th.IndexOf('Current temperatures from HAL')))
    [pscustomobject]@{
        level   = [int]([regex]::Match($t, 'level: (\d+)').Groups[1].Value)
        temp    = [double]([regex]::Match($hal, 'mValue=([\d.]+), mType=\d+, mName=BATTERY,').Groups[1].Value)
        powered = ($t -match '(AC|USB|Wireless) powered: true')
    }
}

function PssMb([string]$proc) {
    $t = adb shell dumpsys meminfo $proc 2>$null | Out-String
    $m = [regex]::Match($t, 'TOTAL PSS:\s+(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($t, 'TOTAL\s+(\d+)') }
    if ($m.Success) { [math]::Round([int]$m.Groups[1].Value / 1024) } else { 0 }
}

$before = [int](adb shell "wc -l < $logPath" 2>$null)
if (-not $NoShare) {
    if (-not $ShareUri) { throw 'Cần -ShareUri hoặc -NoShare.' }
    adb shell am start -n "$Package/io.github.cozycode890.guzz.feature.automation.ShareActivity" `
        -a android.intent.action.SEND -t audio/mp4 --eu android.intent.extra.STREAM $ShareUri | Out-Null
}

$start = Get-Date
$rows = @()
"giay,pin,nhiet,sac,pss_app,pss_diarize,log" | Set-Content -Encoding utf8 $Out
while ($true) {
    $b = Battery
    $sec = [int]((Get-Date) - $start).TotalSeconds
    $last = (adb shell "tail -n 1 $logPath" 2>$null | Out-String).Trim() -replace '"', "'"
    $row = "{0},{1},{2},{3},{4},{5},""{6}""" -f $sec, $b.level, $b.temp, $b.powered, (PssMb $Package), (PssMb "$Package`:diarize"), $last
    Add-Content -Encoding utf8 $Out $row
    $new = adb shell "tail -n +$($before + 1) $logPath" 2>$null | Out-String
    if ($new -match 'Hàng đợi xong|Đã dừng') { break }
    if ($sec -gt $TimeoutMin * 60) { Write-Host 'Quá thời gian.'; break }
    Start-Sleep -Seconds $Every
}

$data = Import-Csv $Out
$maxT = ($data | Measure-Object -Property nhiet -Maximum).Maximum
$maxDia = ($data | ForEach-Object { [int]$_.pss_diarize } | Measure-Object -Maximum).Maximum
$maxApp = ($data | ForEach-Object { [int]$_.pss_app } | Measure-Object -Maximum).Maximum
"Xong sau {0:N1} phút. Pin {1}% → {2}%, nhiệt cao nhất {3} °C, PSS cao nhất app {4} MB / :diarize {5} MB." -f `
    (((Get-Date) - $start).TotalMinutes), $data[0].pin, $data[-1].pin, $maxT, $maxApp, $maxDia
adb shell "tail -n +$($before + 1) $logPath" | Select-String 'xong sau|Tách giọng|Hàng đợi xong|HOÀN THÀNH|LỖI|ERROR'
