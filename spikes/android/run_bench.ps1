# Chạy lần lượt các phép đo trên app spike qua adb, phép sau chờ phép trước ghi dòng "ket_thuc".
#
#   .\spikes\android\run_bench.ps1 -Log decode_L1 -Extra '--es','bench','decode','--es','input','L1.m4a'
#   .\spikes\android\run_bench.ps1 -Batch spikes\android\batches\e3.txt
#
# Tệp batch: mỗi dòng "<log> <extra…>" (vd "decode_L1_sinc --es bench decode --es input L1.m4a --es resampler sinc");
# dòng trống và dòng bắt đầu bằng # bỏ qua. Log đã có dòng ket_thuc thì bỏ qua dòng đó (chạy lại được).
# Kết quả nằm trong files/results/<log>.jsonl trên máy; xong mỗi phép đo thì kéo về $env:GUZZ_SPIKE_DATA\out\phone.
param(
    [string]$Log,
    [string[]]$Extra = @(),
    [string]$Batch,
    [int]$TimeoutMin = 240
)

$ErrorActionPreference = 'Stop'
$pkg = 'io.github.cozycode890.guzz.spike.debug'
$files = "/sdcard/Android/data/$pkg/files"
$activity = "$pkg/io.github.cozycode890.guzz.spike.bench.BenchActivity"
$D = if ($env:GUZZ_SPIKE_DATA) { $env:GUZZ_SPIKE_DATA } else { 'D:\GuzzMobileData\stage-01' }
$out = Join-Path $D 'out\phone'
New-Item -ItemType Directory -Force $out | Out-Null

# Qua Wi-Fi ($env:ANDROID_SERIAL = "<ip>:5555"), máy ngủ hay đổi mạng thì adb rớt kết nối: thử connect lại.
# Trả về true nếu adb đang thấy máy.
function Test-Adb {
    if ((adb get-state 2>$null) -eq 'device') { return $true }
    if ($env:ANDROID_SERIAL -and $env:ANDROID_SERIAL.Contains(':')) {
        adb connect $env:ANDROID_SERIAL 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        return (adb get-state 2>$null) -eq 'device'
    }
    return $false
}

function Get-EndCount([string]$name) {
    $text = adb shell "cat $files/results/$name.jsonl 2>/dev/null"
    return @($text | Select-String '"su_kien":"ket_thuc"').Count
}

function Invoke-Bench([string]$name, [string[]]$extras) {
    if ((Get-EndCount $name) -gt 0) {
        Write-Host "đã có: $name"
        return
    }
    Write-Host "chạy: $name $($extras -join ' ')"
    $t0 = Get-Date
    adb shell am start -n $activity --es log $name @extras | Out-Null
    $gone = 0
    while ((Get-EndCount $name) -eq 0) {
        if (((Get-Date) - $t0).TotalMinutes -gt $TimeoutMin) { throw "Quá $TimeoutMin phút: $name" }
        Start-Sleep -Seconds 5
        # Lỗi native (vd JNI abort) giết cả process mà không kịp ghi ket_thuc: thấy process mất 3 lần liền thì bỏ qua.
        # Mất kết nối adb thì không tính là process mất.
        if (-not (Test-Adb)) { continue }
        if (-not (adb shell pidof $pkg)) { $gone++ } else { $gone = 0 }
        if ($gone -ge 3) {
            Write-Host "  PROCESS CHẾT, không có ket_thuc: $name"
            adb logcat -d -b crash | Select-String 'Abort message|signal \d+' | Select-Object -Last 2 | ForEach-Object { Write-Host "  $_" }
            return
        }
    }
    adb pull "$files/results/$name.jsonl" (Join-Path $out "$name.jsonl") | Out-Null
    $end = (adb shell "cat $files/results/$name.jsonl" | Select-String '"su_kien":"ket_thuc"' | Select-Object -Last 1).Line |
        ConvertFrom-Json
    Write-Host ("  xong sau {0:N0} s; PSS đỉnh {1} MB; lỗi: {2}" -f $end.giay, $end.ram.pss_dinh_mb, $end.loi)
}

if ($Batch) {
    foreach ($line in Get-Content $Batch) {
        $line = $line.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\s+'
        Invoke-Bench $parts[0] ($parts | Select-Object -Skip 1)
    }
} else {
    Invoke-Bench $Log $Extra
}
