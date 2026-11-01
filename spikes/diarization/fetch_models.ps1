# Bước A5 của Stage 1: tải model sherpa-onnx, tệp thử và AAR vào $env:GUZZ_SPIKE_DATA\models (ngoài repo).
#
#   .\spikes\diarization\fetch_models.ps1
#
# Mỗi tệp tải về nằm trong một thư mục riêng. Chạy lại được: tệp đã có và khớp SHA-256 thì bỏ qua.
# Kiểm toàn vẹn: model embedding so với checksum.txt của release speaker-recongition-models; AAR so với số đã kiểm ở
# plans/stage-01-spike.md ("Thông tin bên ngoài"); các tệp còn lại so với spikes/diarization/models.sha256 (lần đầu thì
# ghi vào tệp đó, commit tệp đó).

$ErrorActionPreference = 'Stop'

$D = if ($env:GUZZ_SPIKE_DATA) { $env:GUZZ_SPIKE_DATA } else { 'D:\GuzzMobileData\stage-01' }
$models = Join-Path $D 'models'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$shaFile = Join-Path $PSScriptRoot 'models.sha256'
$libsDir = Join-Path $repoRoot 'spikes\android\app\libs'

$base = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$aarSha = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
$embeddings = @(
    '3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx',
    '3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx',
    '3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx',
    'nemo_en_titanet_small.onnx',
    'wespeaker_en_voxceleb_resnet34_LM.onnx'
)

function Get-Sha([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }

# models.sha256: "<sha256>  <đường dẫn tương đối trong models\>", như sha256sum.
$known = [ordered]@{}
if (Test-Path $shaFile) {
    foreach ($line in Get-Content $shaFile) {
        if ($line -match '^([0-9a-f]{64})\s+(.+)$') { $known[$Matches[2]] = $Matches[1] }
    }
}

function Save-Known {
    $lines = foreach ($k in $known.Keys) { "$($known[$k])  $k" }
    Set-Content -LiteralPath $shaFile -Value $lines -Encoding utf8NoBOM
}

# Tải $url vào $models\$rel nếu chưa có hoặc sai SHA-256. $expected rỗng thì so với models.sha256, chưa có thì ghi vào.
function Get-Checked([string]$url, [string]$rel, [string]$expected) {
    $dst = Join-Path $models $rel
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    $want = if ($expected) { $expected } elseif ($known.Contains($rel)) { $known[$rel] } else { $null }
    if ((Test-Path -LiteralPath $dst) -and $want -and (Get-Sha $dst) -eq $want) {
        Write-Host "đã có, khớp SHA-256: $rel"
        return $dst
    }
    Write-Host "tải: $rel"
    $tmp = "$dst.part"
    curl.exe -L --fail --silent --show-error -o $tmp $url
    if ($LASTEXITCODE -ne 0) { throw "Tải lỗi: $url" }
    $sha = Get-Sha $tmp
    if ($want -and $sha -ne $want) {
        Remove-Item -LiteralPath $tmp
        throw "SHA-256 không khớp: $rel ($sha, cần $want)"
    }
    Move-Item -Force -LiteralPath $tmp -Destination $dst
    if (-not $expected -and -not $want) {
        $known[$rel] = $sha
        Save-Known
        Write-Host "  ghi SHA-256 lần đầu vào models.sha256"
    }
    return $dst
}

# 1. checksum.txt của release model embedding, rồi 5 model embedding (D33).
$checksum = Get-Checked "$base/speaker-recongition-models/checksum.txt" 'checksum\checksum.txt' ''
$embSha = @{}
foreach ($line in Get-Content $checksum) {
    # Dòng dạng "<sha256>  <tên tệp>" hoặc "<tên tệp> <sha256>".
    if ($line -match '([0-9a-f]{64})\s+(\S+)') { $embSha[$Matches[2]] = $Matches[1] }
    elseif ($line -match '(\S+)\s+([0-9a-f]{64})') { $embSha[$Matches[1]] = $Matches[2] }
}
foreach ($name in $embeddings) {
    if (-not $embSha.ContainsKey($name)) { throw "checksum.txt không có $name" }
    $stem = [IO.Path]::GetFileNameWithoutExtension($name)
    Get-Checked "$base/speaker-recongition-models/$name" "emb\$stem\$name" $embSha[$name] | Out-Null
}

# 2. Segmentation pyannote 3.0: giải nén ra seg\.
$tar = Get-Checked "$base/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" `
    'seg-tar\sherpa-onnx-pyannote-segmentation-3-0.tar.bz2' ''
$seg = Join-Path $models 'seg'
foreach ($f in 'model.onnx', 'model.int8.onnx') {
    $rel = "seg\$f"
    $p = Join-Path $models $rel
    if (-not ((Test-Path -LiteralPath $p) -and $known.Contains($rel) -and (Get-Sha $p) -eq $known[$rel])) {
        Write-Host "giải nén: seg-tar → seg\"
        $x = Join-Path $models 'seg-extract'
        if (Test-Path $x) { Remove-Item -Recurse -Force $x }
        New-Item -ItemType Directory -Force $x | Out-Null
        tar -xjf $tar -C $x
        if ($LASTEXITCODE -ne 0) { throw 'tar lỗi' }
        if (Test-Path $seg) { Remove-Item -Recurse -Force $seg }
        Move-Item (Get-ChildItem $x -Directory | Select-Object -First 1).FullName $seg
        Remove-Item -Recurse -Force $x
        break
    }
}
foreach ($f in 'model.onnx', 'model.int8.onnx') {
    $rel = "seg\$f"
    $sha = Get-Sha (Join-Path $models $rel)
    if ($known.Contains($rel)) {
        if ($known[$rel] -ne $sha) { throw "SHA-256 không khớp: $rel" }
        Write-Host "đã có, khớp SHA-256: $rel"
    } else {
        $known[$rel] = $sha
        Save-Known
        Write-Host "ghi SHA-256 lần đầu: $rel"
    }
}
Get-ChildItem $seg | ForEach-Object { Write-Host "  seg\$($_.Name) ($($_.Length) byte)" }

# 3. Tệp thử bốn người nói của sherpa-onnx (B1).
Get-Checked "$base/speaker-segmentation-models/0-four-speakers-zh.wav" 'test-wav\0-four-speakers-zh.wav' '' | Out-Null

# 4. AAR cho app spike (D31), so với SHA-256 đã kiểm lúc plan; chép vào spikes/android/app/libs (gitignore).
$aar = Get-Checked "$base/v2.0.1/sherpa-onnx-2.0.1.aar" 'aar\sherpa-onnx-2.0.1.aar' $aarSha
New-Item -ItemType Directory -Force $libsDir | Out-Null
$aarDst = Join-Path $libsDir 'sherpa-onnx-2.0.1.aar'
if ((Test-Path $aarDst) -and (Get-Sha $aarDst) -eq $aarSha) {
    Write-Host 'đã có, khớp SHA-256: spikes\android\app\libs\sherpa-onnx-2.0.1.aar'
} else {
    Copy-Item -Force $aar $aarDst
    Write-Host 'chép: spikes\android\app\libs\sherpa-onnx-2.0.1.aar'
}
