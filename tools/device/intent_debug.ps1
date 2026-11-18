# Gửi lệnh intent của Stage 6 tới Guzz (bản debug) như MacroDroid gửi, đọc lại sự kiện Guzz phát ra.
#
#   .\tools\device\intent_debug.ps1 -Enable on                       # bật "Cho phép app khác điều khiển Guzz"
#   .\tools\device\intent_debug.ps1 -Transcribe B1_1m.m4a -RequestId t1 -FakeGemini on
#   .\tools\device\intent_debug.ps1 -Transcribe /sdcard/Recordings/a.m4a -Activity -Speakers off -Preset speakers_around
#   .\tools\device\intent_debug.ps1 -Status ; .\tools\device\intent_debug.ps1 -Stop
#   .\tools\device\intent_debug.ps1 -Events                          # in files/debug-events/events.jsonl
#   .\tools\device\intent_debug.ps1 -Secret abc -Enable on ; .\tools\device\intent_debug.ps1 -Secret ''   # đặt / bỏ mã
#
# Tệp của -Transcribe không bắt đầu bằng "/" hay "content://" thì lấy trong files/input/ (adb push vào đó, như
# queue_debug.ps1). Mọi extra gửi dạng chuỗi (--es) như MacroDroid. -Activity: gửi tới CommandActivity (lối dự phòng,
# D94) thay cho CommandReceiver. -SendSecret: mã gửi kèm lệnh (khác -Secret là mã đặt trong app).
param(
    [string]$Transcribe,
    [string]$RequestId,
    [switch]$Activity,
    [switch]$Start,
    [switch]$Stop,
    [switch]$Status,
    [switch]$Events,
    [switch]$ClearEvents,
    [ValidateSet('on', 'off')][string]$Enable,
    [string]$Secret,
    [string]$SendSecret,
    [ValidateSet('on', 'off')][string]$IncludePaths,
    [ValidateSet('on', 'off')][string]$BesideSource,
    [ValidateSet('off', 'device', 'gemini', 'hybrid')][string]$Speakers,
    [int]$NumSpeakers = -1,
    [ValidateSet('near_lecturer', 'near_speaker', 'moving_lecturer', 'speakers_around')][string]$Preset,
    [ValidateSet('on', 'off')][string]$Denoise,
    [string]$OutputDir,
    [ValidateSet('on', 'off')][string]$FakeGemini,
    [ValidateSet('true', 'false')][string]$StartAfter
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$pkg = 'io.github.cozycode890.guzz.debug'
$files = "/sdcard/Android/data/$pkg/files"
$prefix = 'io.github.cozycode890.guzz'
$receiver = "$pkg/$prefix.feature.automation.CommandReceiver"
$activityName = "$pkg/$prefix.feature.automation.CommandActivity"
$debugReceiver = "$pkg/$prefix.debug.QueueDebugReceiver"

# Lệnh chạy qua shell của máy: bọc giá trị trong nháy đơn để dấu cách / ký tự đặc biệt không tách tham số.
function Quote([string]$v) { "'" + ($v -replace "'", "'\''") + "'" }

function Send-Command([string]$action, [string[]]$extra) {
    if ($Activity) {
        adb shell am start -W -n $activityName -a "$prefix.action.$action" @extra | Out-Null
    } else {
        adb shell am broadcast -n $receiver -a "$prefix.action.$action" @extra | Out-Null
    }
    Start-Sleep -Milliseconds 800
}

function Common-Extras {
    $e = @()
    if ($RequestId) { $e += @('--es', 'request_id', (Quote $RequestId)) }
    if ($PSBoundParameters.ContainsKey('SendSecret') -or $SendSecret) { $e += @('--es', 'secret', (Quote $SendSecret)) }
    return $e
}

# ---- cài đặt [AUTOMATION] qua lệnh adb của bản debug
$set = @()
if ($Enable) { $set += @('--es', 'enabled', $Enable) }
if ($IncludePaths) { $set += @('--es', 'include_paths', $IncludePaths) }
if ($BesideSource) { $set += @('--es', 'beside_source', $BesideSource) }
if ($PSBoundParameters.ContainsKey('Secret')) { $set += @('--es', 'secret', (Quote $Secret)) }
if ($ClearEvents) { $set += @('--es', 'clear_events', '1') }
if ($set.Count) {
    adb shell am broadcast -n $debugReceiver --es cmd automation @set | Out-Null
    Start-Sleep -Milliseconds 800
}

if ($Transcribe) {
    $source = if ($Transcribe.StartsWith('/') -or $Transcribe.StartsWith('content://')) { $Transcribe } else { "$files/input/$Transcribe" }
    $key = if ($source.StartsWith('content://')) { 'uri' } else { 'path' }
    $extra = @('--es', $key, (Quote $source)) + (Common-Extras)
    if ($Speakers) { $extra += @('--es', 'speakers', $Speakers) }
    if ($NumSpeakers -ge 0) { $extra += @('--es', 'num_speakers', "$NumSpeakers") }
    if ($Preset) { $extra += @('--es', 'preset', $Preset) }
    if ($Denoise) { $extra += @('--es', 'denoise', $Denoise) }
    if ($OutputDir) { $extra += @('--es', 'output_dir', (Quote $OutputDir)) }
    if ($FakeGemini) { $extra += @('--es', 'fake_gemini', $FakeGemini) }
    if ($StartAfter) { $extra += @('--es', 'start', $StartAfter) }
    Send-Command 'TRANSCRIBE' $extra
}
if ($Start) { Send-Command 'START' (Common-Extras) }
if ($Stop) { Send-Command 'STOP' (Common-Extras) }
if ($Status) {
    Send-Command 'STATUS' (Common-Extras)
    Start-Sleep -Seconds 1
    $Events = $true
}
if ($Events) {
    $text = adb shell cat "$files/debug-events/events.jsonl" 2>$null | Out-String
    if (-not $text.Trim()) { Write-Host '(chưa có sự kiện)'; return }
    foreach ($line in ($text -split "`n" | Where-Object { $_.Trim() })) {
        $e = $line | ConvertFrom-Json
        $time = [DateTimeOffset]::FromUnixTimeMilliseconds($e.time).LocalDateTime.ToString('HH:mm:ss')
        $rest = $e.PSObject.Properties | Where-Object { $_.Name -notin @('time', 'action', 'package') } |
            ForEach-Object { "$($_.Name)=$($_.Value)" }
        Write-Host ("{0} {1,-15} {2}" -f $time, $e.action, ($rest -join ' '))
    }
}
